#!/usr/bin/env bash
# ============================================================================
# metrics-snapshot.sh — 부하 측정 창의 지표 스냅샷 수집기 (읽기 전용)
#
# 알람이 없어서 "부하 중 뭐가 먼저 터졌나"를 사후에 못 따지는 결함을 보정한다.
# 부하 시작 전에 켜 두고 종료 후 끄면, 라운드 전체 구간의 시계열이 CSV로 남는다.
#
# 실행 위치: **semi 노드 안**(SSM 셸 또는 send-command). 로컬 kubectl로는 못 돈다.
#
# NetworkPolicy 주의 — observability 네임스페이스는 `observability-intra-only`
# default-deny가 걸려 있어 **노드 호스트에서 ClusterIP/파드IP로 직접 curl이 안 된다**
# (apiserver service proxy도 502로 막힌다. openat의 WireMock ClusterIP가 노드에서
# 닿는 것과 다르다). 그래서 기본 경로는 `kubectl port-forward`다 — port-forward는
# kubelet이 파드 netns로 직접 들어가므로 NetworkPolicy 적용 대상이 아니다.
# 직접 curl이 되는 환경이면 자동으로 그 쪽을 먼저 쓴다.
#
# 사용법:
#   ./metrics-snapshot.sh <round-name> [interval_sec] [duration_sec]
#     interval_sec  기본 15
#     duration_sec  생략하면 Ctrl+C까지 무한
#
# 환경변수:
#   PROM_URL      Prometheus 베이스 URL을 직접 지정(자동 탐색·port-forward 건너뜀)
#   PROM_NS       기본 observability
#   PROM_SVC      기본 prometheus
#   PROM_PORT     생략 시 Service에서 실측(20-prometheus.yaml 기준 9090)
#   LOCAL_PORT    port-forward 로컬 포트, 기본 19090
#   RESULTS_DIR   출력 루트, 기본 <스크립트>/../results
#   RATE_WINDOW   rate()/increase() 윈도, 기본 2m
#                 (스크레이프 실효 30초 — otel-collector.yaml scrape_interval.
#                  2 스크레이프 미만 윈도는 빈 결과가 되므로 2m 미만으로 줄이지 말 것)
#
# 트레이스 요약(Tempo) — 수집이 끝날 때 딱 1회만 질의해 traces.txt를 남긴다:
#   TRACES        0이면 트레이스 질의를 통째로 건너뛴다. 기본 1
#   TEMPO_URL     Tempo 베이스 URL 직접 지정(자동 탐색·port-forward 건너뜀)
#   TEMPO_NS      기본 observability
#   TEMPO_SVC     기본 tempo
#   TEMPO_PORT    생략 시 Service의 http 포트에서 실측(10-tempo.yaml 기준 3200)
#   TEMPO_LOCAL_PORT  Tempo용 port-forward 로컬 포트, 기본 13200
#                 (Prometheus용 LOCAL_PORT와 반드시 달라야 한다 — 둘이 동시에 뜬다)
#   TRACE_TOPN    느린 트레이스 상위 몇 개를 적을지, 기본 20
#   TRACE_LIMIT   질의 1건이 Tempo에서 받아올 트레이스 상한, 기본 100
#   TRACE_ERR_LIMIT  에러 질의 전용 상한, 기본 200
#   TRACE_TIMEOUT 트레이스 질의 1건 타임아웃(초), 기본 60
#   TRACE_SLOW_MIN 느린 트레이스 1순위 기준(TraceQL duration 표기), 기본 1s
#   TRACE_SERVICES 서비스별 분포를 뽑을 service.name 목록(공백 구분)
#
# 이 스크립트는 조회만 한다. kubectl 변경 명령(apply/patch/scale/delete/exec)은 없다.
# ============================================================================
set -euo pipefail

usage() {
  cat <<'EOF'
사용법: metrics-snapshot.sh <round-name> [interval_sec] [duration_sec]

  round-name    결과가 쌓이는 라운드 이름 (loadtest/results/<round-name>/)
  interval_sec  폴링 간격, 기본 15
  duration_sec  총 수집 기간. 생략하면 Ctrl+C까지 무한

환경변수: PROM_URL PROM_NS PROM_SVC PROM_PORT LOCAL_PORT RESULTS_DIR RATE_WINDOW
          TRACES TEMPO_URL TEMPO_NS TEMPO_SVC TEMPO_PORT TEMPO_LOCAL_PORT
          TRACE_TOPN TRACE_LIMIT TRACE_ERR_LIMIT TRACE_TIMEOUT TRACE_SLOW_MIN TRACE_SERVICES
          (자세한 설명은 스크립트 상단 주석)

semi 노드 안에서 실행한다. 로컬 kubectl로는 클러스터에 닿지 않는다.

예:
  ./metrics-snapshot.sh ramp 15          # 15초 간격, Ctrl+C까지
  ./metrics-snapshot.sh spike 10 900     # 10초 간격, 15분 후 자동 종료
EOF
}

die() { echo "치명: $*" >&2; exit 1; }
warn() { echo "경고: $*" >&2; }

# ---------------------------------------------------------------- 인자 검사
[ $# -ge 1 ] || { usage >&2; die "라운드 이름이 없다."; }
case "${1:-}" in -h|--help|help) usage; exit 0;; esac

ROUND="$1"
INTERVAL="${2:-15}"
DURATION="${3:-}"

case "$ROUND" in
  ''|*[!A-Za-z0-9._-]*) die "라운드 이름은 영숫자·. _ - 만 쓴다: '$ROUND'";;
esac
case "$INTERVAL" in ''|*[!0-9]*) die "interval_sec은 정수여야 한다: '$INTERVAL'";; esac
[ "$INTERVAL" -ge 1 ] || die "interval_sec은 1 이상이어야 한다: '$INTERVAL'"
if [ -n "$DURATION" ]; then
  case "$DURATION" in ''|*[!0-9]*) die "duration_sec은 정수여야 한다: '$DURATION'";; esac
  [ "$DURATION" -ge 1 ] || die "duration_sec은 1 이상이어야 한다: '$DURATION'"
fi

# ---------------------------------------------------------------- 의존 명령
for c in kubectl curl awk date jq; do
  command -v "$c" >/dev/null 2>&1 || die "필수 명령 '$c' 가 없다. (필요: kubectl curl awk date jq)"
done

# pg-mock-*.sh 와 동일 규약 — SSM 기본 계정(ssm-user)이나 root로 돌아도 kubectl이 붙게.
# kubeconfig는 mode 0644라 아무 계정에서나 읽힌다(terraform/user_data.sh.tpl).
export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"
kubectl get ns "${PROM_NS:-observability}" >/dev/null 2>&1 \
  || die "네임스페이스 ${PROM_NS:-observability} 를 볼 수 없다. KUBECONFIG=$KUBECONFIG 확인."

# ---------------------------------------------------------------- 출력 경로
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_ROOT="${RESULTS_DIR:-$SCRIPT_DIR/../results}"
OUT_DIR="$OUT_ROOT/$ROUND"
mkdir -p "$OUT_DIR" || die "출력 디렉토리를 만들 수 없다: $OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

NODE_NAME="$(hostname)"
METRICS_CSV="$OUT_DIR/metrics.csv"
NODE_CSV="$OUT_DIR/node-$NODE_NAME.csv"
SUMMARY="$OUT_DIR/summary.txt"

CSV_HEADER='ts,metric,labels,value'
[ -s "$METRICS_CSV" ] || printf '%s\n' "$CSV_HEADER" > "$METRICS_CSV"
[ -s "$NODE_CSV" ]    || printf '%s\n' "$CSV_HEADER" > "$NODE_CSV"

RATE_WINDOW="${RATE_WINDOW:-2m}"
PROM_NS="${PROM_NS:-observability}"
PROM_SVC="${PROM_SVC:-prometheus}"
LOCAL_PORT="${LOCAL_PORT:-19090}"

PF_PID=""
PF_LOG="$OUT_DIR/.port-forward.log"
USE_PF=0

# --------------------------------------------------------- 트레이스 설정값
TRACES="${TRACES:-1}"
TEMPO_NS="${TEMPO_NS:-observability}"
TEMPO_SVC="${TEMPO_SVC:-tempo}"
TEMPO_PORT="${TEMPO_PORT:-}"
TEMPO_LOCAL_PORT="${TEMPO_LOCAL_PORT:-13200}"
TEMPO_URL="${TEMPO_URL:-}"
TRACE_TOPN="${TRACE_TOPN:-20}"
TRACE_LIMIT="${TRACE_LIMIT:-100}"
TRACE_ERR_LIMIT="${TRACE_ERR_LIMIT:-200}"
TRACE_TIMEOUT="${TRACE_TIMEOUT:-60}"
TRACE_SLOW_MIN="${TRACE_SLOW_MIN:-1s}"
TRACE_SERVICES="${TRACE_SERVICES:-apigateway member-service product-service order-service payment-service settlement-service search-service queue-service ai-service}"

for v in TRACE_TOPN TRACE_LIMIT TRACE_ERR_LIMIT TRACE_TIMEOUT TEMPO_LOCAL_PORT; do
  eval "vv=\${$v}"
  case "$vv" in ''|*[!0-9]*) die "$v 은 정수여야 한다: '$vv'";; esac
  [ "$vv" -ge 1 ] || die "$v 은 1 이상이어야 한다: '$vv'"
done
[ "$TEMPO_LOCAL_PORT" != "$LOCAL_PORT" ] \
  || die "TEMPO_LOCAL_PORT($TEMPO_LOCAL_PORT)가 LOCAL_PORT와 같다. 두 port-forward가 동시에 뜨므로 달라야 한다."

TRACES_TXT="$OUT_DIR/traces.txt"
TRACE_TMP="$OUT_DIR/.tempo-response.json"
TEMPO_PF_LOG="$OUT_DIR/.tempo-port-forward.log"
TEMPO_PF_PID=""
TEMPO_LINK=""
TEMPO_ERR=""
TRACE_START=""
TRACE_END=""

# ---------------------------------------------------------------- 정리·요약
FINISHED=0
cleanup() {
  local rc=$?
  if [ -n "$PF_PID" ]; then kill "$PF_PID" 2>/dev/null || true; wait "$PF_PID" 2>/dev/null || true; fi
  if [ "$FINISHED" = "1" ]; then
    # 트레이스 질의는 여기서만 돈다 — 수집 창이 닫힌 뒤라 측정에 섞이지 않는다.
    collect_traces || warn "트레이스 요약 생성 실패"
    write_summary || warn "요약 생성 실패"
  fi
  if [ -n "$TEMPO_PF_PID" ]; then kill "$TEMPO_PF_PID" 2>/dev/null || true; wait "$TEMPO_PF_PID" 2>/dev/null || true; fi
  exit "$rc"
}
STOP=0
on_signal() { STOP=1; echo "" >&2; echo "중단 신호 수신 — 요약을 쓰고 종료한다." >&2; }
trap cleanup EXIT
trap on_signal INT TERM

now_iso() { date +%Y-%m-%dT%H:%M:%S%z; }

# CSV 한 줄 기록. 라벨·값에서 콤마·따옴표를 제거해 4열 구조를 지킨다.
emit() {
  local file="$1" metric="$2" labels="$3" value="$4"
  labels="$(printf '%s' "$labels" | tr ',"' ';_')"
  value="$(printf '%s' "$value" | tr -d ',"')"
  printf '%s,%s,%s,%s\n' "$(now_iso)" "$metric" "${labels:--}" "$value" >> "$file"
}

emit_error() {
  local metric="$1" reason="$2"
  emit "$METRICS_CSV" "$metric" "reason=$reason" "ERROR"
  warn "[$metric] $reason"
}

# ---------------------------------------------------------- Prometheus 탐색
prom_ready() {
  curl -sf --max-time 8 -o /dev/null "$1/-/ready" 2>/dev/null
}

start_port_forward() {
  kubectl -n "$PROM_NS" port-forward "svc/$PROM_SVC" "$LOCAL_PORT:$PROM_PORT" \
    >"$PF_LOG" 2>&1 &
  PF_PID=$!
  local i
  for i in $(seq 1 20); do
    sleep 1
    if ! kill -0 "$PF_PID" 2>/dev/null; then
      PF_PID=""
      return 1
    fi
    if prom_ready "http://127.0.0.1:$LOCAL_PORT"; then return 0; fi
  done
  kill "$PF_PID" 2>/dev/null || true
  PF_PID=""
  return 1
}

discover_prom() {
  if [ -n "${PROM_URL:-}" ]; then
    prom_ready "$PROM_URL" || die "PROM_URL='$PROM_URL' 이 /-/ready 에 응답하지 않는다."
    echo "Prometheus: $PROM_URL (PROM_URL 지정)" >&2
    return 0
  fi

  # 매니페스트가 아니라 살아 있는 Service에서 실측한다.
  local cip port
  cip="$(kubectl -n "$PROM_NS" get svc "$PROM_SVC" -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)"
  port="${PROM_PORT:-$(kubectl -n "$PROM_NS" get svc "$PROM_SVC" -o jsonpath='{.spec.ports[0].port}' 2>/dev/null || true)}"
  [ -n "$cip" ]  || die "Prometheus ClusterIP를 못 읽었다 (ns=$PROM_NS svc=$PROM_SVC). kubectl 권한·KUBECONFIG를 확인하라."
  [ -n "$port" ] || die "Prometheus 포트를 못 읽었다 (ns=$PROM_NS svc=$PROM_SVC)."
  PROM_PORT="$port"

  # 1순위: ClusterIP 직접 — NetworkPolicy가 없는 환경이면 가장 싸다.
  if prom_ready "http://$cip:$PROM_PORT"; then
    PROM_URL="http://$cip:$PROM_PORT"
    echo "Prometheus: $PROM_URL (ClusterIP 직결)" >&2
    return 0
  fi

  # 2순위: port-forward — observability default-deny NetworkPolicy 우회 경로.
  echo "ClusterIP $cip:$PROM_PORT 직결 실패(NetworkPolicy 추정) — port-forward로 전환한다." >&2
  if start_port_forward; then
    USE_PF=1
    PROM_URL="http://127.0.0.1:$LOCAL_PORT"
    echo "Prometheus: $PROM_URL (port-forward -> $PROM_SVC:$PROM_PORT)" >&2
    return 0
  fi

  die "Prometheus에 닿을 수 없다. ClusterIP($cip:$PROM_PORT) 직결도, port-forward도 실패했다. port-forward 로그: $(cat "$PF_LOG" 2>/dev/null | tr '\n' ' ')"
}

ensure_link() {
  [ "$USE_PF" = "1" ] || return 0
  if [ -n "$PF_PID" ] && kill -0 "$PF_PID" 2>/dev/null; then return 0; fi
  emit_error "_collector" "port-forward-died"
  PF_PID=""
  if start_port_forward; then
    warn "port-forward 재기동 성공"
  else
    emit_error "_collector" "port-forward-restart-failed"
  fi
}

# ------------------------------------------------------------- 수집 대상 정의
# 각 항목: <메트릭이름>|<PromQL>
# 라벨 규약은 실측 확인분이다 — 서비스 구분은 `app`(otel relabel이 pod label app에서
# 부착), 파드 구분은 `pod`, 드롭 구분은 `dropId`. `service` 라벨은 존재하지 않는다.
# rate 윈도는 $RATE_WINDOW로 치환된다.
QUERIES=(
  # --- Hikari 커넥션풀 (app: ai member order payment product search settlement — queue는 DB 없음)
  "hikari_active|sum by (app) (hikaricp_connections_active)"
  "hikari_pending|sum by (app) (hikaricp_connections_pending)"
  "hikari_idle|sum by (app) (hikaricp_connections_idle)"
  "hikari_max|sum by (app) (hikaricp_connections_max)"
  "hikari_timeout_rate|sum by (app) (rate(hikaricp_connections_timeout_total[__W__]))"
  "hikari_timeout_cum|sum by (app) (hikaricp_connections_timeout_total)"

  # --- Hikari 커넥션 점유·획득 시간 (Micrometer HikariCP 바인더의 Timer 3종)
  # 풀 포화의 원인이 "요청이 느려서"인지 "배경 작업이 커넥션을 오래 쥐어서"인지는
  # active 게이지만으로는 못 가른다. 점유 시간(usage)을 직접 재서 리틀의 법칙 역산이 아닌
  # 실측으로 판단한다. usage=반납까지 쥐고 있던 시간, acquire=풀에서 받기까지 기다린 시간,
  # creation=신규 물리 커넥션 생성 시간. 단위는 전부 초.
  # 평균은 구간 증가분의 비율(sum/count)로 뽑는다. 전 구간 평균은 아래 *_cum 두 행의
  # summary DELTA로 다시 계산할 수 있다(윈도 선택과 무관한 값이 필요할 때).
  "hikari_usage_avg|sum by (app) (rate(hikaricp_connections_usage_seconds_sum[__W__])) / sum by (app) (rate(hikaricp_connections_usage_seconds_count[__W__]))"
  "hikari_usage_max_recent|max by (app) (hikaricp_connections_usage_seconds_max)"
  # 반납 횟수 rate × 평균 점유 시간 = 평균 동시 점유 커넥션 수(리틀의 법칙).
  # 이 곱이 hikari_active보다 뚜렷이 작으면 나머지는 요청 경로가 아닌 상주 점유자(스케줄러) 몫이다.
  "hikari_usage_rate|sum by (app) (rate(hikaricp_connections_usage_seconds_count[__W__]))"
  "hikari_usage_sum_cum|sum by (app) (hikaricp_connections_usage_seconds_sum)"
  "hikari_usage_count_cum|sum by (app) (hikaricp_connections_usage_seconds_count)"
  "hikari_acquire_avg|sum by (app) (rate(hikaricp_connections_acquire_seconds_sum[__W__])) / sum by (app) (rate(hikaricp_connections_acquire_seconds_count[__W__]))"
  "hikari_acquire_max_recent|max by (app) (hikaricp_connections_acquire_seconds_max)"
  "hikari_acquire_sum_cum|sum by (app) (hikaricp_connections_acquire_seconds_sum)"
  "hikari_acquire_count_cum|sum by (app) (hikaricp_connections_acquire_seconds_count)"
  "hikari_creation_avg|sum by (app) (rate(hikaricp_connections_creation_seconds_sum[__W__])) / sum by (app) (rate(hikaricp_connections_creation_seconds_count[__W__]))"
  "hikari_creation_max_recent|max by (app) (hikaricp_connections_creation_seconds_max)"
  # 진단 — 위 이름들이 실제로 노출되는지 확인용. 바인더 구성에 따라 Timer 3종이 없을 수 있어
  # 이름 자체를 metric_name 라벨로 꺼내 CSV에 남긴다(값은 그 이름의 시리즈 수).
  # 전부 부재면 이 행도 empty-result로 떨어지므로 스크립트는 죽지 않는다.
  'hikari_meter_names|count by (metric_name) (label_replace({__name__=~"hikaricp_connections_.*"}, "metric_name", "$1", "__name__", "(.*)"))'

  # --- 톰캣 스레드 (queue 포함)
  "tomcat_busy_threads|sum by (app) (tomcat_threads_busy_threads)"
  "tomcat_max_threads|sum by (app) (tomcat_threads_config_max_threads)"

  # --- HTTP 지연 백분위 (버킷 보유 서비스는 order·payment·queue 뿐 — 나머진 histogram 미설정)
  "http_p95|histogram_quantile(0.95, sum by (app, le) (rate(http_server_requests_seconds_bucket{app=~\"order|payment|queue\",uri!~\"/actuator.*\"}[__W__])))"
  "http_p99|histogram_quantile(0.99, sum by (app, le) (rate(http_server_requests_seconds_bucket{app=~\"order|payment|queue\",uri!~\"/actuator.*\"}[__W__])))"
  "http_max_recent|max by (app) (http_server_requests_seconds_max{app=~\"order|payment|queue\",uri!~\"/actuator.*\"})"

  # --- payment PG 호출 지연
  # 버킷은 payment.pg.call percentiles-histogram=true(application.yml)로 켜져 있으나
  # 타이머가 첫 PG 호출에서야 등록된다 — 부하 전에는 빈 결과(ERROR 행)가 정상이다.
  # 버킷이 안 뜨는 경우에도 판단할 수 있게 평균·max를 같이 받는다.
  "pg_call_p95|histogram_quantile(0.95, sum by (app, le) (rate(payment_pg_call_seconds_bucket{app=\"payment\"}[__W__])))"
  "pg_call_avg|sum by (app) (rate(payment_pg_call_seconds_sum{app=\"payment\"}[__W__])) / sum by (app) (rate(payment_pg_call_seconds_count{app=\"payment\"}[__W__]))"
  "pg_call_max_recent|max by (app) (payment_pg_call_seconds_max{app=\"payment\"})"
  "pg_call_rate|sum by (app, outcome) (rate(payment_pg_call_seconds_count{app=\"payment\"}[__W__]))"

  # --- 5xx: 순간 비율 + 누적(구간 델타는 summary가 뽑는다)
  "http_5xx_rate|sum by (app) (rate(http_server_requests_seconds_count{status=~\"5..\",uri!~\"/actuator.*\"}[__W__]))"
  "http_5xx_cum|sum by (app) (http_server_requests_seconds_count{status=~\"5..\",uri!~\"/actuator.*\"})"
  "http_req_rate|sum by (app) (rate(http_server_requests_seconds_count{uri!~\"/actuator.*\"}[__W__]))"
  # actuator 503은 앱 5xx가 아니라 readiness 흔들림(=곧 재시작)의 신호라 따로 센다.
  "health_503_cum|sum by (app) (http_server_requests_seconds_count{status=\"503\",uri=~\"/actuator.*\"})"

  # --- CPU: 스로틀링 비율 + 실사용
  # 주의(실측) — openat 파드에는 CPU limit이 하나도 없다. cAdvisor는 CPU quota가 있는
  # 컨테이너에만 cfs_throttled를 내므로 이 행은 사실상 otel-collector만 잡힌다.
  # 앱 파드 포화는 아래 cpu_usage_cores(+ node-*.csv의 PSI)로 본다.
  "cpu_throttle_ratio|sum by (namespace, pod) (rate(container_cpu_cfs_throttled_periods_total{container!=\"\"}[__W__])) / sum by (namespace, pod) (rate(container_cpu_cfs_periods_total{container!=\"\"}[__W__])) > 0"
  "cpu_usage_cores|sum by (pod) (rate(container_cpu_usage_seconds_total{namespace=\"openat\",container!=\"\"}[__W__]))"

  # --- 힙 사용률
  "heap_used_ratio|sum by (app) (jvm_memory_used_bytes{area=\"heap\"}) / sum by (app) (jvm_memory_max_bytes{area=\"heap\"})"
  "heap_used_bytes|sum by (app) (jvm_memory_used_bytes{area=\"heap\"})"

  # --- Redis
  # OOM 쓰기 거부 전용 카운터는 redis_exporter에 없다(redis_errors_total의 err 라벨은
  # 실측 'ERR' 하나뿐). maxmemory 대비 사용률 + 축출 + 거부 연결로 대체 관측한다.
  "redis_mem_used_bytes|max(redis_memory_used_bytes)"
  "redis_mem_maxmemory_bytes|max(redis_config_maxmemory)"
  "redis_mem_ratio|max(redis_memory_used_bytes) / max(redis_config_maxmemory)"
  "redis_evicted_keys_cum|max(redis_evicted_keys_total)"
  "redis_errors_cum|sum by (err) (redis_errors_total)"
  "redis_rejected_conn_cum|max(redis_rejected_connections_total)"

  # --- postgres
  "pg_active_conn|sum(pg_stat_activity_count{state=\"active\"})"
  "pg_conn_by_state|sum by (state) (pg_stat_activity_count)"

  # --- 아웃박스 적체
  # payment만 PENDING 게이지를 등록한다(payment/.../outbox/OutboxPublisher.java 생성자의
  # Gauge.builder("payment.outbox.pending", ...)). order는 발행 카운터
  # order.outbox.published 하나뿐이라 PENDING 시계열이 없다
  # (order/.../kafka/publisher/OutboxEventPublisher.java) — 이름 패턴으로 함께 걸어 두어
  # order에 게이지가 추가되면 수정 없이 잡히게 한다. member는 계측이 아예 없다.
  # 적체가 늘면서 hikari_usage_avg가 같이 늘면 스케줄러가 커넥션을 문 채 기다린다는 뜻이다.
  "outbox_pending|sum by (app) ({__name__=~\"(order|payment)_outbox_pending\"})"
  # 게이지 기울기(건/초). 양수면 발행이 유입을 못 따라가고 적체가 쌓이는 중.
  "outbox_pending_slope|sum by (app) (deriv({__name__=~\"(order|payment)_outbox_pending\"}[__W__]))"
  "outbox_published_rate|sum by (app) (rate({__name__=~\"(order|payment)_outbox_published_total\"}[__W__]))"
  "outbox_published_cum|sum by (app) ({__name__=~\"(order|payment)_outbox_published_total\"})"

  # --- Kafka 프로듀서 (spring-boot의 Kafka 클라이언트 계측이 붙어 있을 때만 존재)
  # 아웃박스 발행이 kafkaTemplate.send(...).get() 동기 대기라, 여기 지연이 그대로
  # 트랜잭션 점유 시간이 된다. request_latency는 Kafka 클라이언트가 내는 값이라 단위가 ms다
  # (초인 hikari_* 와 섞어 읽지 말 것). 계측이 없으면 이 행들은 empty-result로 남는다.
  "kafka_req_latency_avg|avg by (app) (kafka_producer_request_latency_avg)"
  "kafka_req_latency_max|max by (app) (kafka_producer_request_latency_max)"
  "kafka_in_flight|sum by (app) (kafka_producer_requests_in_flight)"
  "kafka_send_rate|sum by (app) (rate(kafka_producer_record_send_total[__W__]))"
  # 진단 — 위 이름을 코드가 아니라 클라이언트 계측 규약에서 추정했으므로, 실제 노출 이름을
  # 남겨 다음 라운드에서 쿼리를 고칠 수 있게 한다. 관심 계열만 걸어 행 폭증을 막는다.
  'kafka_meter_names|count by (metric_name) (label_replace({__name__=~"kafka_producer_.*(latency|in_flight|record_send|record_error|io_wait).*"}, "metric_name", "$1", "__name__", "(.*)"))'

  # --- 대기열 / 재고
  # queue_admission_*는 AdmissionScheduler가 처음 입장을 처리할 때 lazily 등록된다
  # (queue/.../schedule/AdmissionScheduler.kt) — 부하 전엔 부재가 정상.
  "queue_waiting_size|sum by (dropId) (queue_waiting_size)"
  "queue_outstanding|sum by (dropId) (queue_outstanding)"
  "queue_admission_rate|sum by (dropId) (rate(queue_admission_count_total[__W__]))"
  "queue_admission_qty_rate|sum by (dropId) (rate(queue_admission_quantity_total[__W__]))"
  "queue_stock_remaining|sum by (dropId) (queue_stock_remaining)"
  "product_drop_stock|sum by (dropId) (product_drop_stock)"
)

# ------------------------------------------------------------- 쿼리 1건 실행
# 표시용 라벨에서 스크레이프 잡음(__name__/instance/job/otel_scope_*/application)은 뺀다.
# 라벨이 하나도 없는 집계(예: sum(pg_stat_activity_count{...}))는 "-"로 채운다.
# 빈 문자열을 그대로 내면 탭이 IFS 공백이라 첫 필드가 통째로 접혀 행이 사라진다.
JQ_ROWS='
  .data.result[]
  | [ ( .metric
        | to_entries
        | map(select(.key | test("^(__name__|instance|job|application|otel_scope_name|otel_scope_version)$") | not))
        | map("\(.key)=\(.value)")
        | join(";")
        | if . == "" then "-" else . end ),
      (.value[1] | tostring) ]
  | @tsv
'

query_one() {
  local name="$1" promql="$2" body http rows
  promql="${promql//__W__/$RATE_WINDOW}"

  # -sS: 조용하되 실패는 stderr로 뱉는다(로그에 원인이 남게).
  body="$(curl -sS -G --max-time 15 \
            --data-urlencode "query=$promql" \
            -w '\n__HTTP__%{http_code}' \
            "$PROM_URL/api/v1/query")" || {
    emit_error "$name" "curl-failed"; return 0; }

  http="${body##*__HTTP__}"
  body="${body%$'\n'__HTTP__*}"

  if [ "$http" != "200" ]; then
    emit_error "$name" "http-$http"
    return 0
  fi
  if [ "$(printf '%s' "$body" | jq -r '.status' 2>/dev/null)" != "success" ]; then
    emit_error "$name" "prom-error:$(printf '%s' "$body" | jq -r '.error // "unparseable"' 2>/dev/null | tr -d ',\n' | cut -c1-120)"
    return 0
  fi

  rows="$(printf '%s' "$body" | jq -r "$JQ_ROWS" 2>/dev/null)" || {
    emit_error "$name" "jq-parse-failed"; return 0; }

  if [ -z "$rows" ]; then
    # 시리즈 부재 또는 무표본. 조용히 넘기지 않는다.
    emit_error "$name" "empty-result"
    return 0
  fi

  local lbl val
  while IFS=$'\t' read -r lbl val; do
    [ -n "$val" ] || continue
    case "$val" in
      NaN|nan|+Inf|-Inf|Inf) warn "[$name] 값이 $val (라벨: ${lbl:--})";;
    esac
    emit "$METRICS_CSV" "$name" "$lbl" "$val"
  done <<< "$rows"
}

# ------------------------------------------------------------- 노드 직접 샘플링
# node-exporter가 미배포라 노드 메모리·CPU는 여기서 직접 읽는다.
sample_node() {
  local n="node=$NODE_NAME"

  if ! free -m >/dev/null 2>&1; then
    emit "$NODE_CSV" "node_mem" "$n;reason=free-unavailable" "ERROR"
    warn "free -m 실행 실패"
  else
    free -m | awk -v n="$n" -v ts="$(now_iso)" '
      $1=="Mem:"  { printf "%s,node_mem_total_mb,%s,%s\n",     ts, n, $2
                    printf "%s,node_mem_used_mb,%s,%s\n",      ts, n, $3
                    printf "%s,node_mem_free_mb,%s,%s\n",      ts, n, $4
                    printf "%s,node_mem_buffcache_mb,%s,%s\n", ts, n, $6
                    printf "%s,node_mem_available_mb,%s,%s\n", ts, n, $7 }
      $1=="Swap:" { printf "%s,node_swap_total_mb,%s,%s\n",     ts, n, $2
                    printf "%s,node_swap_used_mb,%s,%s\n",      ts, n, $3 }
    ' >> "$NODE_CSV"
  fi

  if [ -r /proc/loadavg ]; then
    awk -v n="$n" -v ts="$(now_iso)" '
      { split($4, p, "/")
        printf "%s,node_load1,%s,%s\n",         ts, n, $1
        printf "%s,node_load5,%s,%s\n",         ts, n, $2
        printf "%s,node_load15,%s,%s\n",        ts, n, $3
        printf "%s,node_procs_running,%s,%s\n", ts, n, p[1]
        printf "%s,node_procs_total,%s,%s\n",   ts, n, p[2] }
    ' /proc/loadavg >> "$NODE_CSV"
  else
    emit "$NODE_CSV" "node_load1" "$n;reason=no-proc-loadavg" "ERROR"
    warn "/proc/loadavg 를 읽을 수 없다"
  fi

  # PSI(/proc/pressure/*)는 커널·cgroup v2 구성에 따라 없을 수 있다 — 있으면 기록.
  local res
  for res in cpu memory io; do
    if [ -r "/proc/pressure/$res" ]; then
      awk -v n="$n" -v ts="$(now_iso)" -v r="$res" '
        { kind = $1                     # some / full
          for (i = 2; i <= NF; i++) {
            split($i, kv, "=")
            if (kv[1] == "avg10" || kv[1] == "total")
              printf "%s,node_psi_%s_%s_%s,%s,%s\n", ts, r, kind, kv[1], n, kv[2]
          } }
      ' "/proc/pressure/$res" >> "$NODE_CSV"
    fi
  done
}

# =========================================================== 트레이스 (Tempo)
# 라운드가 끝난 뒤 **1회만** 질의한다. Tempo 검색은 블록 스캔이라 비싸서, 폴링에 섞으면
# 측정 대상 노드를 흔들어 지표 자체를 오염시킨다.
#
# 아래는 전부 실측 확인분이다(2026-07-27, 돌고 있는 클러스터에 직접 질의). 추측 아님:
#   * 이미지 grafana/tempo:2.10.7 (k8s/observability/10-tempo.yaml). 매니페스트만이 아니라
#     /api/status/buildinfo 가 {"version":"v2.10.7","revision":"8100f5a7b"} 를 돌려주는 것까지
#     확인해 실행 중인 바이너리와 일치함을 봤다.
#   * GET /api/search?q=<TraceQL>&start=<unix초>&end=<unix초>&limit=N → 200.
#     응답의 trace 원소에 traceID · rootServiceName · rootTraceName · durationMs 와
#     serviceStats{"<svc>":{spanCount,errorCount}} 가 들어 있다. 이 serviceStats 덕에
#     "에러 스팬을 낸 서비스"를 서비스마다 따로 질의하지 않고 한 번에 집계할 수 있다.
#     주의: durationMs·errorCount 는 0일 때 응답에서 아예 빠진다(omitempty) — jq에서 // 0 필수.
#   * TraceQL 메트릭(/api/metrics/query_range)은 **못 쓴다**. 실측 500 +
#     "error finding generators: empty ring" — config/tempo.yaml 에 metrics_generator
#     (local-blocks)가 없기 때문이다. 그래서 정확한 개수 집계는 불가능하고, 아래 표의 수치는
#     전부 limit까지 받아온 **표본**이다(N == limit 이면 상한에 걸린 것 = 실제는 더 많다).
#   * 스팬 속성은 OTel 자동계측 규약이 아니라 Micrometer 계열이다. 실측 키:
#     http.uri · uri · method · http.method · http.status_code · outcome · peer.service.
#     **http.route / url.path / http.target 은 존재하지 않는다**(셋 다 질의해 0건 확인).
#     반면 스팬 이름은 "http post /api/v1/payments/confirm" 형태로 남는다. 그래서 결제 확인
#     경로는 이름 매칭을 1순위로 두고, 속성 기반 필터는 폴백으로만 붙였다(계측이 OTel 규약으로
#     바뀌어도 스크립트를 안 고치게).
#
# 따옴표 처리: TraceQL에는 { } = " 와 공백이 들어간다. 셸에서는 작은따옴표로 감싸 큰따옴표를
# 그대로 넘기고(변수 끼우는 곳만 printf로 조립), URL 인코딩은 Prometheus 쪽과 같은 방식으로
# curl -G --data-urlencode 에 맡긴다(-G 없이 --data-urlencode 를 쓰면 POST가 된다).

fmt_ts() { date -d "@$1" +%Y-%m-%dT%H:%M:%S%z 2>/dev/null || printf '%s' "$1"; }

tempo_ready() { curl -sf --max-time 8 -o /dev/null "$1/ready" 2>/dev/null; }

start_tempo_port_forward() {
  kubectl -n "$TEMPO_NS" port-forward "svc/$TEMPO_SVC" "$TEMPO_LOCAL_PORT:$TEMPO_PORT" \
    >"$TEMPO_PF_LOG" 2>&1 &
  TEMPO_PF_PID=$!
  local i
  for i in $(seq 1 20); do
    sleep 1
    if ! kill -0 "$TEMPO_PF_PID" 2>/dev/null; then
      TEMPO_PF_PID=""
      return 1
    fi
    if tempo_ready "http://127.0.0.1:$TEMPO_LOCAL_PORT"; then return 0; fi
  done
  kill "$TEMPO_PF_PID" 2>/dev/null || true
  TEMPO_PF_PID=""
  return 1
}

# Prometheus와 같은 순서 — ClusterIP 직결 먼저, 막히면(observability default-deny)
# port-forward. 여기서 실패해도 die 하지 않는다. 이 함수는 종료 경로에서 불리므로
# 죽으면 summary.txt까지 날아간다.
discover_tempo() {
  TEMPO_ERR=""
  if [ -n "$TEMPO_URL" ]; then
    if tempo_ready "$TEMPO_URL"; then TEMPO_LINK="TEMPO_URL 지정"; return 0; fi
    TEMPO_ERR="TEMPO_URL='$TEMPO_URL' 이 /ready 에 응답하지 않는다."
    return 1
  fi

  local cip port
  cip="$(kubectl -n "$TEMPO_NS" get svc "$TEMPO_SVC" -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)"
  port="$TEMPO_PORT"
  # 포트가 3개(3200/4318/4317)라 [0] 대신 name=http 로 집는다. 이름이 없으면 첫 포트로 폴백.
  [ -n "$port" ] || port="$(kubectl -n "$TEMPO_NS" get svc "$TEMPO_SVC" -o jsonpath='{.spec.ports[?(@.name=="http")].port}' 2>/dev/null || true)"
  [ -n "$port" ] || port="$(kubectl -n "$TEMPO_NS" get svc "$TEMPO_SVC" -o jsonpath='{.spec.ports[0].port}' 2>/dev/null || true)"
  if [ -z "$cip" ] || [ -z "$port" ]; then
    TEMPO_ERR="Tempo Service를 못 읽었다 (ns=$TEMPO_NS svc=$TEMPO_SVC, clusterIP='$cip' port='$port')."
    return 1
  fi
  TEMPO_PORT="$port"

  if tempo_ready "http://$cip:$TEMPO_PORT"; then
    TEMPO_URL="http://$cip:$TEMPO_PORT"
    TEMPO_LINK="ClusterIP 직결"
    return 0
  fi
  if start_tempo_port_forward; then
    TEMPO_URL="http://127.0.0.1:$TEMPO_LOCAL_PORT"
    TEMPO_LINK="port-forward -> $TEMPO_SVC:$TEMPO_PORT"
    return 0
  fi
  TEMPO_ERR="Tempo에 닿을 수 없다. ClusterIP($cip:$TEMPO_PORT) 직결도 port-forward도 실패했다. port-forward 로그: $(tr '\n' ' ' < "$TEMPO_PF_LOG" 2>/dev/null | cut -c1-200)"
  return 1
}

# 검색 1건. 성공하면 응답 본문이 $TRACE_TMP 에 남고 0을 돌려준다.
# 실패는 $TEMPO_ERR 에 사유를 담고 1. 명령치환($(...)) 안에서 부르면 TEMPO_ERR가
# 서브셸에 갇히므로, 본문은 변수가 아니라 파일로 넘긴다.
tempo_search() {
  local label="$1" traceql="$2" limit="$3" http
  TEMPO_ERR=""
  : > "$TRACE_TMP"
  http="$(curl -sS -G --max-time "$TRACE_TIMEOUT" \
            --data-urlencode "q=$traceql" \
            --data-urlencode "start=$TRACE_START" \
            --data-urlencode "end=$TRACE_END" \
            --data-urlencode "limit=$limit" \
            -o "$TRACE_TMP" -w '%{http_code}' \
            "$TEMPO_URL/api/search")" || {
    TEMPO_ERR="curl 실패 [$label] TraceQL=$traceql"; return 1; }
  if [ "$http" != "200" ]; then
    TEMPO_ERR="HTTP $http [$label] TraceQL=$traceql 응답=$(tr -d '\n' < "$TRACE_TMP" 2>/dev/null | cut -c1-200)"
    return 1
  fi
  # jq에는 파일 인자 대신 리다이렉트로 먹인다 — 파일 접근이 제한된 jq 빌드(snap 등)에서도
  # 셸이 연 fd라 항상 읽힌다. 이 스크립트의 Prometheus 쪽도 전부 파이프로 먹인다.
  if ! jq -e 'type == "object"' < "$TRACE_TMP" >/dev/null 2>&1; then
    TEMPO_ERR="응답 JSON 파싱 실패 [$label] TraceQL=$traceql 응답=$(tr -d '\n' < "$TRACE_TMP" 2>/dev/null | cut -c1-200)"
    return 1
  fi
  return 0
}

trace_count() { jq -r '(.traces // []) | length' < "$TRACE_TMP" 2>/dev/null || echo 0; }

# 검색이 상한에 걸려 조기 종료됐는지 드러낸다(completedJobs < totalJobs = 부분 결과).
JQ_TSTATS='"검사 " + ((.metrics.inspectedBytes // "0")|tostring) + "B, 잡 "
           + ((.metrics.completedJobs // 0)|tostring) + "/" + ((.metrics.totalJobs // 0)|tostring)'

# 에러 스팬을 낸 서비스별 집계 — serviceStats에서 바로 뽑는다.
JQ_ERR_BY_SVC='
  [ .traces[]? | (.serviceStats // {}) | to_entries[]
    | select((.value.errorCount // 0) > 0)
    | { svc: .key, err: (.value.errorCount // 0) } ]
  | group_by(.svc)
  | map({ svc: .[0].svc, traces: length, errs: (map(.err) | add) })
  | sort_by(-.errs)[]
  | [ .svc, (.errs|tostring), (.traces|tostring) ] | @tsv
'

# 느린 순 상위 $n. traceID를 먼저 놓는다 — 사람이 Grafana에 그대로 붙여넣게.
JQ_TOP_SLOW='
  [ .traces[]? | { id: .traceID,
                   svc: (.rootServiceName // "-"),
                   name: (.rootTraceName // "-"),
                   ms: (.durationMs // 0) } ]
  | sort_by(-.ms) | .[0:$n] | .[]
  | [ .id, .svc, (.ms|tostring), .name ] | @tsv
'

# 소요시간 분포(ms) — N/min/p50/p90/max. Tempo가 백분위를 안 주므로 표본에서 직접 센다.
JQ_DUR_DIST='
  [ .traces[]? | (.durationMs // 0) ] | sort as $s
  | ($s | length) as $n
  | if $n == 0 then [ 0, "-", "-", "-", "-" ]
    else [ $n,
           $s[0],
           $s[(($n * 0.5) | floor)],
           $s[ (if ((($n * 0.9) | floor) >= $n) then ($n - 1) else (($n * 0.9) | floor) end) ],
           $s[$n - 1] ]
    end
  | @tsv
'

collect_traces() {
  if [ "$TRACES" = "0" ]; then
    echo "TRACES=0 — 트레이스 질의를 건너뛴다." >&2
    return 0
  fi

  # 수집 창 전체. 앞뒤 60초 여유는 창 경계에 걸친 트레이스를 놓치지 않으려는 것.
  TRACE_START=$(( ${START:-$(date +%s)} - 60 ))
  TRACE_END=$(( $(date +%s) + 60 ))

  local header
  header="$(printf '%s\n' \
    "================================================================" \
    " 트레이스 요약 — 라운드: $ROUND" \
    " 구간: $(fmt_ts "$TRACE_START") ~ $(fmt_ts "$TRACE_END")  (unix $TRACE_START..$TRACE_END)" \
    " Tempo: ${TEMPO_URL:-미확정}${TEMPO_LINK:+ ($TEMPO_LINK)}" \
    " 생성: $(now_iso)" \
    "================================================================")"

  echo "트레이스 질의 시작 — Tempo 탐색 중..." >&2
  if ! discover_tempo; then
    { printf '%s\n' "$header"; echo ""; echo "질의 실패: $TEMPO_ERR"; } > "$TRACES_TXT"
    warn "트레이스: Tempo 접속 실패 — $TEMPO_ERR"
    echo "트레이스: $TRACES_TXT (접속 실패 기록)" >&2
    return 0
  fi
  # 접속 경로가 확정된 뒤라야 헤더에 URL이 찍힌다.
  header="$(printf '%s\n' \
    "================================================================" \
    " 트레이스 요약 — 라운드: $ROUND" \
    " 구간: $(fmt_ts "$TRACE_START") ~ $(fmt_ts "$TRACE_END")  (unix $TRACE_START..$TRACE_END)" \
    " Tempo: $TEMPO_URL ($TEMPO_LINK)" \
    " 표본 상한: 일반 ${TRACE_LIMIT}트레이스 / 에러 ${TRACE_ERR_LIMIT}트레이스" \
    " 생성: $(now_iso)" \
    "================================================================")"
  printf '%s\n' "$header" > "$TRACES_TXT"

  # ---- 1. 에러 스팬을 낸 서비스 ------------------------------------------
  local err_rows="" err_slow="" err_stats="-"
  if tempo_search "errors" '{ status = error }' "$TRACE_ERR_LIMIT"; then
    err_stats="$(jq -r "$JQ_TSTATS" < "$TRACE_TMP" 2>/dev/null | tr -d '\n' || echo '-')"
    err_rows="$(jq -r "$JQ_ERR_BY_SVC" < "$TRACE_TMP" 2>/dev/null || true)"
    err_slow="$(jq -r --argjson n "$TRACE_TOPN" "$JQ_TOP_SLOW" < "$TRACE_TMP" 2>/dev/null || true)"
    {
      echo ""
      echo "[1] 에러 스팬을 낸 서비스    TraceQL: { status = error }    ($err_stats)"
      printf '%-24s %12s %12s\n' SERVICE ERROR_SPANS TRACES
      if [ -n "$err_rows" ]; then
        printf '%s\n' "$err_rows" | awk -F'\t' '{ printf "%-24s %12s %12s\n", $1, $2, $3 }'
      else
        echo "(이 구간에 status=error 트레이스가 잡히지 않았다)"
      fi
      if [ -n "$err_slow" ]; then
        echo ""
        echo "  에러 트레이스 (느린 순 상위 $TRACE_TOPN)"
        printf '  %-34s %-20s %10s  %s\n' TRACE_ID ROOT_SERVICE DUR_MS ROOT_SPAN
        printf '%s\n' "$err_slow" | awk -F'\t' '{ printf "  %-34s %-20s %10s  %s\n", $1, $2, $3, $4 }'
      fi
    } >> "$TRACES_TXT"
  else
    { echo ""; echo "[1] 에러 스팬을 낸 서비스"; echo "질의 실패: $TEMPO_ERR"; } >> "$TRACES_TXT"
    warn "트레이스[에러]: $TEMPO_ERR"
  fi

  # ---- 2. 느린 트레이스 상위 N -------------------------------------------
  # 1순위 기준에서 한 건도 안 잡히면 기준을 낮춰 재시도한다. 전부 실패하면 사유를 남긴다.
  local q slow_rows="" slow_used="" slow_stats="-" slow_err=""
  for q in "{ duration > $TRACE_SLOW_MIN }" '{ duration > 200ms }' '{}'; do
    if tempo_search "slow" "$q" "$TRACE_LIMIT"; then
      slow_used="$q"
      slow_stats="$(jq -r "$JQ_TSTATS" < "$TRACE_TMP" 2>/dev/null | tr -d '\n' || echo '-')"
      slow_rows="$(jq -r --argjson n "$TRACE_TOPN" "$JQ_TOP_SLOW" < "$TRACE_TMP" 2>/dev/null || true)"
      if [ -n "$slow_rows" ]; then break; fi
    else
      slow_err="$TEMPO_ERR"
      warn "트레이스[느린 트레이스]: $TEMPO_ERR"
    fi
  done
  {
    echo ""
    if [ -n "$slow_rows" ]; then
      echo "[2] 느린 트레이스 상위 $TRACE_TOPN    TraceQL: $slow_used    ($slow_stats)"
      printf '%-34s %-20s %10s  %s\n' TRACE_ID ROOT_SERVICE DUR_MS ROOT_SPAN
      printf '%s\n' "$slow_rows" | awk -F'\t' '{ printf "%-34s %-20s %10s  %s\n", $1, $2, $3, $4 }'
    elif [ -n "$slow_used" ]; then
      echo "[2] 느린 트레이스 상위 $TRACE_TOPN    TraceQL: $slow_used"
      echo "(질의는 성공했으나 결과가 0건이다 — 이 구간에 트레이스가 없거나 보존이 지났다)"
    else
      echo "[2] 느린 트레이스 상위 $TRACE_TOPN"
      echo "질의 실패: ${slow_err:-원인 미상}"
    fi
  } >> "$TRACES_TXT"

  # ---- 3. 서비스별 트레이스 표본 분포 -------------------------------------
  {
    echo ""
    echo "[3] 서비스별 트레이스 표본과 소요시간 분포    TraceQL: { resource.service.name = \"<svc>\" }"
    echo "    (durationMs는 그 서비스가 참여한 **트레이스 전체**의 길이다. N이 $TRACE_LIMIT 이면 상한에 걸린 표본이다)"
    printf '%-22s %7s %9s %9s %9s %9s  %s\n' SERVICE N MIN_MS P50_MS P90_MS MAX_MS NOTE
  } >> "$TRACES_TXT"
  local svc dist n note
  for svc in $TRACE_SERVICES; do
    q="$(printf '{ resource.service.name = "%s" }' "$svc")"
    if tempo_search "svc:$svc" "$q" "$TRACE_LIMIT"; then
      dist="$(jq -r "$JQ_DUR_DIST" < "$TRACE_TMP" 2>/dev/null || true)"
      if [ -z "$dist" ]; then
        printf '%-22s %7s %9s %9s %9s %9s  %s\n' "$svc" '-' '-' '-' '-' '-' "집계 실패(jq)" >> "$TRACES_TXT"
        continue
      fi
      n="$(printf '%s' "$dist" | cut -f1)"
      note=""
      [ "$n" != "$TRACE_LIMIT" ] || note="상한 도달 — 실제는 더 많다"
      printf '%s\n' "$dist" | awk -F'\t' -v s="$svc" -v note="$note" \
        '{ printf "%-22s %7s %9s %9s %9s %9s  %s\n", s, $1, $2, $3, $4, $5, note }' >> "$TRACES_TXT"
    else
      printf '%-22s %7s %9s %9s %9s %9s  %s\n' "$svc" '-' '-' '-' '-' '-' "질의 실패: $TEMPO_ERR" >> "$TRACES_TXT"
      warn "트레이스[$svc]: $TEMPO_ERR"
    fi
  done

  # ---- 4. 결제 확인 경로 ---------------------------------------------------
  # 이 스택은 Micrometer 계측이라 http.route가 없다(실측). 스팬 이름 매칭이 1순위,
  # 나머지는 계측 규약이 바뀌었을 때를 위한 폴백이다. 처음으로 결과가 나온 필터를 쓴다.
  local pay_dist="" pay_used="" pay_stats="-" pay_err=""
  for q in '{ name =~ ".*payments/confirm.*" }' \
           '{ span.uri = "/api/v1/payments/confirm" }' \
           '{ span.http.uri = "/api/v1/payments/confirm" }' \
           '{ span.http.route = "/api/v1/payments/confirm" }'; do
    if tempo_search "payments-confirm" "$q" "$TRACE_LIMIT"; then
      pay_used="$q"
      pay_stats="$(jq -r "$JQ_TSTATS" < "$TRACE_TMP" 2>/dev/null | tr -d '\n' || echo '-')"
      if [ "$(trace_count)" != "0" ]; then
        pay_dist="$(jq -r "$JQ_DUR_DIST" < "$TRACE_TMP" 2>/dev/null || true)"
        break
      fi
    else
      pay_err="$TEMPO_ERR"
      warn "트레이스[결제 확인 경로]: $TEMPO_ERR"
    fi
  done
  {
    echo ""
    if [ -n "$pay_dist" ]; then
      echo "[4] 결제 확인 경로(/api/v1/payments/confirm) 소요시간 분포    TraceQL: $pay_used    ($pay_stats)"
      printf '%7s %9s %9s %9s %9s\n' N MIN_MS P50_MS P90_MS MAX_MS
      printf '%s\n' "$pay_dist" | awk -F'\t' '{ printf "%7s %9s %9s %9s %9s\n", $1, $2, $3, $4, $5 }'
    elif [ -n "$pay_used" ]; then
      echo "[4] 결제 확인 경로(/api/v1/payments/confirm) 소요시간 분포"
      echo "(후보 필터를 전부 질의했으나 0건이다 — 이 구간에 결제 확인 호출이 없었거나 스팬 이름/속성이 바뀌었다)"
    else
      echo "[4] 결제 확인 경로(/api/v1/payments/confirm) 소요시간 분포"
      echo "질의 실패: ${pay_err:-원인 미상}"
    fi
  } >> "$TRACES_TXT"

  {
    echo ""
    echo "읽는 법 — TRACE_ID를 Grafana Explore의 Tempo 데이터소스에 그대로 붙여넣으면 그 트레이스가 열린다."
    echo "N은 개수가 아니라 표본 수다. Tempo에는 개수 집계 API가 없고(metrics_generator 미구성)"
    echo "검색은 limit까지만 돌려주므로, N이 상한과 같으면 '적어도 그만큼'으로만 읽어야 한다."
    echo "[1]의 ERROR_SPANS는 에러 트레이스 표본 안에서 그 서비스가 낸 에러 스팬 수의 합이다."
    echo "루트가 apigateway인 트레이스가 대부분이므로, 어느 서비스가 실패의 진원인지는 [1]로 보고"
    echo "그 지연이 어디서 왔는지는 [3]의 P90/MAX와 metrics.csv의 hikari_*·http_* 를 겹쳐 읽는다."
  } >> "$TRACES_TXT"

  rm -f "$TRACE_TMP" 2>/dev/null || true
  echo "트레이스: $TRACES_TXT" >&2
  return 0
}

# ------------------------------------------------------------------- 요약
write_summary() {
  {
    echo "================================================================"
    echo " 라운드: $ROUND"
    echo " 노드: $NODE_NAME"
    echo " 간격: ${INTERVAL}s / 기간: ${DURATION:-무한(수동 종료)} / rate 윈도: $RATE_WINDOW"
    echo " Prometheus: ${PROM_URL:-미확정}"
    echo " 생성: $(now_iso)"
    echo "================================================================"
  } > "$SUMMARY"

  local f
  for f in "$METRICS_CSV" "$NODE_CSV"; do
    [ -s "$f" ] || continue
    {
      echo ""
      echo "---- $(basename "$f") ----"
      printf '%-26s %-46s %5s %13s %13s %13s %5s\n' METRIC LABELS N MAX LAST DELTA ERR
    } >> "$SUMMARY"

    awk -F, '
      function isnum(s) { return s ~ /^-?([0-9]+\.?[0-9]*|\.[0-9]+)([eE][-+]?[0-9]+)?$/ }
      NR == 1 { next }
      {
        key = $2 SUBSEP $3
        if (!(key in seen)) { seen[key] = 1; order[++k] = key; m[key] = $2; l[key] = $3 }
        if ($4 == "ERROR") { err[key]++; next }
        if (!isnum($4)) { bad[key]++; next }
        v = $4 + 0
        n[key]++
        if (!(key in mx) || v > mx[key]) mx[key] = v
        if (!(key in first)) first[key] = v
        last[key] = v
      }
      END {
        for (i = 1; i <= k; i++) {
          key = order[i]
          if (n[key] > 0)
            printf "%-26s %-46s %5d %13.6g %13.6g %13.6g %5d\n",
                   m[key], l[key], n[key], mx[key], last[key], last[key] - first[key], err[key] + 0
          else
            printf "%-26s %-46s %5d %13s %13s %13s %5d\n",
                   m[key], l[key], 0, "-", "-", "-", err[key] + bad[key] + 0
        }
      }
    ' "$f" | sort >> "$SUMMARY"
  done

  {
    echo ""
    echo "MAX/LAST는 관측 표본의 최대·마지막 값. DELTA는 마지막−처음(누적 카운터 *_cum 계열에서"
    echo "구간 증가분으로 읽는다). ERR>0 이면 그 지표는 그 횟수만큼 조회 실패·무표본이었다."
    echo ""
    echo "커넥션 점유 읽는 법: 전 구간 평균 점유 시간(초) = hikari_usage_sum_cum의 DELTA /"
    echo "hikari_usage_count_cum의 DELTA (앱별로 각각). 이 값 × hikari_usage_rate 가"
    echo "hikari_active LAST보다 뚜렷이 작으면, 차이만큼은 요청 경로가 아니라 커넥션을 상시"
    echo "쥐고 있는 배경 작업 몫이다. hikari_acquire_avg 가 같이 크면 이미 대기가 발생한 것."
    echo "*_meter_names 행은 값이 아니라 진단이다 — 그 이름의 지표가 실제로 존재했다는 뜻이고,"
    echo "ERR(empty-result)만 남았다면 해당 계열이 노출되지 않아 쿼리 이름을 고쳐야 한다."
  } >> "$SUMMARY"

  echo "요약: $SUMMARY" >&2
}

# -------------------------------------------------------------------- 본체
discover_prom

echo "출력: $OUT_DIR" >&2
echo "  시계열: $METRICS_CSV" >&2
echo "  노드:   $NODE_CSV" >&2

# 첫 폴링 전에 링크가 실제로 쿼리를 받는지 확인 — 빈 파일만 남기고 끝나는 사고 방지.
PROBE="$(curl -sS -G --max-time 15 --data-urlencode 'query=up' "$PROM_URL/api/v1/query" 2>/dev/null | jq -r '.data.result | length' 2>/dev/null || true)"
case "${PROBE:-0}" in
  ''|0) die "Prometheus가 'up' 시리즈조차 돌려주지 않는다 ($PROM_URL). 수집을 시작하지 않는다.";;
esac
echo "사전 점검 통과: up 시리즈 ${PROBE}건" >&2

FINISHED=1
START="$(date +%s)"
ROUND_N=0

while :; do
  ROUND_N=$((ROUND_N + 1))
  TICK="$(date +%s)"

  ensure_link
  for q in "${QUERIES[@]}"; do
    query_one "${q%%|*}" "${q#*|}"
  done
  sample_node

  printf '[%s] 폴링 #%d 완료 (지표 %d행 / 노드 %d행)\n' \
    "$(now_iso)" "$ROUND_N" \
    "$(( $(wc -l < "$METRICS_CSV") - 1 ))" \
    "$(( $(wc -l < "$NODE_CSV") - 1 ))" >&2

  [ "$STOP" = "0" ] || break
  if [ -n "$DURATION" ] && [ $(( $(date +%s) - START )) -ge "$DURATION" ]; then
    echo "기간 ${DURATION}s 도달 — 종료한다." >&2
    break
  fi

  # 폴링에 걸린 시간을 빼고 남은 만큼만 쉰다.
  SPENT=$(( $(date +%s) - TICK ))
  REST=$(( INTERVAL - SPENT ))
  [ "$REST" -gt 0 ] || REST=1
  # 기간 지정 시 초과 수면 방지.
  if [ -n "$DURATION" ]; then
    LEFT=$(( DURATION - ($(date +%s) - START) ))
    [ "$LEFT" -gt 0 ] || break
    [ "$REST" -le "$LEFT" ] || REST="$LEFT"
  fi
  sleep "$REST" || break
  [ "$STOP" = "0" ] || break
done

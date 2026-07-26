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

# ---------------------------------------------------------------- 정리·요약
FINISHED=0
cleanup() {
  local rc=$?
  if [ -n "$PF_PID" ]; then kill "$PF_PID" 2>/dev/null || true; wait "$PF_PID" 2>/dev/null || true; fi
  if [ "$FINISHED" = "1" ]; then write_summary || warn "요약 생성 실패"; fi
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

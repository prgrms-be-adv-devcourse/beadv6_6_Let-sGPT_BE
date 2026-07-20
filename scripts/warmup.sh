#!/usr/bin/env bash
#
# JVM 웜업 스크립트 (1단계 — 외부 웜업)
#
# 배포·재기동·사전 스케일 직후의 콜드 JVM(인터프리터 구간 + 콜드 커넥션풀/캐시)이
# 첫 트래픽 지연과 성능 베이스라인 오염을 만든다. 이 스크립트는 apigateway를 경유해
# product/order의 "부작용 없는 멱등 GET 핫패스"만 반복 호출하여 JIT(C2 승격)와
# 커넥션풀/캐시를 예열한다. 쓰기 엔드포인트는 절대 호출하지 않는다.
#
# 사용 시점:
#   - 베이스라인/부하 측정 직전(콜드스타트 오염 방지).
#   - 드롭 전 사전 스케일 직후(새 replica 예열).
#
# 사용법:
#   ./scripts/warmup.sh [--wait]
#
# 환경변수:
#   BASE_URL     게이트웨이 주소 (기본 http://localhost:8000; kubectl port-forward 전제)
#   ITERATIONS   대상별 요청 횟수 (기본 150)
#   CONCURRENCY  동시 요청 수 (기본 4)
#   AUTH_TOKEN   order 등 인증 GET용 Bearer 토큰 (미지정 시 인증 대상 스킵 + 경고)
#   WINDOW       p95 비교 구간 크기 (기본 20)
#
# 옵션:
#   --wait       kubectl 이 있으면 대상 deployment 의 rollout 완료를 먼저 대기.
#                kubectl 이 없으면 조용히 스킵한다.
#
# 예:
#   kubectl -n openat port-forward svc/apigateway 8000:8000 &
#   AUTH_TOKEN="$(cat /path/to/test-token)" ./scripts/warmup.sh --wait
#
set -euo pipefail

# ---------------------------------------------------------------------------
# 파라미터
# ---------------------------------------------------------------------------
BASE_URL="${BASE_URL:-http://localhost:8000}"
ITERATIONS="${ITERATIONS:-150}"
CONCURRENCY="${CONCURRENCY:-4}"
AUTH_TOKEN="${AUTH_TOKEN:-}"
WINDOW="${WINDOW:-20}"

# k8s (선택) — --wait 시에만 사용
K8S_NAMESPACE="${K8S_NAMESPACE:-openat}"
K8S_DEPLOYMENTS="${K8S_DEPLOYMENTS:-apigateway product order}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-300s}"

DO_WAIT=0
for arg in "$@"; do
  case "$arg" in
    --wait) DO_WAIT=1 ;;
    -h|--help)
      grep -E '^#( |$)' "$0" | sed -E 's/^# ?//'
      exit 0
      ;;
    *)
      echo "ERROR: 알 수 없는 인자: $arg (사용법은 --help)" >&2
      exit 2
      ;;
  esac
done

# 정수 파라미터 검증 — 잘못된 값은 조용히 넘기지 않고 즉시 실패.
for pair in "ITERATIONS=$ITERATIONS" "CONCURRENCY=$CONCURRENCY" "WINDOW=$WINDOW"; do
  name="${pair%%=*}"; val="${pair#*=}"
  if ! [[ "$val" =~ ^[0-9]+$ ]] || [ "$val" -lt 1 ]; then
    echo "ERROR: $name 은(는) 1 이상의 정수여야 합니다 (현재: '$val')" >&2
    exit 2
  fi
done

# curl 은 필수.
if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: curl 이 필요합니다." >&2
  exit 2
fi

WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/warmup.XXXXXX")"
cleanup() { rm -rf "$WORKDIR"; }
trap cleanup EXIT

log()  { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*"; }
warn() { printf '%s WARN: %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
err()  { printf '%s ERROR: %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }

# ---------------------------------------------------------------------------
# 대상 엔드포인트
#   name|path|needs_auth
#   needs_auth=1 인 경로는 AUTH_TOKEN 이 있어야 호출 (없으면 스킵 + 경고).
#   모두 부작용 없는 멱등 GET 만. 쓰기/상태변경 경로는 절대 포함하지 않는다.
#
#   경로 근거 (apigateway/src/main/resources/application-local.yaml 라우트 +
#   apigateway/.../config/SecurityConfig.java 인가 규칙):
#     product-list : GET /api/v1/products   → product-api 라우트, GET permitAll
#     drop-list    : GET /api/v1/drops      → product-api 라우트, GET permitAll
#     category-list: GET /api/v1/categories → product-api 라우트, GET permitAll
#     order-list   : GET /api/v1/orders     → order-api 라우트(AdmissionCheck 는 POST 전용,
#                                             GET 은 통과), 인증 필요(비-scoped access token)
# ---------------------------------------------------------------------------
ENDPOINTS=(
  "product-list|/api/v1/products?page=0&size=20|0"
  "drop-list|/api/v1/drops?page=0&size=20|0"
  "category-list|/api/v1/categories|0"
  "order-list|/api/v1/orders?page=0&size=20|1"
)

# ---------------------------------------------------------------------------
# (선택) rollout 대기
# ---------------------------------------------------------------------------
wait_for_rollout() {
  if [ "$DO_WAIT" -ne 1 ]; then
    return 0
  fi
  if ! command -v kubectl >/dev/null 2>&1; then
    warn "kubectl 이 없어 rollout 대기를 스킵합니다 (--wait 무시)."
    return 0
  fi
  log "rollout 대기 (namespace=$K8S_NAMESPACE, timeout=$ROLLOUT_TIMEOUT): $K8S_DEPLOYMENTS"
  for dep in $K8S_DEPLOYMENTS; do
    if ! kubectl -n "$K8S_NAMESPACE" rollout status "deployment/$dep" --timeout="$ROLLOUT_TIMEOUT"; then
      err "deployment/$dep rollout 미완료 — 콜드 파드로 웜업하면 측정이 오염됩니다."
      exit 1
    fi
  done
}

# ---------------------------------------------------------------------------
# 단일 요청: "$path" "$needs_auth" "$outfile"
#   결과 한 줄 append: "<http_code> <latency_ms>"
#   단일 printf 는 O_APPEND 로 원자적이라 동시 append 에도 라인이 섞이지 않는다.
# ---------------------------------------------------------------------------
one_request() {
  local path="$1" needs_auth="$2" out="$3"
  local auth_hdr=()
  if [ "$needs_auth" = "1" ] && [ -n "$AUTH_TOKEN" ]; then
    auth_hdr=(-H "Authorization: Bearer $AUTH_TOKEN")
  fi
  local res code t ms
  # 실패해도 스크립트를 죽이지 않고(집계에서 판단) code=000 으로 기록.
  res="$(curl -s -o /dev/null -w '%{http_code} %{time_total}' \
              --max-time 30 "${auth_hdr[@]}" "$BASE_URL$path" 2>/dev/null || echo '000 0')"
  code="${res%% *}"
  t="${res##* }"
  ms="$(awk -v x="$t" 'BEGIN{printf "%.1f", x*1000}')"
  printf '%s %s\n' "$code" "$ms" >> "$out"
}

# ---------------------------------------------------------------------------
# stdin 으로 받은 ms 값들의 p95 (오름차순 정렬 후 ceil(0.95*N) 번째)
# ---------------------------------------------------------------------------
p95() {
  sort -n | awk '
    { a[NR]=$1 }
    END {
      if (NR == 0) { print "NA"; exit }
      idx = int((NR*95 + 99) / 100)   # ceil(0.95*NR)
      if (idx < 1) idx = 1
      if (idx > NR) idx = NR
      printf "%.1f", a[idx]
    }'
}

# ---------------------------------------------------------------------------
# 한 엔드포인트를 ITERATIONS 회, CONCURRENCY 동시성으로 호출.
#   wave(=CONCURRENCY 묶음) 단위로 순차 진행 → 파일 라인 순서가 시간 순서를 근사.
# ---------------------------------------------------------------------------
run_endpoint() {
  local name="$1" path="$2" needs_auth="$3" out="$4"
  : > "$out"
  local dispatched=0
  local pids
  while [ "$dispatched" -lt "$ITERATIONS" ]; do
    pids=()
    local n=0
    while [ "$n" -lt "$CONCURRENCY" ] && [ "$dispatched" -lt "$ITERATIONS" ]; do
      one_request "$path" "$needs_auth" "$out" &
      pids+=("$!")
      dispatched=$((dispatched + 1))
      n=$((n + 1))
    done
    wait "${pids[@]}"
  done
}

# ---------------------------------------------------------------------------
# 엔드포인트 결과 집계 + plateau 판정.
#   반환(전역): 없음. 판정 결과는 요약 배열에 push, 실패 여부는 종료코드 관리.
# ---------------------------------------------------------------------------
SUMMARY=()
HARD_FAIL=0     # 대상 완전 불통(2xx 0건) → exit 1
PLATEAU_WARN=0  # plateau 미도달 → WARN(exit 0 유지)

analyze_endpoint() {
  local name="$1" out="$2"
  local total ok errors
  total="$(wc -l < "$out" | tr -d ' ')"
  local ok_file="$out.ok"
  awk '$1 ~ /^2/ {print $2}' "$out" > "$ok_file"
  ok="$(wc -l < "$ok_file" | tr -d ' ')"
  errors=$((total - ok))

  if [ "$errors" -gt 0 ]; then
    # 비-2xx 상태코드 분포를 시끄럽게 노출.
    local dist
    dist="$(awk '$1 !~ /^2/ {c[$1]++} END{for(k in c) printf "%s×%d ", k, c[k]}' "$out")"
    warn "[$name] 비정상 응답 ${errors}/${total}건: ${dist}"
  fi

  if [ "$ok" -eq 0 ]; then
    err "[$name] 성공(2xx) 응답 0건 — 대상 불통. BASE_URL/토큰/라우트를 확인하세요."
    HARD_FAIL=1
    SUMMARY+=("$name: FAIL (2xx 0/${total})")
    return
  fi

  local init_p95 last_p95
  init_p95="$(head -n "$WINDOW" "$ok_file" | p95)"
  last_p95="$(tail -n "$WINDOW" "$ok_file" | p95)"

  if [ "$ok" -lt $((WINDOW * 2)) ]; then
    warn "[$name] 2xx 표본 ${ok}건 < ${WINDOW}×2 — plateau 판정 신뢰도 낮음."
  fi

  # plateau: 마지막 구간 p95 가 초기 구간 대비 개선되거나 5% 이내로 안정.
  local verdict
  verdict="$(awk -v i="$init_p95" -v l="$last_p95" 'BEGIN{
      if (i+0 <= 0) { print "warn"; exit }
      if (l+0 <= i*1.05) print "ok"; else print "warn";
    }')"

  if [ "$verdict" = "ok" ]; then
    log "[$name] plateau 도달 — 초기 p95=${init_p95}ms → 마지막 p95=${last_p95}ms (2xx ${ok}/${total})"
    SUMMARY+=("$name: OK  초기 p95=${init_p95}ms → 마지막 p95=${last_p95}ms (2xx ${ok}/${total})")
  else
    warn "[$name] plateau 미도달 — 초기 p95=${init_p95}ms → 마지막 p95=${last_p95}ms (개선/안정 아님)"
    PLATEAU_WARN=1
    SUMMARY+=("$name: WARN 초기 p95=${init_p95}ms → 마지막 p95=${last_p95}ms (2xx ${ok}/${total})")
  fi
}

# ---------------------------------------------------------------------------
# 실행
# ---------------------------------------------------------------------------
log "웜업 시작 — BASE_URL=$BASE_URL ITERATIONS=$ITERATIONS CONCURRENCY=$CONCURRENCY WINDOW=$WINDOW"

wait_for_rollout

if [ -z "$AUTH_TOKEN" ]; then
  warn "AUTH_TOKEN 미지정 — 인증 필요 대상(order-list)은 스킵합니다."
fi

RAN_ANY=0
for entry in "${ENDPOINTS[@]}"; do
  IFS='|' read -r name path needs_auth <<< "$entry"

  if [ "$needs_auth" = "1" ] && [ -z "$AUTH_TOKEN" ]; then
    SUMMARY+=("$name: SKIP (AUTH_TOKEN 없음)")
    continue
  fi

  out="$WORKDIR/$name.lat"
  log "[$name] GET $path × $ITERATIONS (동시성 $CONCURRENCY) ..."
  run_endpoint "$name" "$path" "$needs_auth" "$out"
  analyze_endpoint "$name" "$out"
  RAN_ANY=1
done

if [ "$RAN_ANY" -eq 0 ]; then
  err "호출한 대상이 없습니다 — 설정을 확인하세요."
  exit 1
fi

# ---------------------------------------------------------------------------
# 요약
# ---------------------------------------------------------------------------
echo
log "===== 웜업 요약 ====="
for s in "${SUMMARY[@]}"; do
  printf '  - %s\n' "$s"
done

if [ "$HARD_FAIL" -ne 0 ]; then
  err "일부 대상이 완전 불통입니다 — 웜업 실패."
  exit 1
fi

if [ "$PLATEAU_WARN" -ne 0 ]; then
  warn "일부 대상이 plateau 에 도달하지 못했습니다 — ITERATIONS 를 늘리거나 파드 상태를 확인하세요."
  # 운영 영향 없는 읽기 호출이므로 exit 0 유지(시끄러운 WARN 만).
fi

log "웜업 완료."
exit 0

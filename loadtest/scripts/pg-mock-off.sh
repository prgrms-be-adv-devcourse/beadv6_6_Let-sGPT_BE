#!/usr/bin/env bash
# pg-mock-off.sh — pg-mock-on.sh가 한 일을 전부 되돌린다.
#
#   ./loadtest/scripts/pg-mock-off.sh
#
# 되돌리는 순서(on의 역순, 안전 우선):
#   1) payment에서 PG_BASE_URL 제거 + rollout 대기   ← 실 토스 복귀가 최우선
#   2) WireMock Deployment/Service/ConfigMap 삭제
#   3) ArgoCD auto-sync를 저장된 원본값으로 복원 + hard refresh
#
# 설계 원칙: 앞 단계가 실패해도 뒤 단계는 반드시 시도한다("가짜 PG가 켜진 채로 방치"가
# 최악의 결말이므로). 각 단계는 실패해도 즉시 죽지 않고 FAILURES에 기록만 하고 계속
# 진행하며, 마지막에 하나라도 실패했으면 exit 1 로 시끄럽게 끝낸다.
# set -e는 유지하되 되돌리기 명령들은 전부 run() 래퍼로 감싸 -e를 우회한다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOADTEST_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"

NS="${LT_NAMESPACE:-openat}"
ARGO_NS="${LT_ARGOCD_NAMESPACE:-argocd}"
ARGO_APP="${LT_ARGOCD_APP:-openat}"
MANIFEST="${LT_MANIFEST:-${LOADTEST_DIR}/k8s/wiremock.yaml}"
CM_NAME="wiremock-toss-mappings"
STATE_FILE="${LT_STATE_FILE:-${LOADTEST_DIR}/.pg-mock-state.json}"

FAILURES=()

log()  { printf '\033[1;36m[pg-mock-off]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[pg-mock-off] WARN:\033[0m %s\n' "$*" >&2; }

# 실패해도 스크립트를 죽이지 않고 기록만 남기는 래퍼.
run() {
  local desc="$1"; shift
  if "$@"; then
    return 0
  fi
  warn "실패: ${desc}"
  FAILURES+=("$desc")
  return 0
}

command -v kubectl >/dev/null 2>&1 || { echo "kubectl이 PATH에 없다." >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. payment를 실 토스로 복귀 (가장 중요)
# ---------------------------------------------------------------------------
if kubectl -n "$NS" get deployment payment >/dev/null 2>&1; then
  # 컨테이너 env에 PG_BASE_URL이 실제로 있는지 확인 — 없으면 set env가 불필요한
  # 롤아웃(=파드 재시작 300s)을 유발하므로 건너뛴다(멱등).
  HAS_ENV="$(kubectl -n "$NS" get deployment payment \
      -o jsonpath='{.spec.template.spec.containers[?(@.name=="payment")].env[?(@.name=="PG_BASE_URL")].name}' \
      2>/dev/null || true)"
  if [ -n "$HAS_ENV" ]; then
    log "payment에서 PG_BASE_URL 제거 -> 기본값(https://api.tosspayments.com) 복귀"
    run "payment PG_BASE_URL 제거" \
      kubectl -n "$NS" set env deployment/payment PG_BASE_URL- >/dev/null
    log "payment rollout 대기..."
    run "payment rollout" \
      kubectl -n "$NS" rollout status deployment/payment --timeout=420s
  else
    log "payment에 PG_BASE_URL이 없다 (이미 복귀 상태, no-op)."
  fi
else
  warn "$NS 에 payment Deployment가 없다. 건너뛴다."
fi

# ---------------------------------------------------------------------------
# 2. WireMock 제거
# ---------------------------------------------------------------------------
if [ -f "$MANIFEST" ]; then
  log "WireMock Deployment/Service 삭제"
  run "wiremock 매니페스트 삭제" \
    kubectl delete -f "$MANIFEST" --ignore-not-found=true --wait=false
else
  warn "매니페스트를 못 찾았다($MANIFEST). 라벨 셀렉터로 대신 삭제한다."
  run "라벨 셀렉터 삭제" \
    kubectl -n "$NS" delete deployment,service -l openat.dev/loadtest=true --ignore-not-found=true --wait=false
fi

log "mappings ConfigMap 삭제"
run "configmap 삭제" \
  kubectl -n "$NS" delete configmap "$CM_NAME" --ignore-not-found=true

# ---------------------------------------------------------------------------
# 3. ArgoCD auto-sync 복원
# ---------------------------------------------------------------------------
if kubectl -n "$ARGO_NS" get application "$ARGO_APP" >/dev/null 2>&1; then
  if [ -f "$STATE_FILE" ]; then
    AUTOMATED="$(tr -d '\n' < "$STATE_FILE")"
  else
    # 상태파일 분실 시 리포의 desired-state(k8s/argocd/app-openat.yaml)와 동일한 값으로 복원.
    AUTOMATED='{"prune":true,"selfHeal":true}'
    warn "상태파일이 없다. app-openat.yaml 기준 기본값으로 복원한다: $AUTOMATED"
  fi
  # 빈 값/깨진 값이면 기본값으로 대체 — auto-sync가 꺼진 채 방치되는 게 최악.
  case "$AUTOMATED" in
    '{'*'}') : ;;
    *) warn "저장값이 이상하다('$AUTOMATED'). 기본값으로 대체."; AUTOMATED='{"prune":true,"selfHeal":true}' ;;
  esac

  log "ArgoCD auto-sync 복원: $AUTOMATED"
  run "syncPolicy 복원" \
    kubectl -n "$ARGO_NS" patch application "$ARGO_APP" --type merge \
      -p "{\"spec\":{\"syncPolicy\":{\"automated\":${AUTOMATED}}}}" >/dev/null

  # 폴링(기본 3분)을 기다리지 않고 즉시 재조회 -> selfHeal이 바로 desired-state로 수렴.
  run "hard refresh" \
    kubectl -n "$ARGO_NS" annotate application "$ARGO_APP" \
      argocd.argoproj.io/refresh=hard --overwrite >/dev/null

  if [ ${#FAILURES[@]} -eq 0 ] && [ -f "$STATE_FILE" ]; then
    rm -f "$STATE_FILE"
  fi

  log "현재 상태:"
  kubectl -n "$ARGO_NS" get application "$ARGO_APP" \
    -o jsonpath='  automated={.spec.syncPolicy.automated}{"\n"}  sync={.status.sync.status} health={.status.health.status}{"\n"}' \
    || true
else
  warn "application/$ARGO_APP 를 찾을 수 없다. auto-sync 복원을 건너뛴다."
fi

echo
if [ ${#FAILURES[@]} -ne 0 ]; then
  printf '\033[1;31m[pg-mock-off] %d개 단계 실패 — 수동 확인 필요:\033[0m\n' "${#FAILURES[@]}" >&2
  printf '  - %s\n' "${FAILURES[@]}" >&2
  cat >&2 <<EOF

  최소한 아래 두 가지는 눈으로 확인할 것:
    kubectl -n ${NS} get deployment payment -o jsonpath='{.spec.template.spec.containers[0].env}'; echo
    kubectl -n ${ARGO_NS} get application ${ARGO_APP} -o jsonpath='{.spec.syncPolicy.automated}'; echo
EOF
  exit 1
fi

printf '\033[1;42;30m %s \033[0m\n' "실 PG 복귀 완료 · ArgoCD auto-sync 복원 완료"

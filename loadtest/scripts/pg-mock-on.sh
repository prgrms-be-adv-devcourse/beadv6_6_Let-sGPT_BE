#!/usr/bin/env bash
# pg-mock-on.sh — payment의 아웃바운드 PG를 실 토스에서 WireMock 목으로 전환한다.
#
# 실행 위치: kubeconfig가 있는 곳(= EC2 서버 노드). 리포가 체크아웃돼 있어야 한다
# (loadtest/wiremock/mappings/*.json 원본에서 ConfigMap을 만들기 때문).
#
#   ./loadtest/scripts/pg-mock-on.sh
#   LT_PG_DELAY_MEDIAN_MS=800 ./loadtest/scripts/pg-mock-on.sh
#
# 하는 일(순서 중요):
#   1) ArgoCD openat Application의 auto-sync를 중단한다.
#      → 이걸 먼저 하지 않으면 selfHeal이 3분 안에 payment의 PG_BASE_URL을 되돌린다.
#        (원래 syncPolicy.automated 값을 상태파일에 저장해 off가 그대로 복원한다.)
#   2) mappings ConfigMap 생성 + wiremock Deployment/Service apply + rollout 대기
#   3) payment Deployment에 PG_BASE_URL 주입 + rollout 대기
#   4) "지금부터 결제는 가짜 PG로 간다" 경고 출력
#
# 멱등: 모든 단계가 apply/set-env 기반이라 여러 번 돌려도 결과가 같다.
#       상태파일은 "없을 때만" 기록해서, 재실행이 (이미 pause된) 현재값으로 원본을
#       덮어쓰지 않게 한다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOADTEST_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# .github/workflows/deploy.yml 의 env.KUBECONFIG 와 동일한 규약(러너가 쓰는 k3s kubeconfig).
export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"

NS="${LT_NAMESPACE:-openat}"
ARGO_NS="${LT_ARGOCD_NAMESPACE:-argocd}"
ARGO_APP="${LT_ARGOCD_APP:-openat}"
MAPPINGS_DIR="${LT_MAPPINGS_DIR:-${LOADTEST_DIR}/wiremock/mappings}"
MANIFEST="${LT_MANIFEST:-${LOADTEST_DIR}/k8s/wiremock.yaml}"
CM_NAME="wiremock-toss-mappings"
STATE_FILE="${LT_STATE_FILE:-${LOADTEST_DIR}/.pg-mock-state.json}"

# 스텁의 lognormal median(ms). 기본 300 — 이 지연이 payment의 스레드/커넥션풀 압력을
# 만드는 장본인이라 0으로 만들면 측정 의미가 사라진다.
DELAY_MEDIAN_MS="${LT_PG_DELAY_MEDIAN_MS:-300}"

PG_URL="http://wiremock-toss.${NS}.svc.cluster.local:8080"

log() { printf '\033[1;36m[pg-mock-on]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[pg-mock-on] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 0. 사전 점검
# ---------------------------------------------------------------------------
command -v kubectl >/dev/null 2>&1 || die "kubectl이 PATH에 없다."
[ -d "$MAPPINGS_DIR" ] || die "mappings 디렉터리가 없다: $MAPPINGS_DIR"
[ -f "$MANIFEST" ] || die "매니페스트가 없다: $MANIFEST"
kubectl get ns "$NS" >/dev/null 2>&1 || die "네임스페이스 $NS 를 볼 수 없다. KUBECONFIG=$KUBECONFIG 확인."
kubectl -n "$NS" get deployment payment >/dev/null 2>&1 || die "$NS 에 payment Deployment가 없다."

# ---------------------------------------------------------------------------
# 1. ArgoCD auto-sync 중단 (반드시 먼저)
# ---------------------------------------------------------------------------
if kubectl -n "$ARGO_NS" get application "$ARGO_APP" >/dev/null 2>&1; then
  CURRENT_AUTOMATED="$(kubectl -n "$ARGO_NS" get application "$ARGO_APP" \
      -o jsonpath='{.spec.syncPolicy.automated}' 2>/dev/null || true)"

  if [ ! -f "$STATE_FILE" ]; then
    # 최초 실행에서만 원본을 기록한다. 이미 pause된 상태에서 재실행하면 빈 값이
    # 저장되어 off가 auto-sync를 영영 복구 못 하기 때문.
    if [ -z "$CURRENT_AUTOMATED" ]; then
      log "경고: auto-sync가 이미 꺼져 있고 저장된 원본도 없다. off는 기본값 {prune:true,selfHeal:true}로 복원한다."
      printf '%s\n' '{"prune":true,"selfHeal":true}' > "$STATE_FILE"
    else
      printf '%s\n' "$CURRENT_AUTOMATED" > "$STATE_FILE"
    fi
    log "원래 syncPolicy.automated 저장 -> $STATE_FILE ($(cat "$STATE_FILE"))"
  else
    log "상태파일이 이미 있다(재실행). 원본 보존: $(cat "$STATE_FILE")"
  fi

  if [ -n "$CURRENT_AUTOMATED" ]; then
    log "ArgoCD auto-sync 중단: application/$ARGO_APP"
    kubectl -n "$ARGO_NS" patch application "$ARGO_APP" --type merge \
      -p '{"spec":{"syncPolicy":{"automated":null}}}' >/dev/null
  else
    log "ArgoCD auto-sync는 이미 꺼져 있다 (no-op)."
  fi
else
  log "경고: application/$ARGO_APP 를 찾을 수 없다. ArgoCD 미설치로 보고 계속 진행한다."
fi

# ---------------------------------------------------------------------------
# 2. mappings ConfigMap + WireMock 배포
# ---------------------------------------------------------------------------
# 원본 JSON은 절대 건드리지 않는다. median 치환은 임시 복사본에서만.
TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

cp "$MAPPINGS_DIR"/*.json "$TMP_DIR"/
if [ "$DELAY_MEDIAN_MS" != "300" ]; then
  log "PG 지연 median 300ms -> ${DELAY_MEDIAN_MS}ms 로 치환(임시 복사본)"
  sed -i "s/\"median\": 300/\"median\": ${DELAY_MEDIAN_MS}/g" "$TMP_DIR"/*.json
fi

log "ConfigMap/$CM_NAME 생성(원본: $MAPPINGS_DIR)"
kubectl -n "$NS" create configmap "$CM_NAME" \
  --from-file="$TMP_DIR" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

log "WireMock 매니페스트 apply: $MANIFEST"
kubectl apply -f "$MANIFEST" >/dev/null

# ConfigMap 내용이 바뀌어도 Deployment spec은 그대로라 파드가 갱신되지 않는다.
# 재실행 시 새 median이 반영되도록 명시적으로 재시작한다.
kubectl -n "$NS" rollout restart deployment/wiremock-toss >/dev/null
log "wiremock-toss rollout 대기..."
kubectl -n "$NS" rollout status deployment/wiremock-toss --timeout=180s

# ---------------------------------------------------------------------------
# 3. payment를 목으로 전환
# ---------------------------------------------------------------------------
log "payment.PG_BASE_URL = $PG_URL"
kubectl -n "$NS" set env deployment/payment "PG_BASE_URL=${PG_URL}" >/dev/null
log "payment rollout 대기(콜드부트 여유 필요 — startupProbe 최대 300s)..."
kubectl -n "$NS" rollout status deployment/payment --timeout=420s

# ---------------------------------------------------------------------------
# 4. 경고 + 검증 안내
# ---------------------------------------------------------------------------
cat <<EOF

$(printf '\033[1;41;97m %s \033[0m' "지금부터 이 클러스터의 모든 결제는 가짜 PG(WireMock)로 간다")

  실제 토스페이먼츠로는 아무 요청도 가지 않는다. 승인/취소는 전부 허구다.
  부하테스트가 끝나면 반드시:

      ./loadtest/scripts/pg-mock-off.sh

  ★ 램프업 전 필수 확인 (이걸 건너뛰면 실 토스를 때리고 있을 수 있다):
    1) smoke 프로파일로 1회 돌린 뒤
    2) WireMock 저널에 confirm 요청이 실제로 찍혔는지 본다

      kubectl -n ${NS} port-forward svc/wiremock-toss 8089:8080 &
      curl -s localhost:8089/__admin/requests/count
      curl -s 'localhost:8089/__admin/requests?limit=5' | head -40

    count가 0이면 payment는 목을 부르지 않고 있다. 배포된 payment 이미지가
    pg.base-url(PG_BASE_URL) 오버라이드를 지원하는 버전인지부터 확인할 것.

  ArgoCD auto-sync는 중단된 상태다(선반영/자가치유 없음). 이 동안의 배포는 반영되지 않는다.

EOF

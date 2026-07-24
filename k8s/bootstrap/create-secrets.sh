#!/usr/bin/env bash
# k8s Secret 부트스트랩 (최초 1회 수동 — 7/9 CD에서 CI 자동화로 교체 예정)
#
# 사용법:
#   ./create-secrets.sh <.env 경로>            # app-secrets 생성/갱신
#   GHCR_USER=<github id> GHCR_PAT=<read:packages PAT> ./create-secrets.sh <.env 경로>
#                                              # + ghcr-pull(이미지 pull 시크릿)도 생성/갱신
#
# 값은 기존 .env/GitHub ENV_SECRETS에서 그대로 사용(신규 발급 아님).
set -euo pipefail

ENV_FILE="${1:?사용법: $0 <.env 경로>}"
NS=openat
# 러너/서버 환경에 따라 kubectl 경로가 다름(k3s는 `k3s kubectl`). 기본은 kubectl, 필요 시 override.
KUBECTL="${KUBECTL:-kubectl}"
export -n DB_USER DB_PASSWORD AI_QUERY_DB_PASSWORD JWT_KEY_ID JWT_PRIVATE_KEY JWT_PUBLIC_KEY \
  PG_CLIENT_KEY PG_SECRET_KEY PAYMENT_FIELD_ENCRYPTION_KEY OPENAI_API_KEY \
  CHAT_INFERENCE_API_KEY GHCR_USER GHCR_PAT WARMUP_EMAIL WARMUP_PASSWORD \
  GRAFANA_ADMIN_USER GRAFANA_ADMIN_PASSWORD 2>/dev/null || true
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=secret-manifest.sh
. "$SCRIPT_DIR/secret-manifest.sh"

ensure_argocd_idle() {
  local operation_phase
  operation_phase=$("$KUBECTL" -n argocd get application openat \
    -o jsonpath='{.status.operationState.phase}') || {
    echo "ERROR: ArgoCD 작업 상태를 읽지 못해 AI 조회 Secret을 만들지 않습니다." >&2
    return 1
  }
  case "$operation_phase" in
    Running|Terminating)
      echo "ERROR: 이전 ArgoCD 작업이 실행 중이라 AI 조회 Secret을 만들지 않습니다." >&2
      return 1
      ;;
  esac
}

[ -f "$ENV_FILE" ] || { echo "ERROR: $ENV_FILE 없음" >&2; exit 1; }

# .env는 compose --env-file 포맷(KEY=VALUE, 단일 라인) — 민감키만 추출
# shellcheck disable=SC1090
. "$ENV_FILE"

for key in DB_USER DB_PASSWORD AI_QUERY_DB_PASSWORD JWT_KEY_ID JWT_PRIVATE_KEY JWT_PUBLIC_KEY PG_CLIENT_KEY PG_SECRET_KEY PAYMENT_FIELD_ENCRYPTION_KEY OPENAI_API_KEY CHAT_INFERENCE_API_KEY; do
  [ -n "${!key:-}" ] || { echo "ERROR: $ENV_FILE 에 $key 없음/빈값 — 실패를 조용히 넘기지 않는다" >&2; exit 1; }
done

# .env의 export 문이나 workflow step env로 들어온 값도 자식 프로세스 환경에는 남기지 않는다.
for key in DB_USER DB_PASSWORD AI_QUERY_DB_PASSWORD JWT_KEY_ID JWT_PRIVATE_KEY JWT_PUBLIC_KEY \
  PG_CLIENT_KEY PG_SECRET_KEY PAYMENT_FIELD_ENCRYPTION_KEY OPENAI_API_KEY \
  CHAT_INFERENCE_API_KEY GHCR_USER GHCR_PAT WARMUP_EMAIL WARMUP_PASSWORD \
  GRAFANA_ADMIN_USER GRAFANA_ADMIN_PASSWORD; do
  export -n "$key" 2>/dev/null || true
done

# --- 도메인별 Secret 분리 (7/18 env 주입 전환) ---
# 공용 DB (단일 postgres·단일 role이라 공유 합리)
render_secret_manifest db-secrets "$NS" Opaque DB_USER DB_PASSWORD \
  | "$KUBECTL" apply -f -
echo "OK: db-secrets 적용 완료"

# AI 챗봇 read-model 조회 전용 계정. 일반 bootstrap은 기존 값을 절대 회전하지 않는다.
# 최초 생성은 active/rollback이 모두 없을 때만 허용하고, 이후 변경은 rotate 전용 스크립트로만 한다.
if ! existing_ai_secret=$("$KUBECTL" get secret ai-query-secrets -n "$NS" \
  --ignore-not-found \
  -o jsonpath='{.metadata.name}{"|"}{.data.AI_QUERY_DB_PASSWORD}{"|"}{.metadata.resourceVersion}'); then
  echo "ERROR: 기존 ai-query-secrets 조회에 실패했습니다. 최초 생성으로 간주하지 않습니다." >&2
  exit 1
fi
if ! rollback_metadata=$("$KUBECTL" get secret ai-query-rollback-secrets -n "$NS" \
  --ignore-not-found \
  -o jsonpath='{.metadata.name}{"|"}{.metadata.annotations.openat\.io/rotation-run-id}{"|"}{.metadata.annotations.openat\.io/target-active-resource-version}{"|"}{.metadata.annotations.openat\.io/target-deploy-revision}'); then
  echo "ERROR: AI 조회 rollback marker를 읽지 못했습니다." >&2
  exit 1
fi

if [ -z "$existing_ai_secret" ]; then
  [ -z "$rollback_metadata" ] || {
    echo "ERROR: active Secret은 없지만 rollback marker가 남아 있어 최초 생성하지 않습니다." >&2
    exit 1
  }
  ensure_argocd_idle
  rotation_started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  render_secret_manifest ai-query-rollback-secrets "$NS" Opaque \
    AI_QUERY_DB_PREVIOUS_PASSWORD=AI_QUERY_DB_PASSWORD \
    | "$KUBECTL" apply -f - >/dev/null
  "$KUBECTL" annotate secret ai-query-rollback-secrets -n "$NS" \
    openat.io/rotation-run-id=bootstrap-pending \
    openat.io/target-active-resource-version=pending \
    openat.io/target-deploy-revision=pending \
    openat.io/rotation-started-at="$rotation_started_at" --overwrite >/dev/null
  active_resource_version=$(render_secret_manifest ai-query-secrets "$NS" Opaque \
    AI_QUERY_DB_PASSWORD | "$KUBECTL" apply -f - \
    -o jsonpath='{.metadata.resourceVersion}')
  [ -n "$active_resource_version" ] || {
    echo "ERROR: 최초 생성한 ai-query-secrets resourceVersion이 비어 있습니다." >&2
    exit 1
  }
  "$KUBECTL" annotate secret ai-query-rollback-secrets -n "$NS" \
    openat.io/rotation-run-id=steady \
    openat.io/target-active-resource-version="$active_resource_version" \
    openat.io/target-deploy-revision=steady \
    openat.io/rotation-started-at- --overwrite >/dev/null
  echo "AI_QUERY_ROTATION_STARTED=true"
  echo "AI_QUERY_RECONCILE_REQUIRED=false"
  echo "ACTIVE_RESOURCE_VERSION=$active_resource_version"
else
  IFS='|' read -r existing_ai_secret_name previous_encoded active_resource_version <<<"$existing_ai_secret"
  [ "$existing_ai_secret_name" = "ai-query-secrets" ] \
    && [ -n "$previous_encoded" ] \
    && [ -n "$active_resource_version" ] || {
    echo "ERROR: 기존 ai-query-secrets의 비밀번호 key 또는 resourceVersion을 읽지 못했습니다." >&2
    exit 1
  }
  IFS='|' read -r rollback_name marker_run_id marker_active_rv marker_revision <<<"$rollback_metadata"
  if [ "$rollback_name" != "ai-query-rollback-secrets" ] \
    || [ "$marker_run_id" != steady ] \
    || [ "$marker_active_rv" != "$active_resource_version" ] \
    || [ "$marker_revision" != steady ]; then
    echo "ERROR: 이전 AI 조회 회전 marker가 steady가 아니어서 기존 Secret을 보존하고 중단합니다." >&2
    exit 1
  fi
  if ! AI_QUERY_DB_CURRENT_PASSWORD=$(printf '%s' "$previous_encoded" | base64 --decode); then
    echo "ERROR: 기존 AI 조회 비밀번호를 해석하지 못했습니다." >&2
    exit 1
  fi
  [ -n "$AI_QUERY_DB_CURRENT_PASSWORD" ] || {
    echo "ERROR: 기존 AI 조회 비밀번호가 비어 있습니다." >&2
    exit 1
  }
  if [ "$AI_QUERY_DB_CURRENT_PASSWORD" != "$AI_QUERY_DB_PASSWORD" ]; then
    echo "ERROR: .env의 AI_QUERY_DB_PASSWORD가 기존 active Secret과 다릅니다. 기존 값을 보존했으며 rotate-ai-query-secret.sh를 사용해야 합니다." >&2
    exit 1
  fi
  echo "AI_QUERY_ROTATION_STARTED=false"
  echo "AI_QUERY_RECONCILE_REQUIRED=false"
  echo "ACTIVE_RESOURCE_VERSION=$active_resource_version"
fi
unset AI_QUERY_DB_CURRENT_PASSWORD previous_encoded existing_ai_secret existing_ai_secret_name \
  rollback_metadata rollback_name marker_run_id marker_active_rv marker_revision rotation_started_at
echo "OK: ai-query-secrets bootstrap 상태 확인 완료"

# member 전용 — JWT 발급/서명 주체
render_secret_manifest member-secrets "$NS" Opaque JWT_KEY_ID JWT_PRIVATE_KEY JWT_PUBLIC_KEY \
  | "$KUBECTL" apply -f -
echo "OK: member-secrets 적용 완료"

# payment 전용
render_secret_manifest payment-secrets "$NS" Opaque PG_SECRET_KEY PAYMENT_FIELD_ENCRYPTION_KEY \
  | "$KUBECTL" apply -f -
echo "OK: payment-secrets 적용 완료"

# search 전용
render_secret_manifest search-secrets "$NS" Opaque OPENAI_API_KEY \
  | "$KUBECTL" apply -f -
echo "OK: search-secrets 적용 완료"

# AI 추론 서버 인증 전용
render_secret_manifest ai-inference-secrets "$NS" Opaque CHAT_INFERENCE_API_KEY \
  | "$KUBECTL" apply -f -
echo "OK: ai-inference-secrets 적용 완료"

# (전환기 한정) 레거시 app-secrets 병행 생성 — 매니페스트 revert 롤백 지렛대.
# 안정 확인 후 후속 커밋에서 이 블록과 위 필수 키 목록의 PG_CLIENT_KEY를 함께 제거하고
# `kubectl delete secret app-secrets -n openat` 1회 수동 실행으로 마감한다.
render_secret_manifest app-secrets "$NS" Opaque \
  DB_USER DB_PASSWORD JWT_KEY_ID JWT_PRIVATE_KEY JWT_PUBLIC_KEY PG_CLIENT_KEY PG_SECRET_KEY \
  PAYMENT_FIELD_ENCRYPTION_KEY OPENAI_API_KEY \
  | "$KUBECTL" apply -f -
echo "OK: app-secrets 적용 완료 (namespace=$NS)"

# GHCR 이미지 pull 시크릿 (BE/FE 이미지 모두 private — read:packages 스코프 PAT 필요)
if [ -n "${GHCR_PAT:-}" ]; then
  [ -n "${GHCR_USER:-}" ] || {
    echo "ERROR: GHCR_PAT 사용 시 GHCR_USER도 필요합니다." >&2
    exit 1
  }
  GHCR_AUTH=$(printf '%s:%s' "$GHCR_USER" "$GHCR_PAT" | base64 | tr -d '\r\n')
  printf -v GHCR_DOCKER_CONFIG '{"auths":{"ghcr.io":{"auth":"%s"}}}' "$GHCR_AUTH"
  export -n GHCR_AUTH GHCR_DOCKER_CONFIG 2>/dev/null || true
  render_secret_manifest ghcr-pull "$NS" kubernetes.io/dockerconfigjson \
    .dockerconfigjson=GHCR_DOCKER_CONFIG \
    | "$KUBECTL" apply -f -
  unset GHCR_AUTH GHCR_DOCKER_CONFIG
  echo "OK: ghcr-pull 적용 완료"
  # 네임스페이스 default SA에 부착 → 전 파드가 imagePullSecret 상속(멀티노드 정석, Q83-6).
  "$KUBECTL" patch serviceaccount default -n "$NS" \
    -p '{"imagePullSecrets":[{"name":"ghcr-pull"}]}'
  echo "OK: default SA에 ghcr-pull 부착 완료"
else
  echo "SKIP: GHCR_PAT 미지정 — ghcr-pull 시크릿은 생성하지 않음 (앱 이미지 pull 불가 상태)"
fi

# 웜업 전용 — PostSync Job이 로그인해 임시 토큰을 발급받는 테스트 계정.
# 미지정이면 생성하지 않는다(웜업 Job은 REQUIRE_AUTH=0 으로 인증 대상만 스킵). 필수 키
# 검증 루프에 넣지 않는다 — 기존 운영자 .env가 즉시 실패하지 않도록 GHCR/Grafana와 동일한
# 선택적 분기로 둔다.
if [ -n "${WARMUP_EMAIL:-}" ] && [ -n "${WARMUP_PASSWORD:-}" ]; then
  render_secret_manifest warmup-secrets "$NS" Opaque WARMUP_EMAIL WARMUP_PASSWORD \
    | "$KUBECTL" apply -f -
  echo "OK: warmup-secrets 적용 완료 (namespace=$NS)"
else
  echo "SKIP: WARMUP_EMAIL/WARMUP_PASSWORD 미지정 — 웜업 인증 대상은 스킵됨"
fi

# WS-C(7/10 observability plan) — postgres_exporter용 Secret. openat의 app-secrets는
# cross-namespace 참조가 안 되므로 observability 네임스페이스에 동일 값으로 복제.
# DB_USER/DB_PASSWORD를 URI에 그대로 넣으면 '%' 등 특수문자가 있을 때 postgres_exporter의
# DSN 파서가 잘못된 percent-encoding으로 오인해 파싱 실패(2026-07-12 실측 발견) — 반드시
# URL 인코딩 후 삽입.
DB_USER_ENC=$(printf '%s' "$DB_USER" \
  | python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.stdin.read(), safe=''), end='')")
DB_PASSWORD_ENC=$(printf '%s' "$DB_PASSWORD" \
  | python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.stdin.read(), safe=''), end='')")
export -n DB_USER_ENC DB_PASSWORD_ENC 2>/dev/null || true
DATA_SOURCE_NAME="postgresql://${DB_USER_ENC}:${DB_PASSWORD_ENC}@postgres.openat.svc.cluster.local:5432/openat?sslmode=disable"
export -n DATA_SOURCE_NAME 2>/dev/null || true
"$KUBECTL" create namespace observability --dry-run=client -o yaml | "$KUBECTL" apply -f -
render_secret_manifest pg-exporter-secret observability Opaque DATA_SOURCE_NAME \
  | "$KUBECTL" apply -f -
unset DATA_SOURCE_NAME DB_USER_ENC DB_PASSWORD_ENC
echo "OK: pg-exporter-secret 적용 완료 (namespace=observability)"

# WS-D — Grafana admin 자격증명. 미지정 시 랜덤 생성(하드코딩 금지 — 실패는 시끄럽게).
# 멱등 처리 근거: 과거엔 미지정 시 매 실행마다 openssl rand로 새 비밀번호를 생성해 Secret을
# 덮어썼는데, CD가 돌 때마다 Secret이 회전되어 이미 기동된 grafana 파드의 env(기동 시점 주입)와
# 어긋나 admin 로그인이 계속 깨졌다(2026-07-20 실측: Secret 해시와 파드 env 해시 불일치).
# 따라서 명시 지정이 없고 Secret이 이미 있으면 회전하지 않고 그대로 둔다.
if [ -n "${GRAFANA_ADMIN_PASSWORD:-}" ]; then
  # 명시 지정 — 의도적 회전 허용(현행대로 apply).
  GRAFANA_ADMIN_USER="${GRAFANA_ADMIN_USER:-admin}"
  export -n GRAFANA_ADMIN_USER GRAFANA_ADMIN_PASSWORD 2>/dev/null || true
  render_secret_manifest grafana-admin observability Opaque \
    admin-user=GRAFANA_ADMIN_USER admin-password=GRAFANA_ADMIN_PASSWORD \
    | "$KUBECTL" apply -f -
  echo "OK: grafana-admin 적용 완료 (namespace=observability, admin-user=${GRAFANA_ADMIN_USER:-admin})"
elif "$KUBECTL" get secret grafana-admin -n observability >/dev/null 2>&1; then
  # 미지정 + 이미 존재 — 회전 금지(파드 env와의 불일치 방지).
  echo "SKIP: grafana-admin 이미 존재 — 회전 안 함(명시 회전은 GRAFANA_ADMIN_PASSWORD 지정)"
else
  # 미지정 + 부재 — 최초 랜덤 생성.
  GRAFANA_ADMIN_PASSWORD="$(openssl rand -base64 18)"
  GRAFANA_ADMIN_USER="${GRAFANA_ADMIN_USER:-admin}"
  export -n GRAFANA_ADMIN_USER GRAFANA_ADMIN_PASSWORD 2>/dev/null || true
  render_secret_manifest grafana-admin observability Opaque \
    admin-user=GRAFANA_ADMIN_USER admin-password=GRAFANA_ADMIN_PASSWORD \
    | "$KUBECTL" apply -f -
  echo "OK: grafana-admin 최초 생성 완료 (namespace=observability, admin-user=${GRAFANA_ADMIN_USER:-admin})"
fi

unset DB_USER DB_PASSWORD AI_QUERY_DB_PASSWORD JWT_KEY_ID JWT_PRIVATE_KEY JWT_PUBLIC_KEY \
  PG_CLIENT_KEY PG_SECRET_KEY PAYMENT_FIELD_ENCRYPTION_KEY OPENAI_API_KEY \
  CHAT_INFERENCE_API_KEY GHCR_USER GHCR_PAT WARMUP_EMAIL WARMUP_PASSWORD \
  GRAFANA_ADMIN_USER GRAFANA_ADMIN_PASSWORD

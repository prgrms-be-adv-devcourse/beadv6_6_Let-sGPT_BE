#!/usr/bin/env bash

# 코드·다른 도메인 Secret을 건드리지 않고 AI 조회 자격증명만 회전 준비한다.
set -euo pipefail
umask 077

ENV_FILE="${1:?사용법: $0 <.env 경로>}"
NS="${NS:-openat}"
KUBECTL="${KUBECTL:-kubectl}"
export -n DB_USER DB_PASSWORD AI_QUERY_DB_PASSWORD JWT_KEY_ID JWT_PRIVATE_KEY JWT_PUBLIC_KEY \
  PG_CLIENT_KEY PG_SECRET_KEY PAYMENT_FIELD_ENCRYPTION_KEY OPENAI_API_KEY \
  CHAT_INFERENCE_API_KEY GHCR_USER GHCR_PAT WARMUP_EMAIL WARMUP_PASSWORD \
  GRAFANA_ADMIN_USER GRAFANA_ADMIN_PASSWORD 2>/dev/null || true
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=secret-manifest.sh
. "$SCRIPT_DIR/secret-manifest.sh"

rotation_may_be_active=false
rotation_published=false

restore_unpublished_ai_rotation() {
  local exit_status=$? restored_rv rollback_restored marker_restored
  trap - ERR INT TERM
  set +e
  [ "$exit_status" -ne 0 ] || exit_status=1
  if [ "$rotation_may_be_active" = true ] && [ "$rotation_published" != true ]; then
    echo "ERROR: AI 조회 회전 marker 확정 전에 실패해 active Secret을 직전 값으로 복구합니다." >&2
    restored_rv=$(render_secret_manifest ai-query-secrets "$NS" Opaque \
      AI_QUERY_DB_PASSWORD=AI_QUERY_DB_PREVIOUS_PASSWORD | "$KUBECTL" apply -f - \
      -o jsonpath='{.metadata.resourceVersion}')
    if [ $? -ne 0 ] || [ -z "$restored_rv" ]; then
      echo "CRITICAL: active AI 조회 Secret 자동 복구에 실패했습니다." >&2
      exit "$exit_status"
    fi

    rollback_restored=false
    render_secret_manifest ai-query-rollback-secrets "$NS" Opaque \
      AI_QUERY_DB_PREVIOUS_PASSWORD | "$KUBECTL" apply -f - >/dev/null \
      && rollback_restored=true
    marker_restored=false
    "$KUBECTL" annotate secret ai-query-rollback-secrets -n "$NS" \
      openat.io/rotation-run-id=steady \
      openat.io/target-active-resource-version="$restored_rv" \
      openat.io/target-deploy-revision=steady \
      openat.io/rotation-started-at- --overwrite >/dev/null \
      && marker_restored=true
    if [ "$rollback_restored" != true ] || [ "$marker_restored" != true ]; then
      echo "CRITICAL: rollback AI 조회 Secret을 steady 상태로 되돌리지 못했습니다." >&2
    fi
  fi
  exit "$exit_status"
}

ensure_argocd_idle() {
  local operation_phase
  operation_phase=$("$KUBECTL" -n argocd get application openat \
    -o jsonpath='{.status.operationState.phase}') || {
    echo "ERROR: ArgoCD 작업 상태를 읽지 못해 AI 조회 자격증명을 회전하지 않습니다." >&2
    return 1
  }
  case "$operation_phase" in
    Running|Terminating)
      echo "ERROR: 이전 ArgoCD 작업이 실행 중이라 AI 조회 자격증명을 회전하지 않습니다." >&2
      return 1
      ;;
  esac
}

[ -f "$ENV_FILE" ] || { echo "ERROR: $ENV_FILE 없음" >&2; exit 1; }

# shellcheck disable=SC1090
. "$ENV_FILE"
for key in DB_USER DB_PASSWORD AI_QUERY_DB_PASSWORD JWT_KEY_ID JWT_PRIVATE_KEY JWT_PUBLIC_KEY \
  PG_CLIENT_KEY PG_SECRET_KEY PAYMENT_FIELD_ENCRYPTION_KEY OPENAI_API_KEY \
  CHAT_INFERENCE_API_KEY GHCR_USER GHCR_PAT WARMUP_EMAIL WARMUP_PASSWORD \
  GRAFANA_ADMIN_USER GRAFANA_ADMIN_PASSWORD; do
  export -n "$key" 2>/dev/null || true
done

[ -n "${AI_QUERY_DB_PASSWORD:-}" ] || {
  echo "ERROR: $ENV_FILE 에 AI_QUERY_DB_PASSWORD 없음/빈값" >&2
  exit 1
}
case "${AI_QUERY_ROTATION_RUN_ID:-}" in
  ''|*[!A-Za-z0-9._-]*)
    echo "ERROR: AI_QUERY_ROTATION_RUN_ID가 없거나 형식이 올바르지 않습니다." >&2
    exit 1
    ;;
esac

active_snapshot=$("$KUBECTL" get secret ai-query-secrets -n "$NS" \
  -o jsonpath='{.metadata.name}{"|"}{.data.AI_QUERY_DB_PASSWORD}{"|"}{.metadata.resourceVersion}') || {
  echo "ERROR: 기존 ai-query-secrets를 읽지 못해 수동 회전을 시작할 수 없습니다." >&2
  exit 1
}
IFS='|' read -r active_name previous_encoded previous_active_rv <<<"$active_snapshot"
[ "$active_name" = ai-query-secrets ] \
  && [ -n "$previous_encoded" ] \
  && [ -n "$previous_active_rv" ] || {
  echo "ERROR: 기존 ai-query-secrets의 비밀번호 key 또는 resourceVersion이 비어 있습니다." >&2
  exit 1
}
if ! AI_QUERY_DB_PREVIOUS_PASSWORD=$(printf '%s' "$previous_encoded" | base64 --decode); then
  echo "ERROR: 기존 AI 조회 비밀번호를 해석하지 못했습니다." >&2
  exit 1
fi
export -n AI_QUERY_DB_PREVIOUS_PASSWORD 2>/dev/null || true
[ -n "$AI_QUERY_DB_PREVIOUS_PASSWORD" ] || {
  echo "ERROR: 기존 AI 조회 비밀번호가 비어 있습니다." >&2
  exit 1
}
[ "$AI_QUERY_DB_PREVIOUS_PASSWORD" != "$AI_QUERY_DB_PASSWORD" ] || {
  echo "ERROR: 새 AI 조회 비밀번호가 현재 active 값과 같아 회전하지 않습니다." >&2
  exit 1
}

rollback_metadata=$("$KUBECTL" get secret ai-query-rollback-secrets -n "$NS" \
  --ignore-not-found \
  -o jsonpath='{.metadata.name}{"|"}{.metadata.annotations.openat\.io/rotation-run-id}{"|"}{.metadata.annotations.openat\.io/target-active-resource-version}{"|"}{.metadata.annotations.openat\.io/target-deploy-revision}') || {
  echo "ERROR: AI 조회 rollback marker를 읽지 못했습니다." >&2
  exit 1
}
IFS='|' read -r rollback_name marker_run_id marker_active_rv marker_revision <<<"$rollback_metadata"
if [ "$rollback_name" != ai-query-rollback-secrets ] \
  || [ "$marker_run_id" != steady ] \
  || [ "$marker_active_rv" != "$previous_active_rv" ] \
  || [ "$marker_revision" != steady ]; then
  echo "ERROR: 이전 AI 조회 회전 marker가 steady가 아니어서 새 회전을 시작하지 않습니다." >&2
  exit 1
fi

ensure_argocd_idle
rotation_started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# 신규 로그인 검증 실패 시 적용 Job이 되돌릴 수 있도록 직전 값과 회전 identity를 함께 보관한다.
rotation_may_be_active=true
trap restore_unpublished_ai_rotation ERR INT TERM
render_secret_manifest ai-query-rollback-secrets "$NS" Opaque AI_QUERY_DB_PREVIOUS_PASSWORD \
  | "$KUBECTL" apply -f - >/dev/null
"$KUBECTL" annotate secret ai-query-rollback-secrets -n "$NS" \
  openat.io/rotation-run-id="$AI_QUERY_ROTATION_RUN_ID" \
  openat.io/target-active-resource-version=pending \
  openat.io/target-deploy-revision=pending \
  openat.io/rotation-started-at="$rotation_started_at" --overwrite >/dev/null

active_resource_version=$(render_secret_manifest ai-query-secrets "$NS" Opaque \
  AI_QUERY_DB_PASSWORD | "$KUBECTL" apply -f - \
  -o jsonpath='{.metadata.resourceVersion}')
[ -n "$active_resource_version" ] || {
  echo "ERROR: 적용된 ai-query-secrets resourceVersion이 비어 있습니다." >&2
  false
}
"$KUBECTL" annotate secret ai-query-rollback-secrets -n "$NS" \
  openat.io/target-active-resource-version="$active_resource_version" --overwrite >/dev/null
rotation_published=true
trap - ERR INT TERM

echo "AI_QUERY_ROTATION_STARTED=true"
echo "AI_QUERY_RECONCILE_REQUIRED=true"
echo "ACTIVE_RESOURCE_VERSION=$active_resource_version"
unset AI_QUERY_DB_PREVIOUS_PASSWORD AI_QUERY_DB_PASSWORD previous_encoded active_resource_version \
  active_snapshot active_name previous_active_rv rollback_metadata rollback_name marker_run_id \
  marker_active_rv marker_revision rotation_started_at
echo "OK: AI 조회 자격증명 회전 준비 완료"

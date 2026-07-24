#!/usr/bin/env bash

# AI 조회 자격증명 회전 뒤 Kubernetes Secret을 DB의 검증된 상태와 맞춘다.
set -euo pipefail

MODE="${1:?사용법: $0 <success|failure> <target revision 또는 빈값> <run id>}"
TARGET_REVISION="${2:-}"
RUN_ID="${3:?run id가 필요합니다}"
NS="${NS:-openat}"
KUBECTL="${KUBECTL:-kubectl}"
GIT="${GIT:-git}"
RECONCILE_TIMEOUT_SECONDS="${RECONCILE_TIMEOUT_SECONDS:-240}"
RECONCILE_POLL_SECONDS="${RECONCILE_POLL_SECONDS:-5}"
ROLLBACK_SECRET=ai-query-rollback-secrets
ACTIVE_SECRET=ai-query-secrets
READ_MODEL_JOB=ai-read-model-apply
export -n DB_USER DB_PASSWORD AI_QUERY_DB_PASSWORD JWT_KEY_ID JWT_PRIVATE_KEY JWT_PUBLIC_KEY \
  PG_CLIENT_KEY PG_SECRET_KEY PAYMENT_FIELD_ENCRYPTION_KEY OPENAI_API_KEY \
  CHAT_INFERENCE_API_KEY GHCR_USER GHCR_PAT WARMUP_EMAIL WARMUP_PASSWORD \
  GRAFANA_ADMIN_USER GRAFANA_ADMIN_PASSWORD 2>/dev/null || true
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=secret-manifest.sh
. "$SCRIPT_DIR/secret-manifest.sh"
# shellcheck source=prove-ai-query-rollout.sh
. "$SCRIPT_DIR/prove-ai-query-rollout.sh"

case "$MODE" in
  success|failure) ;;
  *) echo "ERROR: 지원하지 않는 reconcile mode: $MODE" >&2; exit 1 ;;
esac
case "$RUN_ID" in
  ''|*[!A-Za-z0-9._-]*) echo "ERROR: run id 형식이 올바르지 않습니다." >&2; exit 1 ;;
esac
case "$RECONCILE_TIMEOUT_SECONDS:$RECONCILE_POLL_SECONDS" in
  *[!0-9:]*|:*|*:) echo "ERROR: reconcile timeout/poll 값이 올바르지 않습니다." >&2; exit 1 ;;
esac

to_epoch() {
  date -u -d "$1" +%s 2>/dev/null
}

metadata=$("$KUBECTL" get secret "$ROLLBACK_SECRET" -n "$NS" --ignore-not-found \
  -o jsonpath='{.metadata.name}{"|"}{.metadata.annotations.openat\.io/rotation-run-id}{"|"}{.metadata.annotations.openat\.io/target-active-resource-version}{"|"}{.metadata.annotations.openat\.io/target-deploy-revision}{"|"}{.metadata.annotations.openat\.io/rotation-started-at}') || {
  echo "ERROR: AI 조회 rollback Secret 상태를 읽지 못했습니다." >&2
  exit 1
}
if [ -z "$metadata" ]; then
  echo "ERROR: 현재 실행이 소유한 AI 조회 rollback Secret이 없습니다." >&2
  exit 1
fi

IFS='|' read -r secret_name marker_run_id target_active_rv marker_target_revision rotation_started_at <<<"$metadata"
[ "$secret_name" = "$ROLLBACK_SECRET" ] \
  && [ "$marker_run_id" = "$RUN_ID" ] \
  && [ -n "$target_active_rv" ] \
  && [ "$target_active_rv" != pending ] \
  && [ -n "$rotation_started_at" ] || {
  echo "ERROR: rollback Secret의 회전 identity가 현재 실행과 일치하지 않습니다." >&2
  exit 1
}
rotation_started_epoch=$(to_epoch "$rotation_started_at") || {
  echo "ERROR: rollback Secret의 회전 시작 시각을 해석하지 못했습니다." >&2
  exit 1
}

current_active_rv=$("$KUBECTL" get secret "$ACTIVE_SECRET" -n "$NS" \
  -o jsonpath='{.metadata.resourceVersion}') || {
  echo "ERROR: active AI 조회 Secret을 읽지 못했습니다." >&2
  exit 1
}
[ "$current_active_rv" = "$target_active_rv" ] || {
  echo "ERROR: active Secret이 회전 직후 관측 상태와 달라 자동 변경하지 않습니다." >&2
  exit 1
}

assert_rotation_identity() {
  local latest
  latest=$("$KUBECTL" get secret "$ROLLBACK_SECRET" -n "$NS" \
    -o jsonpath='{.metadata.annotations.openat\.io/rotation-run-id}{"|"}{.metadata.annotations.openat\.io/target-active-resource-version}{"|"}{.metadata.annotations.openat\.io/target-deploy-revision}{"|"}{.metadata.annotations.openat\.io/rotation-started-at}') || {
    echo "ERROR: Secret 변경 직전 rollback identity를 읽지 못했습니다." >&2
    return 1
  }
  [ "$latest" = "$RUN_ID|$target_active_rv|$marker_target_revision|$rotation_started_at" ] || {
    echo "ERROR: Secret 변경 직전 rollback identity가 바뀌었습니다." >&2
    return 1
  }
}

normalize_rollback_to_active() {
  local active_snapshot active_encoded observed_rv active_password normalized_rv
  assert_rotation_identity
  active_snapshot=$("$KUBECTL" get secret "$ACTIVE_SECRET" -n "$NS" \
    -o jsonpath='{.data.AI_QUERY_DB_PASSWORD}{"|"}{.metadata.resourceVersion}')
  active_encoded="${active_snapshot%%|*}"
  observed_rv="${active_snapshot##*|}"
  [ "$observed_rv" = "$target_active_rv" ] && [ -n "$active_encoded" ] || {
    echo "ERROR: 정규화 직전 active Secret이 바뀌었거나 비어 있습니다." >&2
    return 1
  }
  active_password=$(printf '%s' "$active_encoded" | base64 --decode) || {
    echo "ERROR: active AI 조회 비밀번호를 해석하지 못했습니다." >&2
    return 1
  }
  [ -n "$active_password" ] || {
    echo "ERROR: active AI 조회 비밀번호가 비어 있습니다." >&2
    return 1
  }
  render_secret_manifest "$ROLLBACK_SECRET" "$NS" Opaque \
    AI_QUERY_DB_PREVIOUS_PASSWORD=active_password \
    | "$KUBECTL" apply -f - >/dev/null
  normalized_rv=$("$KUBECTL" get secret "$ACTIVE_SECRET" -n "$NS" \
    -o jsonpath='{.metadata.resourceVersion}')
  [ "$normalized_rv" = "$target_active_rv" ] || {
    echo "ERROR: 정규화 도중 active Secret이 바뀌어 marker를 갱신하지 않습니다." >&2
    return 1
  }
  "$KUBECTL" annotate secret "$ROLLBACK_SECRET" -n "$NS" \
    openat.io/rotation-run-id=steady \
    openat.io/target-active-resource-version="$normalized_rv" \
    openat.io/target-deploy-revision=steady \
    openat.io/rotation-started-at- --overwrite >/dev/null
  unset active_snapshot active_encoded observed_rv active_password normalized_rv
  echo "OK: rollback Secret을 현재 active 값으로 정규화했습니다."
}

restore_previous_and_normalize() {
  local latest_active_rv previous_encoded previous_password restored_rv
  assert_rotation_identity
  latest_active_rv=$("$KUBECTL" get secret "$ACTIVE_SECRET" -n "$NS" \
    -o jsonpath='{.metadata.resourceVersion}')
  [ "$latest_active_rv" = "$target_active_rv" ] || {
    echo "ERROR: 복구 직전 active Secret이 바뀌어 자동 복구를 중단합니다." >&2
    return 1
  }
  previous_encoded=$("$KUBECTL" get secret "$ROLLBACK_SECRET" -n "$NS" \
    -o jsonpath='{.data.AI_QUERY_DB_PREVIOUS_PASSWORD}')
  [ -n "$previous_encoded" ] || {
    echo "ERROR: rollback 비밀번호가 비어 있습니다." >&2
    return 1
  }
  previous_password=$(printf '%s' "$previous_encoded" | base64 --decode) || {
    echo "ERROR: rollback 비밀번호를 해석하지 못했습니다." >&2
    return 1
  }
  [ -n "$previous_password" ] || {
    echo "ERROR: rollback 비밀번호가 비어 있습니다." >&2
    return 1
  }
  render_secret_manifest "$ACTIVE_SECRET" "$NS" Opaque \
    AI_QUERY_DB_PASSWORD=previous_password \
    | "$KUBECTL" apply -f - >/dev/null
  restored_rv=$("$KUBECTL" get secret "$ACTIVE_SECRET" -n "$NS" \
    -o jsonpath='{.metadata.resourceVersion}')
  [ -n "$restored_rv" ] || {
    echo "ERROR: 복구한 active Secret resourceVersion이 비어 있습니다." >&2
    return 1
  }
  render_secret_manifest "$ROLLBACK_SECRET" "$NS" Opaque \
    AI_QUERY_DB_PREVIOUS_PASSWORD=previous_password \
    | "$KUBECTL" apply -f - >/dev/null
  "$KUBECTL" annotate secret "$ROLLBACK_SECRET" -n "$NS" \
    openat.io/rotation-run-id=steady \
    openat.io/target-active-resource-version="$restored_rv" \
    openat.io/target-deploy-revision=steady \
    openat.io/rotation-started-at- --overwrite >/dev/null
  unset latest_active_rv previous_encoded previous_password restored_rv
  echo "OK: DB가 PREVIOUS임을 확인한 뒤 active/rollback Secret을 직전 값으로 맞췄습니다."
}

read_argo_operation() {
  "$KUBECTL" -n argocd get application openat \
    -o jsonpath='{.status.operationState.operation.sync.revision}{"|"}{.status.operationState.phase}{"|"}{.status.operationState.startedAt}{"|"}{.status.operationState.finishedAt}{"|"}{range .status.operationState.syncResult.resources[?(@.name=="ai-read-model-apply")]}{.kind}{","}{.name}{","}{.hookPhase}{end}'
}

parse_matching_operation() {
  local snapshot="$1"
  IFS='|' read -r operation_revision operation_phase operation_started_at operation_finished_at hook_identity <<<"$snapshot"
  [ "$operation_revision" = "$TARGET_REVISION" ] && [ -n "$operation_started_at" ] || return 1
  operation_started_epoch=$(to_epoch "$operation_started_at") || return 1
  [ "$operation_started_epoch" -ge "$rotation_started_epoch" ] || return 1
  operation_finished_epoch=
  if [ -n "$operation_finished_at" ]; then
    operation_finished_epoch=$(to_epoch "$operation_finished_at") || return 1
    [ "$operation_finished_epoch" -ge "$operation_started_epoch" ] || return 1
  fi
  case "$operation_phase" in
    Succeeded|Failed|Error) [ -n "$operation_finished_epoch" ] || return 1 ;;
  esac
}

verify_target_job() {
  local job_snapshot job_name job_uid job_created_at deployment_run_id rotation_identity
  local job_active_rv workload_revision job_created_epoch
  IFS=',' read -r hook_kind hook_name hook_phase <<<"$hook_identity"
  [ "$hook_kind" = Job ] && [ "$hook_name" = "$READ_MODEL_JOB" ] || {
    printf 'UNKNOWN'
    return
  }
  job_snapshot=$("$KUBECTL" get job "$READ_MODEL_JOB" -n "$NS" --ignore-not-found \
    -o jsonpath='{.metadata.name}{"|"}{.metadata.uid}{"|"}{.metadata.creationTimestamp}{"|"}{.metadata.annotations.openat\.io/deployment-run-id}{"|"}{.metadata.annotations.openat\.io/rotation-identity}{"|"}{.metadata.annotations.openat\.io/target-active-resource-version-identity}{"|"}{.metadata.annotations.openat\.io/target-workload-revision}') || {
    printf 'UNKNOWN'
    return
  }
  [ -n "$job_snapshot" ] || {
    # Argo가 Hook을 기록했는데 Job이 없으면 TTL/수동 삭제 가능성이 있어 과거 pod 상태를 신뢰하지 않는다.
    printf 'ABSENT'
    return
  }
  IFS='|' read -r job_name job_uid job_created_at deployment_run_id rotation_identity \
    job_active_rv workload_revision <<<"$job_snapshot"
  [ "$job_name" = "$READ_MODEL_JOB" ] && [ -n "$job_uid" ] && [ -n "$job_created_at" ] || {
    printf 'UNKNOWN'
    return
  }
  job_created_epoch=$(to_epoch "$job_created_at") || {
    printf 'UNKNOWN'
    return
  }
  [ "$job_created_epoch" -ge "$operation_started_epoch" ] \
    && [ "$job_created_epoch" -ge "$rotation_started_epoch" ] || {
    printf 'UNKNOWN'
    return
  }
  if [ -n "${operation_finished_epoch:-}" ] \
    && [ "$job_created_epoch" -gt "$operation_finished_epoch" ]; then
    printf 'UNKNOWN'
    return
  fi

  if [ "$deployment_run_id|$rotation_identity|$job_active_rv" \
    != "$RUN_ID|$RUN_ID|$target_active_rv" ]; then
    printf 'UNKNOWN'
    return
  fi
  case "$workload_revision" in
    *[!0-9a-f]*|'') printf 'UNKNOWN'; return ;;
  esac
  [ "${#workload_revision}" -eq 40 ] \
    && "$GIT" cat-file -e "${workload_revision}^{commit}" \
    && "$GIT" merge-base --is-ancestor "$workload_revision" "$TARGET_REVISION" || {
    printf 'UNKNOWN'
    return
  }
  printf 'BOUND|%s|%s' "$job_uid" "$job_created_epoch"
}

db_state_for_bound_job() {
  local binding="$1" binding_status job_uid job_created_epoch pod_names latest_pod pod_snapshot
  local pod_name owner_uid pod_created_at message running_started terminated_started pod_created_epoch
  IFS='|' read -r binding_status job_uid job_created_epoch <<<"$binding"
  [ "$binding_status" = BOUND ] || {
    printf 'UNKNOWN'
    return
  }
  pod_names=$("$KUBECTL" get pods -n "$NS" \
    -l "batch.kubernetes.io/controller-uid=$job_uid" \
    --sort-by=.metadata.creationTimestamp \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}') || {
    printf 'UNKNOWN'
    return
  }
  latest_pod=$(printf '%s\n' "$pod_names" | sed '/^$/d' | tail -1)
  [ -n "$latest_pod" ] || {
    # Job이 생성된 뒤 pod가 사라진 경우는 미실행과 TTL/수동 삭제를 구분할 수 없다.
    printf 'UNKNOWN'
    return
  }
  pod_snapshot=$("$KUBECTL" get pod "$latest_pod" -n "$NS" \
    -o jsonpath='{.metadata.name}{"|"}{.metadata.ownerReferences[?(@.kind=="Job")].uid}{"|"}{.metadata.creationTimestamp}{"|"}{.status.containerStatuses[?(@.name=="apply")].state.terminated.message}{"|"}{.status.containerStatuses[?(@.name=="apply")].state.running.startedAt}{"|"}{.status.containerStatuses[?(@.name=="apply")].state.terminated.startedAt}') || {
    printf 'UNKNOWN'
    return
  }
  IFS='|' read -r pod_name owner_uid pod_created_at message running_started terminated_started <<<"$pod_snapshot"
  [ "$pod_name" = "$latest_pod" ] && [ "$owner_uid" = "$job_uid" ] && [ -n "$pod_created_at" ] || {
    printf 'UNKNOWN'
    return
  }
  pod_created_epoch=$(to_epoch "$pod_created_at") || {
    printf 'UNKNOWN'
    return
  }
  [ "$pod_created_epoch" -ge "$job_created_epoch" ] || {
    printf 'UNKNOWN'
    return
  }
  case "$message" in
    DB_STATE=PREVIOUS) printf 'PREVIOUS' ;;
    DB_STATE=NEW) printf 'NEW' ;;
    DB_STATE=UNKNOWN) printf 'UNKNOWN' ;;
    *) printf 'UNKNOWN' ;;
  esac
}

ai_rollout_consumes_target() {
  local deployment_state
  deployment_state=$("$KUBECTL" get deployment ai -n "$NS" \
    -o jsonpath='{.metadata.uid}{"|"}{.spec.template.metadata.annotations.openat\.io/ai-query-secret-revision}{"|"}{.metadata.generation}{"|"}{.status.observedGeneration}{"|"}{.spec.replicas}{"|"}{.status.updatedReplicas}{"|"}{.status.readyReplicas}{"|"}{.status.availableReplicas}{"|"}{.status.unavailableReplicas}') || return 1
  prove_ai_rollout "$deployment_state" "$target_active_rv" >/dev/null
}

if [ -n "$TARGET_REVISION" ] && [ "$marker_target_revision" != pending ] \
  && [ "$TARGET_REVISION" != "$marker_target_revision" ]; then
  echo "ERROR: workflow와 rollback Secret의 대상 revision이 달라 자동 변경하지 않습니다." >&2
  exit 1
fi
if [ -z "$TARGET_REVISION" ]; then
  TARGET_REVISION="$marker_target_revision"
fi

if [ -z "$TARGET_REVISION" ] || [ "$TARGET_REVISION" = pending ]; then
  argo_snapshot=$(read_argo_operation) || {
    echo "ERROR: ArgoCD 상태를 읽지 못해 DB 상태를 UNKNOWN으로 유지합니다." >&2
    exit 1
  }
  IFS='|' read -r operation_revision operation_phase operation_started_at _ _ <<<"$argo_snapshot"
  case "$operation_phase" in
    Running|Terminating)
      echo "ERROR: 대상 revision 기록 전 ArgoCD 작업이 실행 중이라 DB 상태를 UNKNOWN으로 유지합니다." >&2
      exit 1
      ;;
  esac
  if [ -n "$operation_started_at" ]; then
    operation_started_epoch=$(to_epoch "$operation_started_at") || {
      echo "ERROR: ArgoCD 작업 시작 시각을 해석하지 못해 DB 상태를 UNKNOWN으로 유지합니다." >&2
      exit 1
    }
    if [ "$operation_started_epoch" -ge "$rotation_started_epoch" ]; then
      echo "ERROR: 회전 뒤 ArgoCD 작업 이력이 있어 DB 상태를 UNKNOWN으로 유지합니다." >&2
      exit 1
    fi
  fi
  restore_previous_and_normalize
  exit 0
fi

case "$TARGET_REVISION" in
  *[!0-9a-f]*|'') echo "ERROR: 대상 deploy revision 형식이 올바르지 않습니다." >&2; exit 1 ;;
esac
[ "${#TARGET_REVISION}" -eq 40 ] || {
  echo "ERROR: 대상 deploy revision 길이가 올바르지 않습니다." >&2
  exit 1
}
[ "$marker_target_revision" = "$TARGET_REVISION" ] || {
  echo "ERROR: rollback Secret에 대상 deploy revision이 확정되지 않았습니다." >&2
  exit 1
}

remote_ref=$("$GIT" ls-remote --exit-code origin refs/heads/deploy/state) || {
  echo "ERROR: deploy/state 원격 revision을 확인하지 못해 Secret을 변경하지 않습니다." >&2
  exit 1
}
remote_revision="${remote_ref%%[[:space:]]*}"
case "$remote_revision" in
  *[!0-9a-f]*|'') echo "ERROR: 원격 deploy/state revision 형식이 올바르지 않습니다." >&2; exit 1 ;;
esac
if [ "$remote_revision" != "$TARGET_REVISION" ]; then
  "$GIT" cat-file -e "${remote_revision}^{commit}" \
    && "$GIT" cat-file -e "${TARGET_REVISION}^{commit}" || {
    echo "ERROR: 원격/대상 deploy revision 이력을 확인하지 못했습니다." >&2
    exit 1
  }
  if "$GIT" merge-base --is-ancestor "$remote_revision" "$TARGET_REVISION"; then
    argo_revisions=$("$KUBECTL" -n argocd get application openat \
      -o jsonpath='{.status.sync.revision}{"|"}{.status.operationState.operation.sync.revision}') || {
      echo "ERROR: ArgoCD revision을 읽지 못해 DB 상태를 UNKNOWN으로 유지합니다." >&2
      exit 1
    }
    if [ "${argo_revisions%%|*}" != "$TARGET_REVISION" ] \
      && [ "${argo_revisions##*|}" != "$TARGET_REVISION" ]; then
      echo "INFO: 대상 revision이 원격이나 ArgoCD에 게시되지 않아 직전 값으로 복구합니다."
      restore_previous_and_normalize
      exit 0
    fi
  elif ! "$GIT" merge-base --is-ancestor "$TARGET_REVISION" "$remote_revision"; then
    echo "ERROR: 원격 deploy/state와 대상 revision이 분기되어 Secret을 변경하지 않습니다." >&2
    exit 1
  fi
fi

deadline=$((SECONDS + RECONCILE_TIMEOUT_SECONDS))
while [ "$SECONDS" -le "$deadline" ]; do
  argo_snapshot=$(read_argo_operation) || {
    echo "ERROR: ArgoCD API 오류로 DB 상태를 UNKNOWN으로 유지합니다." >&2
    exit 1
  }
  if ! parse_matching_operation "$argo_snapshot"; then
    if [ "$MODE" = success ]; then
      echo "ERROR: 성공 처리 대상 ArgoCD operation identity가 회전과 일치하지 않습니다." >&2
      exit 1
    fi
  else
    IFS=',' read -r hook_kind hook_name hook_phase <<<"$hook_identity"
    if [ "$hook_kind" = Job ] && [ "$hook_name" = "$READ_MODEL_JOB" ]; then
      case "$hook_phase" in
        Succeeded)
          job_binding=$(verify_target_job)
          case "$job_binding" in
            BOUND\|*) ;;
            *)
              echo "ERROR: Succeeded Hook의 현재 target Job identity를 결박하지 못해 UNKNOWN으로 유지합니다." >&2
              exit 1
              ;;
          esac
          if ! ai_rollout_consumes_target; then
            echo "ERROR: AI rollout이 target active resourceVersion을 소비했음을 증명하지 못해 UNKNOWN으로 유지합니다." >&2
            exit 1
          fi
          normalize_rollback_to_active
          exit 0
          ;;
        Failed|Error)
          if [ "$MODE" != failure ]; then
            echo "ERROR: 성공 처리 대상 Hook이 실패 상태라 Secret을 변경하지 않습니다." >&2
            exit 1
          fi
          job_binding=$(verify_target_job)
          db_state=$(db_state_for_bound_job "$job_binding")
          case "$db_state" in
            PREVIOUS) restore_previous_and_normalize; exit 0 ;;
            NEW) normalize_rollback_to_active; exit 0 ;;
            *)
              echo "ERROR: 현재 target Job/Pod의 DB 상태가 UNKNOWN이라 active/rollback Secret을 변경하지 않습니다." >&2
              exit 1
              ;;
          esac
          ;;
      esac
    elif [ "$MODE" = failure ] && [ -z "$hook_identity" ] \
      && { [ "$operation_phase" = Failed ] || [ "$operation_phase" = Error ]; }; then
      echo "ERROR: target Hook 실행 흔적이 없어 DB 상태를 UNKNOWN으로 유지합니다." >&2
      exit 1
    fi

    if [ "$MODE" = success ] && [ "$operation_phase" != Running ]; then
      echo "ERROR: 성공 처리 대상 Hook이 Succeeded가 아니어서 Secret을 변경하지 않습니다." >&2
      exit 1
    fi
    if [ "$MODE" = failure ] && [ "$operation_phase" = Failed -o "$operation_phase" = Error ]; then
      echo "ERROR: terminal ArgoCD 작업의 read-model 상태를 증명하지 못해 UNKNOWN으로 유지합니다." >&2
      exit 1
    fi
  fi

  [ "$RECONCILE_POLL_SECONDS" -gt 0 ] && sleep "$RECONCILE_POLL_SECONDS"
done

echo "ERROR: read-model Hook의 terminal DB 상태를 확인하지 못해 active/rollback Secret을 변경하지 않습니다." >&2
exit 1

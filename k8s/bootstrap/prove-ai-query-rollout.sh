#!/usr/bin/env bash

# AI Deployment가 대상 Secret revision을 소비한 최신 ReplicaSet과 Ready Pod로 수렴했는지 증명한다.
# 성공 시 "<target ReplicaSet UID>|<Ready target pod count>"를 stdout으로 반환한다.
prove_ai_rollout() {
  local deployment_state="$1" expected_rv="$2"
  local kubectl_command="${KUBECTL:-kubectl}" namespace="${NS:-openat}"
  local deployment_uid template_rv generation observed replicas
  local updated ready available unavailable numeric_value replica_set_states selected_rs
  local latest_rs_revision
  local target_rs_name target_rs_uid target_rs_count rs_name rs_uid rs_owner_kind
  local rs_owner_uid rs_controller rs_revision rs_rv rs_replicas rs_ready rs_available
  local pod_states ready_target_pods pod_owner_kind pod_owner_uid pod_controller pod_rv
  local pod_deleted pod_phase pod_ready deployment_state_after selected_rs_after

  IFS='|' read -r deployment_uid template_rv generation observed replicas updated ready \
    available unavailable <<<"$deployment_state"
  [ -n "$deployment_uid" ] || return 1
  for numeric_value in "$generation" "$observed" "$replicas" "$updated" "$ready" \
    "$available" "${unavailable:-0}"; do
    case "$numeric_value" in
      ''|*[!0-9]*) return 1 ;;
    esac
  done
  [ "$template_rv" = "$expected_rv" ] \
    && [ "$generation" = "$observed" ] \
    && [ "$replicas" -gt 0 ] \
    && [ "$updated" = "$replicas" ] \
    && [ "$ready" = "$replicas" ] \
    && [ "$available" = "$replicas" ] \
    && [ "${unavailable:-0}" -eq 0 ] || return 1

  replica_set_states=$("$kubectl_command" get replicasets -n "$namespace" -l app=ai \
    -o jsonpath='{range .items[*]}{.metadata.name}{"|"}{.metadata.uid}{"|"}{.metadata.ownerReferences[?(@.controller==true)].kind}{"|"}{.metadata.ownerReferences[?(@.controller==true)].uid}{"|"}{.metadata.ownerReferences[?(@.controller==true)].controller}{"|"}{.metadata.annotations.deployment\.kubernetes\.io/revision}{"|"}{.spec.template.metadata.annotations.openat\.io/ai-query-secret-revision}{"|"}{.spec.replicas}{"|"}{.status.readyReplicas}{"|"}{.status.availableReplicas}{"\n"}{end}' \
    2>/dev/null) || return 1
  latest_rs_revision=
  while IFS='|' read -r _ _ rs_owner_kind rs_owner_uid rs_controller rs_revision _; do
    if [ "$rs_owner_kind" = Deployment ] \
      && [ "$rs_owner_uid" = "$deployment_uid" ] \
      && [ "$rs_controller" = true ]; then
      case "$rs_revision" in
        ''|*[!0-9]*) return 1 ;;
      esac
      if [ -z "$latest_rs_revision" ] || [ "$rs_revision" -gt "$latest_rs_revision" ]; then
        latest_rs_revision="$rs_revision"
      fi
    fi
  done <<<"$replica_set_states"
  [ -n "$latest_rs_revision" ] || return 1

  selected_rs=
  target_rs_name=
  target_rs_uid=
  target_rs_count=0
  while IFS='|' read -r rs_name rs_uid rs_owner_kind rs_owner_uid rs_controller \
    rs_revision rs_rv rs_replicas rs_ready rs_available; do
    if [ -n "$rs_name" ] \
      && [ -n "$rs_uid" ] \
      && [ "$rs_owner_kind" = Deployment ] \
      && [ "$rs_owner_uid" = "$deployment_uid" ] \
      && [ "$rs_controller" = true ] \
      && [ "$rs_revision" = "$latest_rs_revision" ] \
      && [ "$rs_rv" = "$expected_rv" ] \
      && [ "$rs_replicas" = "$replicas" ] \
      && [ "$rs_ready" = "$replicas" ] \
      && [ "$rs_available" = "$replicas" ]; then
      target_rs_name="$rs_name"
      target_rs_uid="$rs_uid"
      selected_rs="$rs_name|$rs_uid|$rs_owner_kind|$rs_owner_uid|$rs_controller|$rs_revision|$rs_rv|$rs_replicas|$rs_ready|$rs_available"
      target_rs_count=$((target_rs_count + 1))
    fi
  done <<<"$replica_set_states"
  [ "$target_rs_count" -eq 1 ] || return 1

  pod_states=$("$kubectl_command" get pods -n "$namespace" -l app=ai \
    -o jsonpath='{range .items[*]}{.metadata.ownerReferences[?(@.controller==true)].kind}{"|"}{.metadata.ownerReferences[?(@.controller==true)].uid}{"|"}{.metadata.ownerReferences[?(@.controller==true)].controller}{"|"}{.metadata.annotations.openat\.io/ai-query-secret-revision}{"|"}{.metadata.deletionTimestamp}{"|"}{.status.phase}{"|"}{range .status.conditions[?(@.type=="Ready")]}{.status}{end}{"\n"}{end}' \
    2>/dev/null) || return 1
  ready_target_pods=0
  while IFS='|' read -r pod_owner_kind pod_owner_uid pod_controller pod_rv \
    pod_deleted pod_phase pod_ready; do
    if [ "$pod_owner_kind" = ReplicaSet ] \
      && [ "$pod_owner_uid" = "$target_rs_uid" ] \
      && [ "$pod_controller" = true ] \
      && [ "$pod_rv" = "$expected_rv" ] \
      && [ -z "$pod_deleted" ] \
      && [ "$pod_phase" = Running ] \
      && [ "$pod_ready" = True ]; then
      ready_target_pods=$((ready_target_pods + 1))
    fi
  done <<<"$pod_states"
  [ "$ready_target_pods" -eq "$replicas" ] || return 1

  deployment_state_after=$("$kubectl_command" get deployment ai -n "$namespace" \
    -o jsonpath='{.metadata.uid}{"|"}{.spec.template.metadata.annotations.openat\.io/ai-query-secret-revision}{"|"}{.metadata.generation}{"|"}{.status.observedGeneration}{"|"}{.spec.replicas}{"|"}{.status.updatedReplicas}{"|"}{.status.readyReplicas}{"|"}{.status.availableReplicas}{"|"}{.status.unavailableReplicas}' \
    2>/dev/null) || return 1
  [ "$deployment_state_after" = "$deployment_state" ] || return 1
  selected_rs_after=$("$kubectl_command" get replicaset "$target_rs_name" -n "$namespace" \
    -o jsonpath='{.metadata.name}{"|"}{.metadata.uid}{"|"}{.metadata.ownerReferences[?(@.controller==true)].kind}{"|"}{.metadata.ownerReferences[?(@.controller==true)].uid}{"|"}{.metadata.ownerReferences[?(@.controller==true)].controller}{"|"}{.metadata.annotations.deployment\.kubernetes\.io/revision}{"|"}{.spec.template.metadata.annotations.openat\.io/ai-query-secret-revision}{"|"}{.spec.replicas}{"|"}{.status.readyReplicas}{"|"}{.status.availableReplicas}' \
    2>/dev/null) || return 1
  [ "$selected_rs_after" = "$selected_rs" ] || return 1
  printf '%s|%s' "$target_rs_uid" "$ready_target_pods"
}

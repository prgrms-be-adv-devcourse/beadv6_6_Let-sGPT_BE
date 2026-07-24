package com.openat.chat.infrastructure.persistence;

import static com.openat.chat.infrastructure.persistence.YamlDocuments.asMaps;
import static com.openat.chat.infrastructure.persistence.YamlDocuments.named;
import static com.openat.chat.infrastructure.persistence.YamlDocuments.value;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiReadModelDeploymentContractTest {

  @Test
  @DisplayName("CD는 배포를 유실하거나 오래된 이미지로 되돌리지 않는다")
  void deploymentWorkflow_serializesAndRejectsStaleRevisions() throws IOException {
    String workflow = read(".github", "workflows", "deploy.yml");
    Map<String, Object> document = YamlDocuments.parse(workflow).onlyDocument();
    Map<String, Object> deployJob = YamlDocuments.asMap(value(document, "jobs", "deploy"));
    List<Map<String, Object>> steps = asMaps(deployJob.get("steps"));
    Map<String, Object> triggerContext = step(workflow, "Resolve trigger context");
    Map<String, Object> triggerEnvironment = YamlDocuments.asMap(triggerContext.get("env"));
    String syncScript =
        (String) step(workflow, "Sync deploy/state (merge main + pin changed images)").get("run");

    assertThat(value(deployJob, "concurrency", "queue")).isEqualTo("max");
    assertThat(value(steps.getFirst(), "with", "ref"))
        .isEqualTo(
            "${{ github.event_name == 'workflow_run' && github.event.workflow_run.head_sha || 'deploy/state' }}");
    assertThat(triggerEnvironment)
        .containsEntry("FE_SEQUENCE_INPUT", "${{ github.event.client_payload.run_number }}")
        .containsEntry("FE_SHA_INPUT", "${{ github.event.client_payload.sha }}");
    assertThat(syncScript)
        .contains("git merge-base --is-ancestor \"$SHA\" \"$current_tag\"")
        .contains("frontend-image-sequence.txt")
        .contains("commit_staged")
        .doesNotContain("|| echo \"no image change to commit\"");
    assertThat(workflow)
        .contains("ruleset/branch protection")
        .contains("CD identity만")
        .doesNotContain("fe_sha=${{ github.event.client_payload.sha }}")
        .doesNotContain("cancel-in-progress: true");
  }

  @Test
  @DisplayName("운영 권한으로 실행하는 스크립트는 검증된 workflow source에서만 가져온다")
  void deploymentWorkflow_executesOnlyTrustedScriptsAndPreinstalledTools() throws IOException {
    String workflow = read(".github", "workflows", "deploy.yml");
    Map<String, Object> trustedCheckout = step(workflow, "Checkout trusted workflow source");
    String stagingScript = (String) step(workflow, "Stage trusted deployment scripts").get("run");
    String provisionScript =
        (String) step(workflow, "Provision k8s secrets (idempotent)").get("run");
    String convergenceScript = (String) step(workflow, "Wait for ArgoCD convergence").get("run");

    assertThat(value(trustedCheckout, "with", "ref").toString()).contains("github.workflow_sha");
    assertThat(value(trustedCheckout, "with", "path")).isEqualTo(".trusted-workflow-source");
    assertThat(stagingScript)
        .contains("git -C .trusted-workflow-source rev-parse HEAD")
        .contains("create-secrets.sh rotate-ai-query-secret.sh reconcile-ai-query-rotation.sh")
        .contains("secret-manifest.sh prove-ai-query-rollout.sh");
    assertThat(provisionScript)
        .contains("$RUNNER_TEMP/openat-deploy-scripts/create-secrets.sh")
        .contains("$RUNNER_TEMP/openat-deploy-scripts/rotate-ai-query-secret.sh");
    assertThat(convergenceScript)
        .contains("$RUNNER_TEMP/openat-deploy-scripts/prove-ai-query-rollout.sh");
    assertThat(workflow)
        .contains("$RUNNER_TEMP/openat-deploy-scripts/reconcile-ai-query-rotation.sh")
        .contains("command -v kustomize")
        .doesNotContain("bash ./k8s/bootstrap/create-secrets.sh")
        .doesNotContain("bash ./k8s/bootstrap/rotate-ai-query-secret.sh")
        .doesNotContain("raw.githubusercontent.com/kubernetes-sigs/kustomize/master")
        .doesNotContain("install_kustomize.sh");
  }

  @Test
  @DisplayName("read-model Hook은 기본 workload 뒤 단독 wave에서 immutable image와 배포 identity를 쓴다")
  void readModelHook_runsAfterDefaultWorkloadsAndBeforeAiWithBoundIdentity() throws IOException {
    Map<String, Object> queue =
        YamlDocuments.read("k8s", "base", "27-queue.yaml").document("Deployment", "queue");
    Map<String, Object> ai =
        YamlDocuments.read("k8s", "base", "28-ai.yaml").document("Deployment", "ai");
    Map<String, Object> hook =
        YamlDocuments.read("ai", "k8s", "read-model-job.yaml")
            .document("Job", "ai-read-model-apply");
    Map<String, Object> overlay =
        YamlDocuments.read("k8s", "overlay", "kustomization.yaml").onlyDocument();
    Map<String, Object> identity =
        YamlDocuments.read("k8s", "overlay", "ai-read-model-deployment-identity.yaml")
            .document("Job", "ai-read-model-apply");
    Map<String, Object> applyContainer =
        named(asMaps(value(hook, "spec", "template", "spec", "containers")), "apply");
    List<String> patchPaths =
        asMaps(overlay.get("patches")).stream().map(patch -> (String) patch.get("path")).toList();
    Map<String, Object> jobAnnotations =
        YamlDocuments.asMap(value(identity, "metadata", "annotations"));
    Map<String, Object> podLabels =
        YamlDocuments.asMap(value(identity, "spec", "template", "metadata", "labels"));
    Map<String, Object> podAnnotations =
        YamlDocuments.asMap(value(identity, "spec", "template", "metadata", "annotations"));

    assertThat(YamlDocuments.asMap(queue.get("metadata")).get("annotations")).isNull();
    assertThat(value(hook, "metadata", "annotations", "argocd.argoproj.io/sync-wave"))
        .isEqualTo("9");
    assertThat(applyContainer.get("image"))
        .isEqualTo(
            "postgres:16.14@sha256:da8cf245a60506e50a0a8cbb0f39c559ca622d92490605b67fcadc74ca1ea8e4");
    assertThat(value(ai, "metadata", "annotations", "argocd.argoproj.io/sync-wave"))
        .isEqualTo("10");
    assertThat(patchPaths).contains("ai-read-model-deployment-identity.yaml");
    assertThat(jobAnnotations)
        .containsKeys(
            "openat.io/target-workload-revision",
            "openat.io/deployment-run-id",
            "openat.io/rotation-identity",
            "openat.io/target-active-resource-version-identity");
    assertThat(podAnnotations)
        .containsKeys(
            "openat.io/target-workload-revision",
            "openat.io/target-active-resource-version-identity");
    assertThat(podLabels)
        .containsKeys(
            "openat.io/deployment-run-id",
            "openat.io/rotation-identity",
            "openat.io/target-active-rv-hash");
  }

  @Test
  @DisplayName("배포 성공은 대상 Hook과 AI pod가 같은 Secret revision을 소비한 뒤에만 인정한다")
  void deploymentWorkflow_requiresHookAndAiRolloutIdentity() throws IOException {
    String workflow = read(".github", "workflows", "deploy.yml");
    Map<String, Object> convergence = step(workflow, "Wait for ArgoCD convergence");
    Map<String, Object> convergenceEnvironment = YamlDocuments.asMap(convergence.get("env"));
    String convergenceScript = (String) convergence.get("run");
    String rolloutProof = read("k8s", "bootstrap", "prove-ai-query-rollout.sh");

    assertThat(convergenceEnvironment)
        .containsEntry("TARGET_REV", "${{ steps.pin.outputs.revision }}")
        .containsEntry("EXPECTED_WORKLOAD_REV", "${{ steps.pin.outputs.workload_revision }}")
        .containsEntry("EXPECTED_ACTIVE_RV", "${{ steps.pin.outputs.active_rv }}")
        .containsEntry("EXPECTED_ACTIVE_RV_HASH", "${{ steps.pin.outputs.active_rv_hash }}")
        .containsEntry("EXPECTED_ROTATION_IDENTITY", "${{ steps.pin.outputs.rotation_identity }}")
        .containsEntry(
            "EXPECTED_DEPLOYMENT_RUN_ID", "${{ github.run_id }}-${{ github.run_attempt }}");
    assertThat(convergenceScript)
        .contains("ai-read-model-apply\")].hookPhase")
        .contains("HOOK_STATE\" = \"$EXPECTED_HOOK_STATE")
        .contains("$RUNNER_TEMP/openat-deploy-scripts/prove-ai-query-rollout.sh")
        .contains("prove_ai_rollout \"$AI_STATE\" \"$EXPECTED_ACTIVE_RV\"")
        .contains("AI_ROLLOUT_PROOF=PROVEN")
        .doesNotContain("prove_ai_rollout()")
        .doesNotContain("get replicasets -n openat -l app=ai");
    assertThat(rolloutProof)
        .contains("prove_ai_rollout()")
        .contains("deployment\\.kubernetes\\.io/revision")
        .contains("get replicasets -n \"$namespace\" -l app=ai")
        .contains("ownerReferences[?(@.controller==true)].uid")
        .contains(".metadata.deletionTimestamp")
        .contains("selected_rs_after");
    assertThat(workflow).contains("steps.provision.outputs.reconcile_required == 'true'");
  }

  @Test
  @DisplayName("자격증명 복구는 현재 실행과 검증된 DB 상태에만 결합된다")
  void credentialRotation_reconcilesOnlyProvenState() throws IOException {
    String workflow = read(".github", "workflows", "deploy.yml");
    String applyScript = read("ai", "scripts", "apply-read-model.sh");
    String reconcileScript = read("k8s", "bootstrap", "reconcile-ai-query-rotation.sh");
    String secretManifest = read("k8s", "bootstrap", "secret-manifest.sh");
    String createSecrets = read("k8s", "bootstrap", "create-secrets.sh");
    String rotateSecret = read("k8s", "bootstrap", "rotate-ai-query-secret.sh");

    assertThat(workflow)
        .contains("${{ github.run_id }}-${{ github.run_attempt }}")
        .contains("Stage trusted deployment scripts")
        .contains("rotation_started=%s")
        .contains("reconcile_required=%s")
        .contains("steps.convergence.outcome != 'success'")
        .contains("reconcile-ai-query-rotation.sh\" failure")
        .contains("reconcile-ai-query-rotation.sh\" success")
        .doesNotContain("kubectl delete secret ai-query-rollback-secrets");
    assertThat(applyScript)
        .contains("record_db_state PREVIOUS")
        .contains("record_db_state NEW")
        .contains("record_db_state UNKNOWN")
        .contains("probe_query_login \"${AI_QUERY_DB_PREVIOUS_PASSWORD}\"")
        .contains("verify_query_contract \"${AI_QUERY_DB_PASSWORD}\"");
    assertThat(reconcileScript)
        .contains("openat\\.io/rotation-run-id")
        .contains("openat\\.io/target-active-resource-version")
        .contains("openat\\.io/target-deploy-revision")
        .contains("\"$GIT\" ls-remote --exit-code origin refs/heads/deploy/state")
        .contains("Failed|Error")
        .contains("DB 상태가 UNKNOWN")
        .contains("operation_finished_epoch")
        .contains("$SCRIPT_DIR/prove-ai-query-rollout.sh")
        .contains("prove_ai_rollout \"$deployment_state\" \"$target_active_rv\"")
        .contains("normalize_rollback_to_active")
        .contains("restore_previous_and_normalize")
        .doesNotContain("prove_target_hook_never_created");
    assertThat(secretManifest)
        .contains("render_secret_manifest()")
        .contains("data-key=variable-name")
        .contains("${!variable_name}")
        .contains("kind: Secret")
        .contains("base64")
        .doesNotContain("mktemp");
    assertThat(createSecrets)
        .contains("$SCRIPT_DIR/secret-manifest.sh")
        .contains("ai-inference-secrets")
        .contains("CHAT_INFERENCE_API_KEY")
        .doesNotContain("--from-literal", "--docker-password", "sys.argv", "set -a");
    assertThat(rotateSecret)
        .contains("$SCRIPT_DIR/secret-manifest.sh")
        .doesNotContain("--from-literal", "--docker-password", "set -a");
    assertThat(reconcileScript)
        .contains("$SCRIPT_DIR/secret-manifest.sh")
        .doesNotContain("--from-literal", "--docker-password");
    assertThat(createSecrets)
        .contains("--ignore-not-found")
        .contains("최초 생성으로 간주하지 않습니다")
        .contains("AI_QUERY_ROTATION_STARTED=true")
        .contains("AI_QUERY_ROTATION_STARTED=false")
        .contains("AI_QUERY_RECONCILE_REQUIRED=false")
        .contains("openat.io/rotation-run-id=steady")
        .contains("rotate-ai-query-secret.sh를 사용해야 합니다")
        .doesNotContain("trap restore_unpublished_ai_rotation ERR INT TERM");
    assertThat(rotateSecret)
        .contains("restore_unpublished_ai_rotation")
        .contains("trap restore_unpublished_ai_rotation ERR INT TERM")
        .contains("openat.io/rotation-run-id")
        .contains("openat.io/target-active-resource-version=pending");
  }

  private static String read(String first, String... more) throws IOException {
    return Files.readString(Path.of(first, more), StandardCharsets.UTF_8);
  }

  private static Map<String, Object> step(String workflow, String name) {
    Map<String, Object> document = YamlDocuments.parse(workflow).onlyDocument();
    Map<String, Object> deployJob = YamlDocuments.asMap(value(document, "jobs", "deploy"));
    return asMaps(deployJob.get("steps")).stream()
        .filter(candidate -> name.equals(candidate.get("name")))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("workflow step이 없어요: " + name));
  }
}

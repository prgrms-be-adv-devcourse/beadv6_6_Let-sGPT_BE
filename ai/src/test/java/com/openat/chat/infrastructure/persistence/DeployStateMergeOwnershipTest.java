package com.openat.chat.infrastructure.persistence;

import static com.openat.chat.infrastructure.persistence.YamlDocuments.asMaps;
import static org.assertj.core.api.Assertions.assertThat;

import com.openat.chat.infrastructure.persistence.DeploymentScripts.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * main→deploy/state 병합 스크립트를 임시 저장소에서 실제로 실행해 소유권 집행을 검증한다.
 *
 * <p>임시 git 저장소를 세우는 비용을 감수하는 이유: 이 스크립트의 값은 "충돌이 어디서 나든 결과가 같다"는 성질에 있고, 그 성질은 실제 3-way 병합을 거치지
 * 않으면 확인할 수 없다.
 */
class DeployStateMergeOwnershipTest {

  private static final String MERGE_SCRIPT = "k8s/bootstrap/merge-main-into-deploy-state.sh";
  private static final String OVERLAY = "k8s/overlay/kustomization.yaml";
  private static final String QUEUE_MANIFEST = "k8s/base/27-queue.yaml";
  private static final String JOB_NAME_STATE = "k8s/base/29-ai-read-model-job-name.yaml";
  private static final String IDENTITY_PATCH =
      "k8s/base/29-ai-read-model-deployment-identity-patch.yaml";
  private static final String NEW_MANIFEST = "k8s/base/32-https-redirect-middleware.yaml";

  private static final String OVERLAY_MAIN =
      """
      # main이 소유하는 골격
      apiVersion: kustomize.config.k8s.io/v1beta1
      kind: Kustomization

      resources:
        - ../base

      images:
        - name: ghcr.io/openat/ai
          newTag: latest
        - name: ghcr.io/openat/queue
          newTag: latest
      """;

  private static final String OVERLAY_DEPLOY_STATE =
      """
      apiVersion: kustomize.config.k8s.io/v1beta1
      kind: Kustomization
      resources:
      - ../base
      images:
      - name: ghcr.io/openat/ai
        newName: ghcr.io/openat/ai
        newTag: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
      - name: ghcr.io/openat/queue
        newName: ghcr.io/openat/queue
        newTag: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
      """;

  private static final String QUEUE_MAIN =
      """
      apiVersion: apps/v1
      kind: Deployment
      metadata:
        name: queue
      spec:
        replicas: 1
      """;

  private static final String JOB_NAME_SEED =
      """
      apiVersion: v1
      kind: ConfigMap
      metadata:
        name: ai-read-model-job-name
      data:
        identity: "bootstrap"
        jobName: "ai-read-model-apply-bootstrap"
      """;

  private static final String IDENTITY_PATCH_SEED =
      """
      apiVersion: batch/v1
      kind: Job
      metadata:
        name: ai-read-model-apply
        annotations:
          openat.io/target-workload-revision: "bootstrap"
          openat.io/read-model-identity: "bootstrap"
      """;

  @TempDir Path tempDir;

  @Test
  @DisplayName("read-model 값 파일 하나만 충돌해도 두 파일이 함께 main seed로 맞춰진다")
  void merge_partialReadModelConflict_alignsBothValueFilesToMainSeed() throws Exception {
    // given: identity patch는 main이 annotation 키를 바꿔 충돌하고, job name은 deploy/state만 고쳐 충돌하지 않는다
    Path repository = seedRepository();
    commitOnDeployState(
        Map.of(
            OVERLAY, OVERLAY_DEPLOY_STATE,
            JOB_NAME_STATE, deployedJobName(),
            IDENTITY_PATCH, deployedIdentityPatch()));
    String mainSha =
        commitOnMain(
            Map.of(
                IDENTITY_PATCH,
                renamedIdentityPatch(),
                NEW_MANIFEST,
                """
        apiVersion: traefik.io/v1alpha1
        kind: Middleware
        """));
    git(repository, "checkout", "deploy/state");
    String beforeMerge = revision(repository, "HEAD");

    // when
    Result result = merge(repository, mainSha);

    // then
    assertThat(result.exitCode()).describedAs(result.output()).isZero();
    assertThat(result.output()).contains("read-model identity 파일 충돌");
    // 두 값 파일 모두 main seed다 — 하나만 되돌아가면 뒤 검증에서 identity가 어긋나 배포가 멈춘다.
    assertThat(read(repository, IDENTITY_PATCH)).isEqualTo(renamedIdentityPatch());
    assertThat(read(repository, JOB_NAME_STATE)).isEqualTo(JOB_NAME_SEED);
    // main이 새로 넣은 매니페스트는 살아남고, overlay는 main 구조 위에 deploy/state pin이 얹힌다.
    assertThat(Files.exists(repository.resolve(NEW_MANIFEST))).isTrue();
    assertThat(imageTags(read(repository, OVERLAY)))
        .containsExactly(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    assertThat(read(repository, OVERLAY)).startsWith("# main이 소유하는 골격");
    // 병합은 커밋으로 마무리되고 worktree에 남는 변경이 없다.
    assertThat(status(repository)).isEmpty();
    assertThat(revision(repository, "HEAD")).isNotEqualTo(beforeMerge);
    assertThat(parents(repository)).containsExactly(beforeMerge, mainSha);
  }

  @Test
  @DisplayName("main이 소유한 매니페스트가 충돌하면 병합을 되돌리고 실패한다")
  void merge_unexpectedConflict_abortsAndLeavesWorktreeClean() throws Exception {
    // given: deploy/state가 CD 계약 밖에서 main 소유 매니페스트를 직접 고쳤다
    Path repository = seedRepository();
    commitOnDeployState(
        Map.of(
            OVERLAY,
            OVERLAY_DEPLOY_STATE,
            QUEUE_MANIFEST,
            QUEUE_MAIN.replace("replicas: 1", "replicas: 9")));
    String mainSha =
        commitOnMain(Map.of(QUEUE_MANIFEST, QUEUE_MAIN.replace("replicas: 1", "replicas: 3")));
    git(repository, "checkout", "deploy/state");
    String beforeMerge = revision(repository, "HEAD");

    // when
    Result result = merge(repository, mainSha);

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("소유권 규칙에 없는 충돌").contains(QUEUE_MANIFEST);
    assertThat(revision(repository, "HEAD")).isEqualTo(beforeMerge);
    assertThat(status(repository)).isEmpty();
    assertThat(Files.exists(repository.resolve(".git/MERGE_HEAD"))).isFalse();
    assertThat(read(repository, QUEUE_MANIFEST)).contains("replicas: 9");
  }

  private static String deployedJobName() {
    return JOB_NAME_SEED.replace("bootstrap", "deployedidentity");
  }

  private static String deployedIdentityPatch() {
    return IDENTITY_PATCH_SEED.replace("bootstrap", "deployedidentity");
  }

  /** main이 annotation 키 이름을 바꾼 구조 변경 — deploy/state의 값 변경과 같은 줄에서 충돌한다. */
  private static String renamedIdentityPatch() {
    return IDENTITY_PATCH_SEED.replace(
        "openat.io/read-model-identity", "openat.io/read-model-artifact-identity");
  }

  private Path seedRepository() throws Exception {
    Path repository = Files.createDirectories(tempDir.resolve("deploy-state-repository"));
    git(repository, "init", "-b", "main");
    write(
        repository,
        Map.of(
            OVERLAY, OVERLAY_MAIN,
            QUEUE_MANIFEST, QUEUE_MAIN,
            JOB_NAME_STATE, JOB_NAME_SEED,
            IDENTITY_PATCH, IDENTITY_PATCH_SEED));
    git(repository, "add", "-A");
    git(repository, "commit", "-m", "seed");
    git(repository, "branch", "deploy/state");
    return repository;
  }

  private void commitOnDeployState(Map<String, String> files) throws Exception {
    Path repository = repository();
    git(repository, "checkout", "deploy/state");
    write(repository, files);
    git(repository, "add", "-A");
    git(repository, "commit", "-m", "cd values");
    git(repository, "checkout", "main");
  }

  private String commitOnMain(Map<String, String> files) throws Exception {
    Path repository = repository();
    git(repository, "checkout", "main");
    write(repository, files);
    git(repository, "add", "-A");
    git(repository, "commit", "-m", "main structure");
    return revision(repository, "main");
  }

  private Result merge(Path repository, String mainSha) throws Exception {
    Path workDirectory = Files.createDirectories(tempDir.resolve("work"));
    Map<String, String> environment = new HashMap<>(gitEnvironment());
    environment.put("WORK_DIR", DeploymentScripts.toScriptPath(workDirectory));
    environment.put("PYTHON", DeploymentScripts.python());
    List<String> command =
        List.of(
            DeploymentScripts.bash(),
            DeploymentScripts.toScriptPath(Path.of(MERGE_SCRIPT).toAbsolutePath()),
            mainSha);
    return DeploymentScripts.run(repository, environment, command);
  }

  private Path repository() {
    return tempDir.resolve("deploy-state-repository");
  }

  private void git(Path repository, String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(DeploymentScripts.git());
    command.addAll(List.of(arguments));
    Result result = DeploymentScripts.run(repository, gitEnvironment(), command);
    if (result.exitCode() != 0) {
      throw new IllegalStateException("git " + List.of(arguments) + " 실패: " + result.output());
    }
  }

  /** 개발자·러너의 전역 git 설정(병합 전략·서명·hook)이 결과를 바꾸지 않게 격리한다. */
  private Map<String, String> gitEnvironment() {
    return Map.of(
        "GIT_CONFIG_GLOBAL", DeploymentScripts.toScriptPath(tempDir.resolve("absent-global")),
        "GIT_CONFIG_SYSTEM", DeploymentScripts.toScriptPath(tempDir.resolve("absent-system")),
        "GIT_AUTHOR_NAME", "merge-test",
        "GIT_AUTHOR_EMAIL", "merge-test@example.test",
        "GIT_COMMITTER_NAME", "merge-test",
        "GIT_COMMITTER_EMAIL", "merge-test@example.test");
  }

  private static void write(Path repository, Map<String, String> files) throws IOException {
    for (Map.Entry<String, String> file : files.entrySet()) {
      Path target = repository.resolve(file.getKey());
      Files.createDirectories(target.getParent());
      Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
    }
  }

  private static String read(Path repository, String path) throws IOException {
    return Files.readString(repository.resolve(path), StandardCharsets.UTF_8);
  }

  private String status(Path repository) throws Exception {
    return output(repository, "status", "--porcelain").strip();
  }

  private String revision(Path repository, String revision) throws Exception {
    return output(repository, "rev-parse", revision).strip();
  }

  private List<String> parents(Path repository) throws Exception {
    return List.of(output(repository, "rev-parse", "HEAD^1", "HEAD^2").strip().split("\\s+"));
  }

  private String output(Path repository, String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(DeploymentScripts.git());
    command.addAll(List.of(arguments));
    Result result = DeploymentScripts.run(repository, gitEnvironment(), command);
    if (result.exitCode() != 0) {
      throw new IllegalStateException("git " + List.of(arguments) + " 실패: " + result.output());
    }
    return result.output();
  }

  private static List<String> imageTags(String overlay) {
    return asMaps(YamlDocuments.parse(overlay).onlyDocument().get("images")).stream()
        .map(image -> (String) image.get("newTag"))
        .toList();
  }
}

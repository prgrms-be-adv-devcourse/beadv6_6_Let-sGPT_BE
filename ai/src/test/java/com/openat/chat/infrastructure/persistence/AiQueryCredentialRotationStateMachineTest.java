package com.openat.chat.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiQueryCredentialRotationStateMachineTest {

  private static final String TARGET_REVISION = "a".repeat(40);
  private static final String RUN_ID = "run-1";
  private static final String SECRET_CANARY = "secret-canary";
  private static final String OLD_PASSWORD = SECRET_CANARY + "-old";
  private static final String NEW_PASSWORD = SECRET_CANARY + "-new";

  @TempDir Path tempDir;

  @Test
  @DisplayName("일반 bootstrap은 기존 비밀번호가 같으면 active와 rollback Secret을 보존한다")
  void createSecrets_existingPasswordSame_preservesAiSecrets() throws Exception {
    // given
    Path envFile = writeEnv(OLD_PASSWORD);

    // when
    ScriptResult result = run("k8s/bootstrap/create-secrets.sh", "create-same", envFile.toString());

    // then
    assertThat(result.exitCode()).isZero();
    assertThat(result.output())
        .contains("AI_QUERY_ROTATION_STARTED=false")
        .contains("AI_QUERY_RECONCILE_REQUIRED=false")
        .contains("ACTIVE_RESOURCE_VERSION=rv1");
    assertThat(result.secretMutations()).isEmpty();
  }

  @Test
  @DisplayName("일반 bootstrap은 기존 비밀번호와 환경 값이 다르면 active를 덮어쓰지 않는다")
  void createSecrets_existingPasswordDiffers_failsWithoutAiMutation() throws Exception {
    // given
    Path envFile = writeEnv(NEW_PASSWORD);

    // when
    ScriptResult result =
        run("k8s/bootstrap/create-secrets.sh", "create-different", envFile.toString());

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("rotate-ai-query-secret.sh");
    assertThat(result.secretMutations()).isEmpty();
  }

  @Test
  @DisplayName("수동 회전은 이전 rollback marker가 steady가 아니면 시작하지 않는다")
  void rotateSecret_unresolvedMarker_failsWithoutMutation() throws Exception {
    // given
    Path envFile = writeRotationEnv();

    // when
    ScriptResult result =
        run("k8s/bootstrap/rotate-ai-query-secret.sh", "rotate-unresolved", envFile.toString());

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("marker가 steady가 아니어서");
    assertThat(result.secretMutations()).isEmpty();
  }

  @Test
  @DisplayName("수동 회전은 이전 ArgoCD operation이 실행 중이면 시작하지 않는다")
  void rotateSecret_argoRunning_failsWithoutMutation() throws Exception {
    // given
    Path envFile = writeRotationEnv();

    // when
    ScriptResult result =
        run("k8s/bootstrap/rotate-ai-query-secret.sh", "rotate-running", envFile.toString());

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("이전 ArgoCD 작업이 실행 중");
    assertThat(result.secretMutations()).isEmpty();
  }

  @Test
  @DisplayName("수동 회전은 steady marker와 idle gate를 통과한 경우에만 새 active를 게시한다")
  void rotateSecret_steadyAndIdle_publishesRotationIdentity() throws Exception {
    // given
    Path envFile = writeRotationEnv();

    // when
    ScriptResult result =
        run("k8s/bootstrap/rotate-ai-query-secret.sh", "rotate-success", envFile.toString());

    // then
    assertThat(result.exitCode()).isZero();
    assertThat(result.output())
        .contains("AI_QUERY_ROTATION_STARTED=true")
        .contains("AI_QUERY_RECONCILE_REQUIRED=true")
        .contains("ACTIVE_RESOURCE_VERSION=rv2");
    assertThat(result.secretMutations())
        .contains("APPLY:ai-query-rollback-secrets", "APPLY:ai-query-secrets");
  }

  @Test
  @DisplayName("Pod 목록 API 오류는 zero pod가 아니라 UNKNOWN으로 처리해 Secret을 변경하지 않는다")
  void reconcile_podApiError_keepsSecretsUnchanged() throws Exception {
    // when
    ScriptResult result = runReconcile("reconcile-pod-api-error");

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("DB 상태가 UNKNOWN");
    assertThat(result.secretMutations()).isEmpty();
  }

  @Test
  @DisplayName("Argo에 Hook 이력이 있는데 Job이 없으면 TTL 삭제 가능성 때문에 UNKNOWN으로 유지한다")
  void reconcile_hookRecordedButJobMissing_keepsSecretsUnchanged() throws Exception {
    // when
    ScriptResult result = runReconcile("reconcile-hook-job-missing");

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("DB 상태가 UNKNOWN");
    assertThat(result.secretMutations()).isEmpty();
  }

  @Test
  @DisplayName("terminal operation에 Hook 실행 흔적이 없으면 UNKNOWN으로 유지한다")
  void reconcile_terminalOperationWithNoHookObjects_keepsSecretsUnchanged() throws Exception {
    // when
    ScriptResult result = runReconcile("reconcile-no-hook");

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("UNKNOWN");
    assertThat(result.secretMutations()).isEmpty();
  }

  @Test
  @DisplayName("operation 종료 뒤 재생성된 Job은 target으로 신뢰하지 않는다")
  void reconcile_replacementJobCreatedAfterOperationFinished_keepsSecretsUnchanged()
      throws Exception {
    // when
    ScriptResult result = runReconcile("reconcile-replacement-job");

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("DB 상태가 UNKNOWN");
    assertThat(result.secretMutations()).isEmpty();
  }

  @Test
  @DisplayName("현재 target에 결박된 Job과 Pod의 PREVIOUS marker만 복구 근거로 사용한다")
  void reconcile_boundJobPodPrevious_restoresPrevious() throws Exception {
    // when
    ScriptResult result = runReconcile("reconcile-previous");

    // then
    assertThat(result.exitCode()).isZero();
    assertThat(result.secretMutations()).contains("APPLY:ai-query-secrets");
  }

  @Test
  @DisplayName("다른 회전에 결박된 Job의 DB_STATE는 신뢰하지 않고 Secret을 변경하지 않는다")
  void reconcile_jobIdentityMismatch_keepsSecretsUnchanged() throws Exception {
    // when
    ScriptResult result = runReconcile("reconcile-job-identity-mismatch");

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("DB 상태가 UNKNOWN");
    assertThat(result.secretMutations()).isEmpty();
  }

  @Test
  @DisplayName("Hook이 성공해도 AI rollout의 target 비밀번호 소비를 증명하지 못하면 UNKNOWN으로 유지한다")
  void reconcile_hookSucceededAiRolloutUnproven_keepsSecretsUnchanged() throws Exception {
    // when
    ScriptResult result = runReconcile("reconcile-hook-succeeded-unproven");

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("AI rollout");
    assertThat(result.secretMutations()).isEmpty();
  }

  @Test
  @DisplayName("Hook과 AI rollout이 같은 target을 소비한 경우에만 NEW 상태를 정규화한다")
  void reconcile_hookAndAiRolloutProven_normalizesNew() throws Exception {
    // when
    ScriptResult result =
        run(
            "k8s/bootstrap/reconcile-ai-query-rotation.sh",
            "reconcile-success",
            "success",
            TARGET_REVISION,
            RUN_ID);

    // then
    assertThat(result.exitCode()).isZero();
    assertThat(result.secretMutations())
        .contains("APPLY:ai-query-rollback-secrets")
        .doesNotContain("APPLY:ai-query-secrets");
  }

  @Test
  @DisplayName("이전 ReplicaSet의 Ready Pod는 target rollout 증거로 사용하지 않는다")
  void reconcile_oldReplicaSetReadyPod_keepsSecretsUnchanged() throws Exception {
    // when
    ScriptResult result = runSuccessfulReconcile("reconcile-success-old-rs-decoy");

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("AI rollout");
    assertThat(result.secretMutations()).isEmpty();
  }

  @Test
  @DisplayName("ReplicaSet 목록 API 오류는 rollout을 증명하지 못한 것으로 처리한다")
  void reconcile_replicaSetApiError_keepsSecretsUnchanged() throws Exception {
    // when
    ScriptResult result = runSuccessfulReconcile("reconcile-success-rs-api-error");

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("AI rollout");
    assertThat(result.secretMutations()).isEmpty();
  }

  @Test
  @DisplayName("현재 ReplicaSet의 Pod만 desired 수만큼 Ready면 이전 Pod가 남아도 성공한다")
  void reconcile_currentReplicaSetReadyPods_withOldDecoy_normalizesNew() throws Exception {
    // when
    ScriptResult result = runSuccessfulReconcile("reconcile-success-with-old-rs-decoy");

    // then
    assertThat(result.exitCode()).isZero();
    assertThat(result.secretMutations())
        .contains("APPLY:ai-query-rollback-secrets")
        .doesNotContain("APPLY:ai-query-secrets");
  }

  @Test
  @DisplayName("rollout 증명 중 Deployment snapshot이 바뀌면 Secret을 변경하지 않는다")
  void reconcile_deploymentChangesDuringRolloutProof_keepsSecretsUnchanged() throws Exception {
    // when
    ScriptResult result = runSuccessfulReconcile("reconcile-success-deployment-changed");

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("AI rollout");
    assertThat(result.secretMutations()).isEmpty();
  }

  private ScriptResult runReconcile(String scenario) throws Exception {
    return run(
        "k8s/bootstrap/reconcile-ai-query-rotation.sh",
        scenario,
        "failure",
        TARGET_REVISION,
        RUN_ID);
  }

  private ScriptResult runSuccessfulReconcile(String scenario) throws Exception {
    return run(
        "k8s/bootstrap/reconcile-ai-query-rotation.sh",
        scenario,
        "success",
        TARGET_REVISION,
        RUN_ID);
  }

  private ScriptResult run(String script, String scenario, String... arguments) throws Exception {
    Path caseDir = Files.createTempDirectory(tempDir, "rotation-");
    Path binDir = Files.createDirectories(caseDir.resolve("bin"));
    Path mutationLog = caseDir.resolve("mutations.log");
    Files.createFile(mutationLog);
    writeExecutable(binDir.resolve("kubectl"), fakeKubectl());
    writeExecutable(binDir.resolve("git"), fakeGit());
    writeExecutable(binDir.resolve("python3"), fakePython());

    String bash = findBash();
    String wrapper = "export PATH=\"$1:$PATH\"; shift; exec bash \"$@\"";
    List<String> command = new ArrayList<>();
    command.add(bash);
    command.add("-c");
    command.add(wrapper);
    command.add("rotation-test");
    command.add(toBashPath(binDir));
    command.add(toBashPath(Path.of(script).toAbsolutePath()));
    for (String argument : arguments) {
      Path possiblePath = Path.of(argument);
      command.add(
          Files.exists(possiblePath) ? toBashPath(possiblePath.toAbsolutePath()) : argument);
    }

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    processBuilder.environment().put("SCENARIO", scenario);
    processBuilder.environment().put("MUTATION_LOG", toBashPath(mutationLog));
    processBuilder.environment().put("TARGET_REVISION", TARGET_REVISION);
    processBuilder.environment().put("AI_QUERY_ROTATION_RUN_ID", RUN_ID);
    processBuilder.environment().put("RECONCILE_TIMEOUT_SECONDS", "0");
    processBuilder.environment().put("RECONCILE_POLL_SECONDS", "0");
    processBuilder.environment().put("GHCR_USER", SECRET_CANARY + "-ghcr-user");
    processBuilder.environment().put("GHCR_PAT", SECRET_CANARY + "-ghcr-pat");
    processBuilder.environment().remove("KUBECTL");
    processBuilder.environment().remove("GIT");

    Process process = processBuilder.start();
    boolean finished = process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new IllegalStateException("회전 스크립트 테스트가 제한 시간 안에 끝나지 않았습니다.");
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    List<String> mutations = Files.readAllLines(mutationLog, StandardCharsets.UTF_8);
    return new ScriptResult(process.exitValue(), output, mutations);
  }

  private Path writeEnv(String aiPassword) throws IOException {
    Path envFile = tempDir.resolve("all-" + aiPassword + ".env");
    String contents =
        "DB_USER="
            + SECRET_CANARY
            + "-db-user\n"
            + "DB_PASSWORD="
            + SECRET_CANARY
            + "-db-password\n"
            + "AI_QUERY_DB_PASSWORD="
            + aiPassword
            + "\n"
            + "JWT_KEY_ID="
            + SECRET_CANARY
            + "-jwt-id\n"
            + "JWT_PRIVATE_KEY="
            + SECRET_CANARY
            + "-jwt-private\n"
            + "JWT_PUBLIC_KEY="
            + SECRET_CANARY
            + "-jwt-public\n"
            + "PG_CLIENT_KEY="
            + SECRET_CANARY
            + "-pg-client\n"
            + "PG_SECRET_KEY="
            + SECRET_CANARY
            + "-pg-secret\n"
            + "PAYMENT_FIELD_ENCRYPTION_KEY="
            + SECRET_CANARY
            + "-payment-field\n"
            + "OPENAI_API_KEY="
            + SECRET_CANARY
            + "-openai\n"
            + "OPENAI_INFERENCEAI_BASE_URL=https://inference.example.test/v1\n"
            + "OPENAI_INFERENCEAI_API_KEY="
            + SECRET_CANARY
            + "-search-inference\n"
            + "CHAT_INFERENCE_API_KEY="
            + SECRET_CANARY
            + "-inference\n"
            + "WARMUP_EMAIL="
            + SECRET_CANARY
            + "-warmup@example.com\n"
            + "WARMUP_PASSWORD="
            + SECRET_CANARY
            + "-warmup-password\n"
            + "GRAFANA_ADMIN_USER="
            + SECRET_CANARY
            + "-grafana-user\n"
            + "GRAFANA_ADMIN_PASSWORD="
            + SECRET_CANARY
            + "-grafana-password\n";
    return Files.writeString(envFile, contents, StandardCharsets.UTF_8);
  }

  private Path writeRotationEnv() throws IOException {
    Path envFile = tempDir.resolve("rotation.env");
    return Files.writeString(
        envFile, "AI_QUERY_DB_PASSWORD=" + NEW_PASSWORD + "\n", StandardCharsets.UTF_8);
  }

  private static void writeExecutable(Path path, String contents) throws IOException {
    Files.writeString(path, contents, StandardCharsets.UTF_8);
    path.toFile().setExecutable(true, false);
    try {
      Files.setPosixFilePermissions(
          path,
          EnumSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.GROUP_EXECUTE,
              PosixFilePermission.OTHERS_READ,
              PosixFilePermission.OTHERS_EXECUTE));
    } catch (UnsupportedOperationException ignored) {
      // Windows Git Bash는 NTFS 파일을 실행 가능 파일로 취급한다.
    }
  }

  private static String findBash() throws IOException, InterruptedException {
    List<String> candidates =
        System.getProperty("os.name").toLowerCase().contains("windows")
            ? List.of(
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
                "bash")
            : List.of("bash", "/bin/bash", "/usr/bin/bash");
    for (String candidate : candidates) {
      try {
        Process process = new ProcessBuilder(candidate, "--version").start();
        if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
          return candidate;
        }
      } catch (IOException ignored) {
        // 다음 표준 위치를 확인한다.
      }
    }
    throw new IOException("bash 또는 Git Bash를 찾지 못했습니다.");
  }

  private static String toBashPath(Path path) {
    String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/');
    if (normalized.length() >= 3 && normalized.charAt(1) == ':') {
      return "/" + Character.toLowerCase(normalized.charAt(0)) + normalized.substring(2);
    }
    return normalized;
  }

  private static String fakeGit() {
    return """
        #!/usr/bin/env bash
        set -eu
        case "${1:-}" in
          ls-remote) printf '%s\\trefs/heads/deploy/state\\n' "$TARGET_REVISION" ;;
          cat-file) exit 0 ;;
          merge-base) exit 0 ;;
          *) exit 0 ;;
        esac
        """;
  }

  private static String fakePython() {
    return """
        #!/usr/bin/env bash
        set -eu
        case " $* " in
          *secret-canary*) echo "secret value leaked through Python argv" >&2; exit 91 ;;
        esac
        if env | grep -Fq 'secret-canary'; then
          echo "secret value leaked through Python environment" >&2
          exit 92
        fi
        cat >/dev/null
        printf 'encoded'
        """;
  }

  private static String fakeKubectl() {
    String oldEncoded =
        Base64.getEncoder().encodeToString(OLD_PASSWORD.getBytes(StandardCharsets.UTF_8));
    String newEncoded =
        Base64.getEncoder().encodeToString(NEW_PASSWORD.getBytes(StandardCharsets.UTF_8));
    return """
        #!/usr/bin/env bash
        set -u
        joined=" $* "

        case "$joined" in
          *secret-canary*) echo "secret value leaked through kubectl argv" >&2; exit 91 ;;
        esac
        if env | grep -Fq 'secret-canary'; then
          echo "secret value leaked through kubectl environment" >&2
          exit 92
        fi

        log_ai_mutation() {
          case "$1" in
            ai-query-secrets|ai-query-rollback-secrets) printf '%s:%s\\n' "$2" "$1" >> "$MUTATION_LOG" ;;
          esac
        }

        if [ "${1:-}" = create ] && [ "${2:-}" = secret ]; then
          echo "secret-bearing kubectl create is forbidden" >&2
          exit 93
        fi
        if [ "${1:-}" = create ]; then
          printf 'RESOURCE=%s\\n' "${2:-unknown}"
          exit 0
        fi
        if [ "${1:-}" = apply ]; then
          payload=$(cat)
          case "$payload" in
            *secret-canary*) echo "plaintext secret leaked through manifest" >&2; exit 94 ;;
          esac
          name=$(printf '%s\\n' "$payload" | sed -n 's/^  name: //p' | head -1)
          log_ai_mutation "$name" APPLY
          if [ "$name" = ai-query-secrets ]; then
            printf 'rv2'
          fi
          exit 0
        fi
        if [ "${1:-}" = annotate ]; then
          kind=${2:-unknown}
          name=${3:-unknown}
          if [ "$kind" = secret ]; then
            log_ai_mutation "$name" ANNOTATE
          fi
          exit 0
        fi

        if [[ "$joined" == *" get secret ai-query-secrets "* ]]; then
          active_rv=rv1
          active_encoded="__OLD__"
          case "$SCENARIO" in
            reconcile-*) active_rv=rv2; active_encoded="__NEW__" ;;
          esac
          if grep -q '^APPLY:ai-query-secrets$' "$MUTATION_LOG"; then
            active_rv=rv3
            active_encoded="__OLD__"
          fi
          if [[ "$joined" == *"metadata.name"* ]]; then
            printf 'ai-query-secrets|%s|%s' "$active_encoded" "$active_rv"
          elif [[ "$joined" == *"data.AI_QUERY_DB_PASSWORD"* && "$joined" == *"metadata.resourceVersion"* ]]; then
            printf '%s|%s' "$active_encoded" "$active_rv"
          elif [[ "$joined" == *"metadata.resourceVersion"* ]]; then
            printf '%s' "$active_rv"
          fi
          exit 0
        fi

        if [[ "$joined" == *" get secret ai-query-rollback-secrets "* ]]; then
          if [[ "$joined" == *"metadata.name"* ]]; then
            case "$SCENARIO" in
              rotate-unresolved)
                printf 'ai-query-rollback-secrets|old-run|rv1|pending'
                ;;
              reconcile-*)
                printf 'ai-query-rollback-secrets|run-1|rv2|%s|2026-01-01T00:00:00Z' "$TARGET_REVISION"
                ;;
              *)
                printf 'ai-query-rollback-secrets|steady|rv1|steady'
                ;;
            esac
          elif [[ "$joined" == *"data.AI_QUERY_DB_PREVIOUS_PASSWORD"* ]]; then
            printf '%s' "__OLD__"
          else
            printf 'run-1|rv2|%s|2026-01-01T00:00:00Z' "$TARGET_REVISION"
          fi
          exit 0
        fi

        if [[ "$joined" == *" get application openat "* ]]; then
          if [[ "$joined" == *"syncResult.resources"* ]]; then
            case "$SCENARIO" in
              reconcile-no-hook)
                printf '%s|Failed|2026-01-01T00:01:00Z|2026-01-01T00:02:00Z|' "$TARGET_REVISION"
                ;;
              reconcile-success*)
                printf '%s|Succeeded|2026-01-01T00:01:00Z|2026-01-01T00:02:00Z|Job,ai-read-model-apply,Succeeded' "$TARGET_REVISION"
                ;;
              reconcile-hook-succeeded-unproven)
                printf '%s|Succeeded|2026-01-01T00:01:00Z|2026-01-01T00:02:00Z|Job,ai-read-model-apply,Succeeded' "$TARGET_REVISION"
                ;;
              *)
                printf '%s|Failed|2026-01-01T00:01:00Z|2026-01-01T00:02:00Z|Job,ai-read-model-apply,Failed' "$TARGET_REVISION"
                ;;
            esac
          elif [[ "$joined" == *"status.sync.revision"* ]]; then
            printf '%s|%s' "$TARGET_REVISION" "$TARGET_REVISION"
          else
            case "$SCENARIO" in
              rotate-running) printf 'Running' ;;
              *) printf 'Succeeded' ;;
            esac
          fi
          exit 0
        fi

        if [[ "$joined" == *" get job ai-read-model-apply "* ]]; then
          case "$SCENARIO" in
            reconcile-no-hook|reconcile-hook-job-missing) exit 0 ;;
          esac
          if [[ "$joined" == *"metadata.name"* ]]; then
            case "$SCENARIO" in
              reconcile-job-identity-mismatch)
                printf 'ai-read-model-apply|uid-1|2026-01-01T00:01:01Z|other-run|run-1|rv2|%s' "$TARGET_REVISION"
                ;;
              reconcile-replacement-job)
                printf 'ai-read-model-apply|uid-1|2026-01-01T00:02:01Z|run-1|run-1|rv2|%s' "$TARGET_REVISION"
                ;;
              *)
                printf 'ai-read-model-apply|uid-1|2026-01-01T00:01:01Z|run-1|run-1|rv2|%s' "$TARGET_REVISION"
                ;;
            esac
          else
            printf 'uid-1|run-1|rv2|%s' "$TARGET_REVISION"
          fi
          exit 0
        fi

        if [[ "$joined" == *" get deployment ai "* ]]; then
          case "$SCENARIO" in
            reconcile-success-deployment-changed)
              deployment_count_file="$MUTATION_LOG.deployment-count"
              deployment_count=$(cat "$deployment_count_file" 2>/dev/null || printf '0')
              deployment_count=$((deployment_count + 1))
              printf '%s' "$deployment_count" > "$deployment_count_file"
              if [ "$deployment_count" -eq 1 ]; then
                printf 'deployment-uid|rv2|2|2|1|1|1|1|0'
              else
                printf 'deployment-uid|rv2|3|3|1|1|1|1|0'
              fi
              ;;
            reconcile-success*) printf 'deployment-uid|rv2|2|2|1|1|1|1|0' ;;
          esac
          exit 0
        fi
        if [[ "$joined" == *" get replicasets "* ]]; then
          if [ "$SCENARIO" = reconcile-success-rs-api-error ]; then
            exit 42
          fi
          case "$SCENARIO" in
            reconcile-success*)
              printf 'ai-rs-current|rs-current-uid|Deployment|deployment-uid|true|7|rv2|1|1|1\\n'
              ;;
          esac
          exit 0
        fi
        if [[ "$joined" == *" get replicaset ai-rs-current "* ]]; then
          printf 'ai-rs-current|rs-current-uid|Deployment|deployment-uid|true|7|rv2|1|1|1'
          exit 0
        fi
        if [[ "$joined" == *" get pods "* ]]; then
          if [[ "$joined" == *" -l app=ai "* ]]; then
            case "$SCENARIO" in
              reconcile-success)
                printf 'ReplicaSet|rs-current-uid|true|rv2||Running|True\\n'
                ;;
              reconcile-success-old-rs-decoy)
                printf 'ReplicaSet|rs-old-uid|true|rv2||Running|True\\n'
                ;;
              reconcile-success-with-old-rs-decoy)
                printf 'ReplicaSet|rs-current-uid|true|rv2||Running|True\\n'
                printf 'ReplicaSet|rs-old-uid|true|rv2||Running|True\\n'
                ;;
            esac
            exit 0
          fi
          if [ "$SCENARIO" = reconcile-pod-api-error ]; then
            exit 42
          fi
          case "$SCENARIO" in
            reconcile-previous|reconcile-job-identity-mismatch) printf 'pod-1\\n' ;;
          esac
          exit 0
        fi
        if [[ "$joined" == *" get pod pod-1 "* ]]; then
          printf 'pod-1|uid-1|2026-01-01T00:01:02Z|DB_STATE=PREVIOUS||2026-01-01T00:01:03Z'
          exit 0
        fi

        exit 0
        """
        .replace("__OLD__", oldEncoded)
        .replace("__NEW__", newEncoded);
  }

  private record ScriptResult(int exitCode, String output, List<String> secretMutations) {}
}

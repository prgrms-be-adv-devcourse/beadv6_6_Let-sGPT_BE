package com.openat.chat.infrastructure.persistence;

import static com.openat.chat.infrastructure.persistence.YamlDocuments.asMaps;
import static org.assertj.core.api.Assertions.assertThat;

import com.openat.chat.infrastructure.persistence.DeploymentScripts.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * main→deploy/state 병합에서 overlay 이미지 pin 소유권을 집행하는 스크립트를 실제로 실행해 검증한다.
 *
 * <p>규칙은 "이미지 항목의 구조(name·newName 등)는 main 골격이 소유하고 태그(newTag)만 deploy/state가 소유한다"다. 문자열 검사로는 이
 * 규칙이 지켜지는지 알 수 없어 스크립트를 임시 파일로 돌려 결과 YAML을 읽는다.
 */
class ImagePinOwnershipEnforcementTest {

  private static final String OWNERSHIP_SCRIPT = "k8s/bootstrap/apply-image-pin-ownership.py";
  private static final String REPOSITORY_OVERLAY = "k8s/overlay/kustomization.yaml";

  private static final String MAIN_SKELETON =
      """
      # main이 소유하는 골격
      apiVersion: kustomize.config.k8s.io/v1beta1
      kind: Kustomization

      resources:
        - ../base

      images:
        - name: ghcr.io/openat/ai
          newName: ghcr.io/openat-next/ai
          newTag: latest
        - name: ghcr.io/openat/queue
          newTag: latest
      """;

  private static final String DEPLOY_STATE =
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
      - name: ghcr.io/openat/recommendation
        newName: ghcr.io/openat/recommendation
        newTag: cccccccccccccccccccccccccccccccccccccccc
      """;

  @TempDir Path tempDir;

  @Test
  @DisplayName("main이 이미지 저장소 경로를 바꾸면 그 구조가 살아남고 태그는 deploy/state 값이 유지된다")
  void enforcement_keepsMainStructureAndDeployStateTag() throws Exception {
    // given: main은 ai 이미지의 newName을 새 저장소로 옮겼고 deploy/state는 옛 경로에 pin을 갖고 있다
    // when
    Result result = enforce(MAIN_SKELETON, DEPLOY_STATE);

    // then
    assertThat(result.exitCode()).isZero();
    Map<String, Object> ai = image(rendered(), "ghcr.io/openat/ai");
    assertThat(ai)
        .containsEntry("newName", "ghcr.io/openat-next/ai")
        .containsEntry("newTag", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    assertThat(image(rendered(), "ghcr.io/openat/queue"))
        .containsEntry("newTag", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        .doesNotContainKey("newName");
  }

  @Test
  @DisplayName("항목 집합은 main을 따른다 — deploy/state에만 있는 항목은 버리고 main에만 있는 항목은 골격대로 채택한다")
  void enforcement_followsMainImageEntrySet() throws Exception {
    // given: main에만 있는 search를 더하고 deploy/state에만 있는 recommendation은 그대로 둔다
    String skeleton =
        MAIN_SKELETON
            + """
              - name: ghcr.io/openat/search
                newTag: latest
            """;

    // when
    Result result = enforce(skeleton, DEPLOY_STATE);

    // then
    assertThat(result.exitCode()).isZero();
    assertThat(imageNames(rendered()))
        .containsExactly("ghcr.io/openat/ai", "ghcr.io/openat/queue", "ghcr.io/openat/search");
    assertThat(image(rendered(), "ghcr.io/openat/search")).containsEntry("newTag", "latest");
    assertThat(result.output()).contains("ghcr.io/openat/recommendation");
  }

  @Test
  @DisplayName("골격 항목에 newTag가 없으면 태그 없는 배포를 만들지 않고 실패한다")
  void enforcement_skeletonWithoutNewTag_failsClosed() throws Exception {
    // given
    String skeleton = MAIN_SKELETON.replace("    newTag: latest\n", "");

    // when
    Result result = enforce(skeleton, DEPLOY_STATE);

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("newTag: 가 없습니다").contains("골격");
    assertThat(Files.exists(outputFile())).isFalse();
  }

  @Test
  @DisplayName("deploy/state 항목에 newTag가 없으면 pin 없는 배포를 만들지 않고 실패한다")
  void enforcement_deployStateWithoutNewTag_failsClosed() throws Exception {
    // given
    String state = DEPLOY_STATE.replace("  newTag: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n", "");

    // when
    Result result = enforce(MAIN_SKELETON, state);

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("newTag: 가 없습니다").contains("deploy/state");
    assertThat(Files.exists(outputFile())).isFalse();
  }

  @Test
  @DisplayName("골격 항목에 newTag가 둘 이상이면 어느 값이 pin인지 정하지 않고 실패한다")
  void enforcement_skeletonWithDuplicateNewTag_failsClosed() throws Exception {
    // given
    String skeleton =
        MAIN_SKELETON.replace(
            "    newName: ghcr.io/openat-next/ai\n",
            "    newTag: dddddddddddddddddddddddddddddddddddddddd\n");

    // when
    Result result = enforce(skeleton, DEPLOY_STATE);

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("newTag: 가 2개 있습니다").contains("골격");
    assertThat(Files.exists(outputFile())).isFalse();
  }

  @Test
  @DisplayName("deploy/state 항목에 newTag가 둘 이상이면 첫 값을 고르지 않고 실패한다")
  void enforcement_deployStateWithDuplicateNewTag_failsClosed() throws Exception {
    // given
    String state =
        DEPLOY_STATE.replace(
            "  newName: ghcr.io/openat/ai\n",
            "  newTag: dddddddddddddddddddddddddddddddddddddddd\n");

    // when
    Result result = enforce(MAIN_SKELETON, state);

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("newTag: 가 2개 있습니다").contains("deploy/state");
    assertThat(Files.exists(outputFile())).isFalse();
  }

  @Test
  @DisplayName("images 블록을 못 찾으면 골격을 통째로 밀어넣지 않고 실패한다")
  void enforcement_withoutImagesMarker_failsClosed() throws Exception {
    // given
    String state = DEPLOY_STATE.replace("images:", "# images 블록이 사라졌다");

    // when
    Result result = enforce(MAIN_SKELETON, state);

    // then
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("블록을 찾지 못했습니다");
    assertThat(Files.exists(outputFile())).isFalse();
  }

  @Test
  @DisplayName("들여쓰기가 다른 두 입력을 섞어도 결과는 한 종류 들여쓰기의 파싱 가능한 YAML이다")
  void enforcement_mixedIndentInputs_rendersSingleIndentParsableYaml() throws Exception {
    // given: 골격은 2스페이스, deploy/state는 kustomize 재직렬화 결과라 들여쓰기가 없다
    assertThat(MAIN_SKELETON).contains("\n  - name: ghcr.io/openat/ai");
    assertThat(DEPLOY_STATE).contains("\n- name: ghcr.io/openat/ai");

    // when
    Result result = enforce(MAIN_SKELETON, DEPLOY_STATE);

    // then
    assertThat(result.exitCode()).isZero();
    Set<Integer> indents =
        rendered()
            .lines()
            .filter(line -> line.stripLeading().startsWith("- name:"))
            .map(line -> line.length() - line.stripLeading().length())
            .collect(Collectors.toSet());
    assertThat(indents).containsExactly(2);
    assertThat(YamlDocuments.parse(rendered()).onlyDocument())
        .containsEntry("resources", List.of("../base"));
  }

  @Test
  @DisplayName("저장소에 커밋된 overlay는 소유권 집행의 불변식을 이미 만족한다")
  void enforcement_repositoryOverlayIsAValidSkeleton() throws Exception {
    // given
    String overlay = Files.readString(Path.of(REPOSITORY_OVERLAY), StandardCharsets.UTF_8);

    // when: 골격과 deploy/state가 같으면 집행 결과도 입력과 같아야 한다
    Result result = enforce(overlay, overlay);

    // then
    assertThat(result.exitCode()).isZero();
    assertThat(rendered()).isEqualTo(overlay);
  }

  private Result enforce(String skeleton, String state) throws Exception {
    Files.writeString(skeletonFile(), skeleton, StandardCharsets.UTF_8);
    Files.writeString(stateFile(), state, StandardCharsets.UTF_8);
    Files.deleteIfExists(outputFile());
    return DeploymentScripts.run(
        Path.of("").toAbsolutePath(),
        Map.of(),
        List.of(
            DeploymentScripts.python(),
            DeploymentScripts.toNativePath(Path.of(OWNERSHIP_SCRIPT)),
            DeploymentScripts.toNativePath(skeletonFile()),
            DeploymentScripts.toNativePath(stateFile()),
            DeploymentScripts.toNativePath(outputFile())));
  }

  private String rendered() throws IOException {
    return Files.readString(outputFile(), StandardCharsets.UTF_8);
  }

  private Path skeletonFile() {
    return tempDir.resolve("main-overlay.yaml");
  }

  private Path stateFile() {
    return tempDir.resolve("deploy-state-overlay.yaml");
  }

  private Path outputFile() {
    return tempDir.resolve("enforced-overlay.yaml");
  }

  private static Map<String, Object> image(String overlay, String name) {
    return YamlDocuments.named(images(overlay), name);
  }

  private static List<String> imageNames(String overlay) {
    return images(overlay).stream().map(entry -> (String) entry.get("name")).toList();
  }

  private static List<Map<String, Object>> images(String overlay) {
    return asMaps(YamlDocuments.parse(overlay).onlyDocument().get("images"));
  }
}

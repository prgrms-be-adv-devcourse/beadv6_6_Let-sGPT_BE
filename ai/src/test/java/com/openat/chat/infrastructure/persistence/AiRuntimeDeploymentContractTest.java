package com.openat.chat.infrastructure.persistence;

import static com.openat.chat.infrastructure.persistence.YamlDocuments.asMaps;
import static com.openat.chat.infrastructure.persistence.YamlDocuments.named;
import static com.openat.chat.infrastructure.persistence.YamlDocuments.value;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiRuntimeDeploymentContractTest {

  @Test
  @DisplayName("운영 AI는 고정된 추론·웹 검색 계약과 전용 Secret의 API 키를 사용한다")
  void inferenceConfiguration_usesDedicatedRuntimeSecret() throws IOException {
    Map<String, Object> configMap =
        YamlDocuments.read("k8s", "base", "01-configmap.yaml").document("ConfigMap", "app-config");
    Map<String, Object> deployment =
        YamlDocuments.read("k8s", "base", "28-ai.yaml").document("Deployment", "ai");
    Map<String, Object> aiContainer =
        named(asMaps(value(deployment, "spec", "template", "spec", "containers")), "ai");
    Map<String, Object> apiKey = named(asMaps(aiContainer.get("env")), "CHAT_INFERENCE_API_KEY");
    Map<String, Object> tavilyApiKey = named(asMaps(aiContainer.get("env")), "TAVILY_API_KEY");
    String bootstrap = read("k8s", "bootstrap", "create-secrets.sh");

    assertThat(value(configMap, "data", "CHAT_INFERENCE_BASE_URL"))
        .isEqualTo("https://api.inferway.xyz/v1");
    assertThat(value(configMap, "data", "CHAT_INFERENCE_MODEL")).isEqualTo("chat");
    assertThat(value(configMap, "data", "CHAT_INFERENCE_LOCAL_ONLY_ROUTE")).isEqualTo("false");
    assertThat(value(configMap, "data", "CHAT_WEB_SEARCH_ENABLED")).isEqualTo("true");
    assertThat(value(apiKey, "valueFrom", "secretKeyRef", "name"))
        .isEqualTo("ai-inference-secrets");
    assertThat(value(apiKey, "valueFrom", "secretKeyRef", "key"))
        .isEqualTo("CHAT_INFERENCE_API_KEY");
    assertThat(value(tavilyApiKey, "valueFrom", "secretKeyRef", "name"))
        .isEqualTo("ai-inference-secrets");
    assertThat(value(tavilyApiKey, "valueFrom", "secretKeyRef", "key")).isEqualTo("TAVILY_API_KEY");
    assertThat(bootstrap)
        .contains(
            "render_secret_manifest ai-inference-secrets \"$NS\" Opaque CHAT_INFERENCE_API_KEY TAVILY_API_KEY");
  }

  private static String read(String first, String... more) throws IOException {
    return Files.readString(Path.of(first, more), StandardCharsets.UTF_8);
  }
}

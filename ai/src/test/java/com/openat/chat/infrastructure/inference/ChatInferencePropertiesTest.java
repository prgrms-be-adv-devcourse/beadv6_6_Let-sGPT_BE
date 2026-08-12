package com.openat.chat.infrastructure.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

@DisplayName("관리자 챗봇 추론 설정")
class ChatInferencePropertiesTest {

  @Test
  @DisplayName("기존 추론 URL 환경 변수는 Spring AI 공통 연결 주소에 그대로 바인딩된다")
  void deploymentBaseUrlEnvironment_bindsSpringAiCommonEndpoint() throws IOException {
    assertThat(
            resolveApplicationProperty(
                "spring.ai.openai.base-url",
                Map.of("CHAT_INFERENCE_BASE_URL", "https://inference.example.com/v1")))
        .isEqualTo("https://inference.example.com/v1");
  }

  @Test
  @DisplayName("관리자 전용 추론 URL 환경 변수는 기존 공통 URL보다 우선한다")
  void adminBaseUrlEnvironment_overridesSharedEndpoint() throws IOException {
    assertThat(
            resolveApplicationProperty(
                "spring.ai.openai.base-url",
                Map.of(
                    "CHAT_ADMIN_INFERENCE_BASE_URL",
                    "https://admin-inference.example.com/v1",
                    "CHAT_INFERENCE_BASE_URL",
                    "https://shared-inference.example.com/v1")))
        .isEqualTo("https://admin-inference.example.com/v1");
  }

  @Test
  @DisplayName("루프백 추론 주소는 명시 설정 없이도 로컬 전용 경로로 인정한다")
  void loopbackBaseUrl_isLocalOnlyRoute() {
    ChatInferenceProperties properties = new ChatInferenceProperties();

    assertThat(properties.isLocalOnlyRoute("http://127.0.0.1:11434/v1")).isTrue();
  }

  @Test
  @DisplayName("원격 추론 주소는 서버 계약을 명시해야 로컬 전용 경로로 인정한다")
  void remoteBaseUrl_requiresExplicitLocalOnlyContract() {
    ChatInferenceProperties properties = new ChatInferenceProperties();
    assertThat(properties.isLocalOnlyRoute("https://api.example.com/v1")).isFalse();

    properties.setLocalOnlyRoute(true);
    assertThat(properties.isLocalOnlyRoute("https://api.example.com/v1")).isTrue();
  }

  @Test
  @DisplayName("사고 수준 설정이 비어 있으면 기동을 거부한다")
  void blankReasoningEffort_isRejected() {
    ChatInferenceProperties properties = new ChatInferenceProperties();
    properties.setReasoningEffort(" ");

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("reasoning-effort");
  }

  @Test
  @DisplayName("입력·답변·안전 여유 합계가 컨텍스트 창을 넘으면 기동을 거부한다")
  void contextBudgetOverWindow_isRejected() {
    ChatInferenceProperties properties = new ChatInferenceProperties();
    properties.getContext().setInputTokenLimit(6_001);

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("컨텍스트 창");
  }

  @Test
  @DisplayName("단계 출력 토큰이 답변 예약 토큰을 넘으면 기동을 거부한다")
  void outputBudgetOverReserve_isRejected() {
    ChatInferenceProperties properties = new ChatInferenceProperties();
    properties.setAnswerMaxTokens(1_501);

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("답변 예약");
  }

  private String resolveApplicationProperty(String name, Map<String, Object> environment)
      throws IOException {
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(new MapPropertySource("test-environment", environment));
    sources.addLast(
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"))
            .getFirst());
    return new PropertySourcesPropertyResolver(sources).getProperty(name);
  }
}

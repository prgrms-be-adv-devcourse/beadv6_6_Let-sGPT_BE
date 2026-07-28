package com.openat.recommendation.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

class InferenceApiKeyPropertyTest {

  @Test
  void INFERENCE_API_KEY가_있으면_OPENAI_API_KEY보다_우선한다() throws IOException {
    assertThat(resolveApiKey(Map.of("INFERENCE_API_KEY", "inference-key", "OPENAI_API_KEY", "openai-key")))
        .isEqualTo("inference-key");
  }

  @Test
  void INFERENCE_API_KEY가_없으면_OPENAI_API_KEY로_폴백한다() throws IOException {
    assertThat(resolveApiKey(Map.of("OPENAI_API_KEY", "openai-key"))).isEqualTo("openai-key");
  }

  @Test
  void 두_키가_모두_없으면_빈_문자열이다() throws IOException {
    assertThat(resolveApiKey(Map.of())).isEmpty();
  }

  @Test
  void 빈_INFERENCE_API_KEY는_OPENAI_API_KEY로_폴백하지_않는다() throws IOException {
    assertThat(resolveApiKey(Map.of("INFERENCE_API_KEY", "", "OPENAI_API_KEY", "openai-key")))
        .isEmpty();
  }

  private String resolveApiKey(Map<String, Object> environment) throws IOException {
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(new MapPropertySource("test-environment", environment));
    sources.addLast(
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"))
            .getFirst());
    return new PropertySourcesPropertyResolver(sources).getProperty("inference.api-key");
  }
}

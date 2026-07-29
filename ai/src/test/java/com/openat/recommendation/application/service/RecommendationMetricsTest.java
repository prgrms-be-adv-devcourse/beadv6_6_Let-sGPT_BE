package com.openat.recommendation.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** /actuator/prometheus에 실제로 나가는 이름·태그를 고정한다. */
class RecommendationMetricsTest {

  private PrometheusMeterRegistry registry;
  private RecommendationMetrics metrics;

  @BeforeEach
  void setUp() {
    registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    metrics = new RecommendationMetrics(registry);
  }

  @Test
  @DisplayName("캐시 카운터는 mode·result 태그와 함께 recommendation_cache_total로 노출된다")
  void cacheLookup_isExposedWithModeAndResultTags() {
    metrics.cacheLookup(RecommendationMode.HOME, true);
    metrics.cacheLookup(RecommendationMode.DETAIL, false);

    String scrape = registry.scrape();

    assertThat(scrape)
        .contains("recommendation_cache_total{mode=\"home\",result=\"hit\"} 1.0")
        .contains("recommendation_cache_total{mode=\"detail\",result=\"miss\"} 1.0")
        .contains("recommendation_cache_total{mode=\"home\",result=\"miss\"} 0.0");
  }

  @Test
  @DisplayName("파이프라인·LLM 타이머는 seconds 단위로 노출된다")
  void timers_areExposedInSeconds() {
    metrics.startPipeline(RecommendationMode.DETAIL, true).stop();
    assertThat(metrics.recordLlm(() -> "raw")).isEqualTo("raw");

    String scrape = registry.scrape();

    assertThat(scrape)
        .contains("recommendation_pipeline_seconds_count{mode=\"detail\"} 1")
        .contains("recommendation_llm_seconds_count 1");
  }

  @Test
  @DisplayName("버려진 표본과 비측정 표본은 파이프라인 타이머에 남지 않는다")
  void discardedAndUnmeasuredSamples_areNotRecorded() {
    RecommendationMetrics.PipelineSample discarded =
        metrics.startPipeline(RecommendationMode.DETAIL, true);
    discarded.discard();
    discarded.stop();
    metrics.startPipeline(RecommendationMode.DETAIL, false).stop();

    assertThat(registry.scrape())
        .contains("recommendation_pipeline_seconds_count{mode=\"detail\"} 0");
  }

  @Test
  @DisplayName("폴백·과부하 카운터는 원천 태그와 함께 노출된다")
  void fallbackAndOverloaded_areExposed() {
    metrics.fallback(RecommendationMode.HOME, "llm-failed");
    metrics.overloaded(true);
    metrics.overloaded(false);

    String scrape = registry.scrape();

    assertThat(scrape)
        .contains("recommendation_fallback_total{mode=\"home\",reason=\"llm-failed\"} 1.0")
        .contains("recommendation_overloaded_total{source=\"request\"} 1.0")
        .contains("recommendation_overloaded_total{source=\"background\"} 1.0");
  }

  @Test
  @DisplayName("빈 응답 카운터는 mode·reason 태그와 함께 노출된다")
  void empty_isExposedWithModeAndReasonTags() {
    metrics.empty(RecommendationMode.HOME, "no-seeds");
    metrics.empty(RecommendationMode.DETAIL, "no-candidates");

    String scrape = registry.scrape();

    assertThat(scrape)
        .contains("recommendation_empty_total{mode=\"home\",reason=\"no-seeds\"} 1.0")
        .contains("recommendation_empty_total{mode=\"detail\",reason=\"no-candidates\"} 1.0");
  }
}

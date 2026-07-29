package com.openat.recommendation.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 추천 파이프라인 계측. HTTP 홉은 {@code http_client_requests}에 이미 잡혀 중복 Timer를 두지 않는다.
 *
 * <p>LLM만 예외로 도메인 이름 Timer를 둔다 — {@code client_name} 태그가 환경마다 달라 쿼리가 깨진다.
 */
@Component
public class RecommendationMetrics {

  private static final String CACHE = "recommendation.cache";
  private static final String PIPELINE = "recommendation.pipeline";
  private static final String LLM = "recommendation.llm";
  private static final String FALLBACK = "recommendation.fallback";
  private static final String LAST_RESORT = "recommendation.last-resort";
  private static final String EMPTY = "recommendation.empty";
  private static final String OVERLOADED = "recommendation.overloaded";
  private static final String SEED_REFRESH = "recommendation.seed-refresh";
  private static final String SEED_SALVAGE = "recommendation.seed-salvage";

  private final MeterRegistry registry;
  private final Map<RecommendationMode, Counter> cacheHits =
      new EnumMap<>(RecommendationMode.class);
  private final Map<RecommendationMode, Counter> cacheMisses =
      new EnumMap<>(RecommendationMode.class);
  private final Map<RecommendationMode, Timer> pipelineTimers =
      new EnumMap<>(RecommendationMode.class);
  private final Timer llmTimer;

  public RecommendationMetrics(MeterRegistry registry) {
    this.registry = registry;
    for (RecommendationMode mode : RecommendationMode.values()) {
      cacheHits.put(mode, registry.counter(CACHE, "mode", mode.tag(), "result", "hit"));
      cacheMisses.put(mode, registry.counter(CACHE, "mode", mode.tag(), "result", "miss"));
      pipelineTimers.put(mode, histogram(Timer.builder(PIPELINE).tag("mode", mode.tag())));
    }
    this.llmTimer = histogram(Timer.builder(LLM));
  }

  // p95를 Prometheus에서 계산하려면 히스토그램이 필요하다. 버킷은 관심 구간으로 좁혀 시계열을 아낀다.
  private Timer histogram(Timer.Builder builder) {
    return builder
        .publishPercentileHistogram()
        .minimumExpectedValue(Duration.ofMillis(10))
        .maximumExpectedValue(Duration.ofSeconds(10))
        .register(registry);
  }

  /** 홈은 회원별 키, 상세는 상품별 공유 키라 적중률 특성이 달라 mode로 나눠 센다. */
  void cacheLookup(RecommendationMode mode, boolean hit) {
    (hit ? cacheHits : cacheMisses).get(mode).increment();
  }

  /** {@code measured=false}는 프리배치·배경 재계산 — 사용자 지연 분포를 왜곡하지 않게 빼 둔다. */
  PipelineSample startPipeline(RecommendationMode mode, boolean measured) {
    return measured
        ? new PipelineSample(pipelineTimers.get(mode), Timer.start(registry))
        : new PipelineSample(null, null);
  }

  /** ≈0ms 요청이 표본에 섞이면 과부하가 심할수록 지연이 낮아 보이므로, 그런 경로는 버린다. */
  static final class PipelineSample {

    private final Timer timer;
    private final Timer.Sample sample;
    private boolean discarded;

    private PipelineSample(Timer timer, Timer.Sample sample) {
      this.timer = timer;
      this.sample = sample;
    }

    void discard() {
      discarded = true;
    }

    void stop() {
      if (sample == null || discarded) {
        return;
      }
      sample.stop(timer);
    }
  }

  public <T> T recordLlm(Supplier<T> call) {
    Timer.Sample sample = Timer.start(registry);
    try {
      return call.get();
    } finally {
      sample.stop(llmTimer);
    }
  }

  /** reason은 코드에 고정된 문자열만 들어온다 — 태그 카디널리티는 유한해야 한다. */
  void fallback(RecommendationMode mode, String reason) {
    registry.counter(FALLBACK, "mode", mode.tag(), "reason", reason).increment();
  }

  /** fallback의 reason은 어느 단계까지 내려갔는지 구분하지 못하므로 별도 카운터로 센다. */
  void lastResort(RecommendationMode mode, String reason) {
    registry.counter(LAST_RESORT, "mode", mode.tag(), "reason", reason).increment();
  }

  void empty(RecommendationMode mode, String reason) {
    registry.counter(EMPTY, "mode", mode.tag(), "reason", reason).increment();
  }

  void overloaded(boolean requestPath) {
    registry.counter(OVERLOADED, "source", requestPath ? "request" : "background").increment();
  }

  /** 신호 실패가 가중치 캐시에 어떻게 반영됐는지를 센다. outcome은 코드에 고정된 4개뿐이다. */
  void seedRefresh(String outcome) {
    registry.counter(SEED_REFRESH, "outcome", outcome).increment();
  }

  /** 실패한 쪽 시드를 얼마나 살려 썼는지. expired=salvage-max-age 초과, superseded=완전 데이터 선점. */
  void seedSalvage(String outcome) {
    registry.counter(SEED_SALVAGE, "outcome", outcome).increment();
  }
}

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
 * 추천 파이프라인 계측. 요청당 INFO 로그를 대체하는 측정 수단이다. 이름은 payment 모듈 관례를
 * 따라 점 표기로 두고, Prometheus 노출 시 {@code recommendation_cache_total} 형태가 된다.
 *
 * <p>HTTP 홉(검색·상품·seed·LLM)은 auto-configured {@code RestClient.Builder} 덕에
 * {@code http_client_requests}로 이미 자동 계측되므로 중복 Timer를 두지 않는다. LLM만 예외로
 * 도메인 이름의 Timer를 따로 둔다 — 미스 응답시간에서 LLM 비중을 뽑는 게 계측의 주 목적이고,
 * {@code client_name} 태그는 환경(OpenAI/모의 서버)마다 달라 쿼리가 깨진다.
 */
@Component
public class RecommendationMetrics {

  private static final String CACHE = "recommendation.cache";
  private static final String PIPELINE = "recommendation.pipeline";
  private static final String LLM = "recommendation.llm";
  private static final String FALLBACK = "recommendation.fallback";
  private static final String LAST_RESORT = "recommendation.last-resort";
  private static final String OVERLOADED = "recommendation.overloaded";
  private static final String SEED_REFRESH = "recommendation.seed-refresh";
  private static final String SEED_SALVAGE = "recommendation.seed-salvage";

  private final MeterRegistry registry;
  private final Map<RecommendationMode, Counter> cacheHits = new EnumMap<>(RecommendationMode.class);
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
      pipelineTimers.put(
          mode, histogram(Timer.builder(PIPELINE).tag("mode", mode.tag())));
    }
    this.llmTimer = histogram(Timer.builder(LLM));
  }

  // 백분위(p95)를 Prometheus에서 계산할 수 있게 히스토그램을 낸다. 버킷 수를 관심 구간으로
  // 좁혀 시계열 증가를 억제한다.
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

  /**
   * 파이프라인 타이머 표본. 세마포어 허가를 얻은 뒤에만 시작한다. {@code measured=false}는 요청
   * 경로가 아닌 프리배치·배경 재계산으로, 사용자 지연 분포를 왜곡하지 않도록 기록하지 않는다.
   */
  PipelineSample startPipeline(RecommendationMode mode, boolean measured) {
    return measured
        ? new PipelineSample(pipelineTimers.get(mode), Timer.start(registry))
        : new PipelineSample(null, null);
  }

  /**
   * 실제로 검색·LLM을 태운 요청만 타이머에 남기기 위한 표본. 셰딩된 요청이나 시드가 없어
   * 파이프라인을 아예 타지 않는 요청은 ≈0ms라, 표본에 섞이면 과부하가 심할수록 지연이 낮아
   * 보이는 오염이 생긴다.
   */
  static final class PipelineSample {

    private final Timer timer;
    private final Timer.Sample sample;
    private boolean discarded;

    private PipelineSample(Timer timer, Timer.Sample sample) {
      this.timer = timer;
      this.sample = sample;
    }

    /** 파이프라인을 타지 않고 반환하는 경로는 표본에서 뺀다. */
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

  /** reason은 코드에 고정된 문자열만 들어온다(카디널리티 유한). */
  void fallback(RecommendationMode mode, String reason) {
    registry.counter(FALLBACK, "mode", mode.tag(), "reason", reason).increment();
  }

  /**
   * 폴백 중에서도 열린 드롭을 못 찾아 드롭이 아닌 최신 상품까지 내려간 경우. {@code fallback}의
   * reason은 "왜 폴백했나"만 담아 어느 단계까지 내려갔는지 구분되지 않으므로 별도 카운터로 센다.
   */
  void lastResort(RecommendationMode mode, String reason) {
    registry.counter(LAST_RESORT, "mode", mode.tag(), "reason", reason).increment();
  }

  void overloaded(boolean requestPath) {
    registry
        .counter(OVERLOADED, "source", requestPath ? "request" : "background")
        .increment();
  }

  /**
   * 시드 갱신이 어느 신호를 빼고 끝났는지. 신호 HTTP 실패 자체는 {@code http_client_requests}에
   * 이미 보이므로, 여기서는 그 실패가 가중치 캐시에 어떻게 반영됐는지(완전/부분/미기록)를 센다.
   * outcome은 코드에 고정된 4개 문자열뿐이다.
   */
  void seedRefresh(String outcome) {
    registry.counter(SEED_REFRESH, "outcome", outcome).increment();
  }

  /**
   * 한쪽 신호가 실패했을 때 기존 캐시에서 실패한 쪽 시드를 얼마나 살려 썼는지. {@code expired}가
   * 늘면 연쇄 상한(salvage-max-age)에 걸려 시드를 버리고 있다는 뜻이고, {@code superseded}는 그
   * 사이 다른 스레드가 완전 데이터를 저장해 부분 저장을 생략한 경우다. outcome은 코드에 고정된
   * 4개 문자열뿐이다.
   */
  void seedSalvage(String outcome) {
    registry.counter(SEED_SALVAGE, "outcome", outcome).increment();
  }
}

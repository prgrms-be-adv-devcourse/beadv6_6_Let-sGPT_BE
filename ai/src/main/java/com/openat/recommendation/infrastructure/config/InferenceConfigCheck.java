package com.openat.recommendation.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 시 추론(LLM) 설정을 한 번 찍어 준다.
 *
 * <p>키가 비어 있으면 LLM 호출이 401로 실패하고 전 요청이 폴백으로 떨어지는데, 폴백은 200 OK라
 * 헬스체크·메트릭 어디에도 안 잡힌다. 화면에는 "추천할 상품이 없습니다"만 뜬다. 그래서 기동
 * 로그에서 놓칠 수 없게 배너로 남긴다. 키 값 자체는 절대 찍지 않는다.
 */
@Component
public class InferenceConfigCheck {

  private static final Logger log = LoggerFactory.getLogger(InferenceConfigCheck.class);

  private final String baseUrl;
  private final String model;
  private final String apiKey;

  public InferenceConfigCheck(
      @Value("${inference.base-url}") String baseUrl,
      @Value("${inference.model}") String model,
      @Value("${inference.api-key:}") String apiKey) {
    this.baseUrl = baseUrl;
    this.model = model;
    this.apiKey = apiKey;
  }

  public boolean apiKeyMissing() {
    return apiKey == null || apiKey.isBlank();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void report() {
    if (apiKeyMissing()) {
      log.error(
          """

              ****************************************************************
              * INFERENCE API KEY IS NOT SET (inference.api-key / INFERENCE_API_KEY)
              * base-url={} model={}
              * 모든 추천 요청이 LLM 호출 실패 후 폴백으로 떨어진다.
              * 폴백 후보가 없으면 화면에는 "추천할 상품이 없습니다"만 보인다.
              * 배포 환경변수에 INFERENCE_API_KEY를 채워라.
              ****************************************************************
              """,
          baseUrl,
          model);
      return;
    }
    log.info("inference configured: base-url={}, model={}, api-key=set", baseUrl, model);
  }
}

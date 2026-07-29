package com.openat.order.infrastructure.kafka.publisher;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.HashMap;
import java.util.Map;

/**
 * Outbox 적재 시점의 W3C {@code traceparent}를 캡처하고, 폴링 발행 시점에 그 문맥을 복원한다.
 *
 * <p>outbox는 원 요청 트랜잭션에서 행을 적재하고 별도 폴링 스케줄러가 발행하기 때문에, 아무 조치가
 * 없으면 producer 스팬의 부모가 원 주문 요청이 아니라 폴링 tick(@Scheduled) 트레이스가 된다.
 * 적재 시점의 traceparent를 저장해 두었다가 발행 직전에 {@link #restore(String)}로 그 문맥을
 * 현재 문맥으로 만들면, KafkaTemplate Observation이 만드는 producer 스팬이 원 요청 트레이스의
 * 자식이 되고 레코드에 주입되는 traceparent 헤더도 원 요청 것으로 나간다.
 *
 * <p>Micrometer Tracing OTel 브릿지는 활성 스팬을 OpenTelemetry {@link Context}에 담으므로,
 * 표준 {@link W3CTraceContextPropagator}로 현재 Context를 캐리어에 inject/extract하는 것으로
 * 캡처와 복원이 모두 가능하다. 트레이스 문맥이 없으면(비활성/샘플 제외) 캡처는 null을 반환하고
 * 복원은 아무 것도 하지 않는(no-op) 스코프를 돌려주므로, 폴백 경로가 깨지지 않는다.
 */
final class OutboxTracePropagation {

  private static final String TRACEPARENT = "traceparent";
  private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();

  private static final TextMapGetter<Map<String, String>> GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier == null ? null : carrier.get(key);
        }
      };

  private OutboxTracePropagation() {}

  /** 현재 트레이스 문맥의 W3C traceparent 문자열. 문맥이 없으면 null. */
  static String currentTraceParent() {
    Map<String, String> carrier = new HashMap<>();
    PROPAGATOR.inject(Context.current(), carrier, Map::put);
    return carrier.get(TRACEPARENT);
  }

  /**
   * 저장된 traceparent로 트레이스 문맥을 복원한 스코프를 연다. try-with-resources로 감싸 그 안에서
   * {@code kafkaTemplate.send(...)}를 호출하면 producer 스팬이 원 요청의 자식이 된다. traceparent가
   * 없으면 아무 것도 바꾸지 않는 no-op 스코프를 반환한다.
   */
  static Scope restore(String traceParent) {
    if (traceParent == null || traceParent.isBlank()) {
      return Scope.noop();
    }
    Context extracted = PROPAGATOR.extract(Context.root(), Map.of(TRACEPARENT, traceParent), GETTER);
    return extracted.makeCurrent();
  }
}

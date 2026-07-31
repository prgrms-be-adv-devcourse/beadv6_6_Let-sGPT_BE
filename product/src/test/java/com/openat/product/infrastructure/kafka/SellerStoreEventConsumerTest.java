package com.openat.product.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.config.JacksonConfig;
import com.openat.product.application.usecase.SellerStoreProjectionCommandUseCase;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("판매자 상점 이벤트 소비자")
class SellerStoreEventConsumerTest {

  @Mock private SellerStoreProjectionCommandUseCase commandUseCase;

  private SellerStoreEventConsumer consumer;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new JacksonConfig().objectMapper();
    consumer = new SellerStoreEventConsumer(objectMapper, commandUseCase);
  }

  @Test
  @DisplayName("판매자 등록 이벤트를 로컬 투영 갱신 명령에 전달한다")
  void onSellerStoreChanged_registeredEvent_upsertsProjection() {
    UUID sellerInfoId = UUID.randomUUID();
    String payload =
        """
        {
          "sellerInfoId": "%s",
          "storeName": "스프링 스튜디오"
        }
        """
            .formatted(sellerInfoId);

    consumer.onSellerStoreChanged(record("seller_registered_events", payload));

    then(commandUseCase).should().upsert(sellerInfoId, "스프링 스튜디오");
  }

  @Test
  @DisplayName("판매자 수정 이벤트를 같은 계약으로 소비한다")
  void onSellerStoreChanged_updatedEvent_upsertsProjection() {
    UUID sellerInfoId = UUID.randomUUID();
    String payload =
        """
        {
          "sellerInfoId": "%s",
          "storeName": "변경된 상점"
        }
        """
            .formatted(sellerInfoId);

    consumer.onSellerStoreChanged(record("seller_updated_events", payload));

    then(commandUseCase).should().upsert(sellerInfoId, "변경된 상점");
  }

  @Test
  @DisplayName("잘못된 payload는 예외를 전파하고 투영을 갱신하지 않는다")
  void onSellerStoreChanged_invalidPayload_throwsWithoutUpsert() {
    ConsumerRecord<String, String> record = record("seller_updated_events", "{");

    assertThatThrownBy(() -> consumer.onSellerStoreChanged(record))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("seller store event consume failed");
    then(commandUseCase).shouldHaveNoInteractions();
  }

  private ConsumerRecord<String, String> record(String topic, String payload) {
    return new ConsumerRecord<>(topic, 0, 0L, null, payload);
  }
}

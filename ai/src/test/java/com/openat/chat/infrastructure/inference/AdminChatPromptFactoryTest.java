package com.openat.chat.infrastructure.inference;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.service.OperationContextRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("관리자 챗봇 단계별 프롬프트")
class AdminChatPromptFactoryTest {

  private final ChatInferenceProperties properties = new ChatInferenceProperties();
  private final AdminChatPromptFactory factory =
      new AdminChatPromptFactory(
          new OperationContextRegistry(),
          properties,
          JsonMapper.builder().findAndAddModules().build(),
          Clock.fixed(Instant.parse("2026-07-24T01:02:03Z"), ZoneOffset.UTC));

  @Test
  @DisplayName("첫 질문은 1차 영역 선택 규칙과 함께 그대로 전달한다")
  void routing_firstQuestion_usesOriginalQuestion() {
    ChatCommand command = new ChatCommand(UUID.randomUUID(), "admin", Set.of("ADMIN"), "오늘 주문은?");

    assertThat(factory.routingSystem())
        .contains("도구 없이", "loadInternalDataSchemas", "2026-07-24 10:02:03");
    assertThat(factory.routingUser(command)).isEqualTo("오늘 주문은?");
  }

  @Test
  @DisplayName("상품과 주문의 공개 운영 정보는 개인정보로 거절하지 않도록 안내한다")
  void routing_publicCommerceFields_routesToInternalSchema() {
    assertThat(factory.routingSystem())
        .contains("상품명·카테고리·공개 상품/드롭 식별자·가격·수량·상태")
        .contains("기간 내 주문별 상품명·단가·총액")
        .contains("여러 주문의 제한된 행 목록은 ORDER_SALES 영역으로 처리한다");
  }

  @Test
  @DisplayName("날씨는 1차 요청에서 행정구역 대표 좌표까지 채우도록 안내한다")
  void routing_weather_fillsRepresentativeCoordinates() {
    assertThat(factory.routingSystem())
        .contains("WGS84 대표 위도·경도까지 채운다")
        .contains("같은 이름의 지역이 여러 곳이면 도구를 호출하지 말고 시·도를 짧게 확인한다");
  }

  @Test
  @DisplayName("최종 답변은 안전한 여러 행을 최대 20행의 표로 표현할 수 있다")
  void answer_multipleSafeRows_allowsBoundedTable() {
    assertThat(factory.answerSystem())
        .contains("최대 20행의 간결한 Markdown 표")
        .contains("JSON, SQL, 코드 블록, 카드")
        .contains("도구명·스키마·처리 단계는 노출하지 않는다");
  }

  @Test
  @DisplayName("주문 기간과 개별 사건 기간의 시간 기준을 구분한다")
  void binding_distinguishesOrderAndEventTimeFields() {
    assertThat(factory.bindingSystem("schema", true))
        .contains("주문 자체의 기간")
        .contains("CREATED_AT")
        .contains("PAID_AT, COMPLETED_AT, CANCELLED_AT");
  }

  @Test
  @DisplayName("후속 질문에는 길이가 제한된 직전 완료 대화를 참고 문맥으로 표시한다")
  void routing_followUp_marksPreviousTurnUntrustedAndTruncates() {
    properties.getContext().setPreviousQuestionMaxCharacters(5);
    properties.getContext().setPreviousAnswerMaxCharacters(7);
    ChatCommand command =
        new ChatCommand(
            UUID.randomUUID(),
            "admin",
            Set.of("ADMIN"),
            "그중 많이 팔린 상품은?",
            new ChatCommand.PreviousTurn("지난달 주문은?", "지난달 주문은 40건이야."));

    String prompt = factory.routingUser(command);

    assertThat(prompt)
        .contains("참고일 뿐")
        .contains("<previous-question>지난달 주</previous-question>")
        .contains("<previous-answer>지난달 주문은</previous-answer>")
        .contains("<current-question>그중 많이 팔린 상품은?</current-question>");
  }
}

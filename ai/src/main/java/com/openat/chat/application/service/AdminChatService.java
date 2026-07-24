package com.openat.chat.application.service;

import com.openat.chat.application.dto.ChatCapabilitiesInfo;
import com.openat.chat.application.dto.ChatCapabilityInfo;
import com.openat.chat.application.dto.ChatCapabilityInfo.Availability;
import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.ChatStreamEvent;
import com.openat.chat.application.port.AdminDataQueryPort;
import com.openat.chat.application.port.ChatEventSink;
import com.openat.chat.application.port.ChatStreamClosedException;
import com.openat.chat.application.port.WeatherPort;
import com.openat.chat.application.port.WebSearchPort;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AdminChatService {

  private static final Logger log = LoggerFactory.getLogger(AdminChatService.class);

  public static final int MAX_MESSAGE_LENGTH = 2000;
  public static final int MAX_PREVIOUS_QUESTION_LENGTH = 300;
  public static final int MAX_PREVIOUS_ANSWER_LENGTH = 800;

  private final AdminChatOrchestrator orchestrator;
  private final AdminChatSecurityPolicy securityPolicy;
  private final AdminDataQueryPort dataQueryPort;
  private final WeatherPort weatherPort;
  private final WebSearchPort webSearchPort;

  public AdminChatService(
      AdminChatOrchestrator orchestrator,
      AdminChatSecurityPolicy securityPolicy,
      AdminDataQueryPort dataQueryPort,
      WeatherPort weatherPort,
      WebSearchPort webSearchPort) {
    this.orchestrator = orchestrator;
    this.securityPolicy = securityPolicy;
    this.dataQueryPort = dataQueryPort;
    this.weatherPort = weatherPort;
    this.webSearchPort = webSearchPort;
  }

  public void execute(ChatCommand command, ChatEventSink sink, ChatRequestDeadline deadline) {
    AdminChatSecurityPolicy.PolicyDecision policy = securityPolicy.evaluate(command.message());
    if (!policy.allowed()) {
      sink.terminate(
          ChatStreamEvent.error(
              command.requestId(),
              "SECURITY_POLICY_REJECTED",
              policyMessage(policy.reasonCode()),
              false,
              false));
      return;
    }
    if (!orchestrator.isAvailable()) {
      sink.terminate(
          ChatStreamEvent.error(
              command.requestId(),
              "CHAT_INFERENCE_NOT_CONFIGURED",
              "로컬 추론 서버 설정을 확인해 줘.",
              false,
              false));
      return;
    }
    ChatCommand safeCommand =
        command
            .previousTurnContext()
            .filter(
                previous ->
                    securityPolicy.isSafePreviousTurn(previous.question(), previous.answer()))
            .map(ignored -> command)
            .orElseGet(command::withoutPreviousTurn);
    try {
      orchestrator.execute(safeCommand, sink, deadline);
    } catch (ChatStreamClosedException ignored) {
      // The client has already disconnected, so there is no terminal event to send.
    } catch (RuntimeException exception) {
      log.warn("Admin chat request failed. requestId={}", command.requestId(), exception);
      sink.terminate(
          partial ->
              ChatStreamEvent.error(
                  command.requestId(),
                  "CHAT_PROCESSING_FAILED",
                  "답변을 만드는 중 문제가 생겼어. 잠시 후 다시 시도해 줘.",
                  true,
                  partial));
    }
  }

  public ChatCapabilitiesInfo getCapabilities() {
    boolean inferenceAvailable = orchestrator.isAvailable();
    return new ChatCapabilitiesInfo(
        false,
        MAX_MESSAGE_LENGTH,
        "운영과 관리에 필요한 내용을 편하게 물어봐.",
        List.of(
            capability(
                "ORDER",
                "주문·판매",
                "주문 건수·수량·금액·실패율·상품 순위와 공개 주문번호 한 건의 처리 흐름을 조회해.",
                inferenceAvailable && dataQueryPort.isAvailable(),
                List.of("지난달 주문금액과 주별 추이를 알려줘", "가장 많이 팔린 상품은?")),
            capability(
                "PAYMENT_REFUND",
                "결제·환불",
                "결제 승인·실패·금액과 환불 현황을 안전한 집계로 조회해.",
                inferenceAvailable && dataQueryPort.isAvailable(),
                List.of("지난주 결제 성공률과 환불액은?")),
            capability(
                "SETTLEMENT",
                "정산·대사",
                "수수료·환불·조정·최종 지급액과 배치·대사 현황을 집계해.",
                inferenceAvailable && dataQueryPort.isAvailable(),
                List.of("지난달 최종 정산액과 수수료를 알려줘")),
            capability(
                "MEMBER",
                "회원 집계",
                "개인정보 없이 현재·신규·탈퇴 회원 수를 집계해.",
                inferenceAvailable && dataQueryPort.isAvailable(),
                List.of("이번 달 신규 가입과 탈퇴 추이를 알려줘")),
            capability(
                "PRODUCT_DROP",
                "상품과 드롭",
                "상품 구성과 드롭 재고·소진 현황을 집계해.",
                inferenceAvailable && dataQueryPort.isAvailable(),
                List.of("재고 소진율이 높은 상품을 알려줘")),
            capability(
                "RELIABILITY",
                "처리 안정성",
                "주문 사가와 내부 이벤트 적체를 비식별 집계로 점검해.",
                inferenceAvailable && dataQueryPort.isAvailable(),
                List.of("10분 넘게 보상 중인 주문 사가가 있어?")),
            capability(
                "OPERATIONS",
                "운영 안내",
                "확인된 OPENAT 운영 문서로 점검 절차와 활용 방법을 안내해.",
                inferenceAvailable,
                List.of("관리자가 매일 뭘 확인하면 좋을까?")),
            capability(
                "WEATHER",
                "날씨",
                "한국 지역의 오늘 또는 내일 예보를 조회해.",
                inferenceAvailable && weatherPort.isAvailable(),
                List.of("오늘 부천 날씨는?")),
            capability(
                "WEB_SEARCH",
                "웹 검색",
                "최신 뉴스와 현재 공개 정보를 찾아 자연어로 정리해.",
                inferenceAvailable && webSearchPort.isAvailable(),
                List.of("오늘 주요 AI 뉴스를 찾아줘")),
            capability(
                "GENERAL",
                "일반 답변",
                "시점에 영향받지 않는 일반 지식을 바로 설명해.",
                inferenceAvailable,
                List.of("엑셀이 뭐야?"))));
  }

  private ChatCapabilityInfo capability(
      String type,
      String label,
      String description,
      boolean available,
      List<String> sampleQuestions) {
    return new ChatCapabilityInfo(
        type,
        label,
        description,
        available ? Availability.ACTIVE : Availability.UNAVAILABLE,
        sampleQuestions);
  }

  private String policyMessage(String reasonCode) {
    return switch (reasonCode) {
      case "PROMPT_POLICY_VIOLATION" -> "이 요청은 안전한 읽기 범위를 벗어났어. 공개 주문번호나 비식별 집계 기준으로 다시 적어 줘.";
      default -> "질문을 처리할 수 없어. 내용을 확인해서 다시 적어 줘.";
    };
  }
}

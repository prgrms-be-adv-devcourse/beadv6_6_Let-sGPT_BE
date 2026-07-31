package com.openat.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.EvidenceSegment;
import com.openat.chat.application.exception.AdminChatExecutionException;
import com.openat.chat.application.port.AdminAnalyticsExecutionPort;
import com.openat.chat.application.port.AdminChatInferencePort;
import com.openat.chat.application.port.AdminChatInferencePort.BindingResponse;
import com.openat.chat.application.port.AdminChatInferencePort.BindingStatus;
import com.openat.chat.application.port.AdminChatInferencePort.QueryBinding;
import com.openat.chat.application.port.AdminChatInferencePort.RoutingResponse;
import com.openat.chat.application.port.AdminChatInferencePort.ToolInvocation;
import com.openat.chat.application.port.AdminInitialToolPort;
import com.openat.chat.application.port.AdminInitialToolPort.InitialToolResult;
import com.openat.chat.domain.query.InternalDataDomain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("관리자 챗봇 고정 단계 오케스트레이터")
class AdminChatOrchestratorTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC);

  @Mock AdminChatInferencePort inference;
  @Mock AdminInitialToolPort initialTools;
  @Mock AdminAnalyticsExecutionPort analytics;

  private AdminChatOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    orchestrator = new AdminChatOrchestrator(inference, initialTools, analytics);
  }

  @Test
  @DisplayName("일반 질문은 1차 본문만 잘라서 스트리밍하고 끝낸다")
  void execute_general_usesOneInferenceRound() {
    ChatCommand command = command("엑셀이 뭐야?");
    RecordingChatEventSink sink = new RecordingChatEventSink();
    given(inference.route(command, deadline()))
        .willReturn(new RoutingResponse("엑셀은 스프레드시트 도구야.", List.of(), "stop"));

    orchestrator.execute(command, sink, deadline());

    assertThat(sink.eventNames()).containsExactly("status", "status", "delta", "done");
    assertThat(answer(sink)).isEqualTo("엑셀은 스프레드시트 도구야.");
    verify(initialTools, never()).execute(any(), anyList(), any(), any());
    verify(inference, never()).streamAnswer(any(), anyList(), any(), any());
  }

  @Test
  @DisplayName("토큰 제한으로 잘린 1차 일반 답변은 사용자에게 전달하지 않는다")
  void execute_incompleteGeneralAnswer_failsBeforeEmission() {
    ChatCommand command = command("엑셀이 뭐야?");
    RecordingChatEventSink sink = new RecordingChatEventSink();
    given(inference.route(command, deadline()))
        .willReturn(new RoutingResponse("엑셀은 스프레드시트", List.of(), "length"));

    assertThatThrownBy(() -> orchestrator.execute(command, sink, deadline()))
        .isInstanceOfSatisfying(
            AdminChatExecutionException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(AdminChatExecutionException.Reason.SELECTION_FAILED));

    assertThat(sink.eventNames()).containsExactly("status");
    assertThat(sink.deltas()).isEmpty();
    verify(initialTools, never()).execute(any(), anyList(), any(), any());
  }

  @Test
  @DisplayName("도구 호출과 함께 온 1차 본문은 폐기하고 검증된 사실로만 답한다")
  void execute_lightTool_discardsHybridContent() {
    ChatCommand command = command("오늘 부천 날씨는?");
    RecordingChatEventSink sink = new RecordingChatEventSink();
    ToolInvocation weather =
        new ToolInvocation(
            "call-1",
            "getWeatherForecast",
            "{\"location\":\"경기도 부천시\",\"latitude\":37.50,\"longitude\":126.78,\"day\":\"TODAY\"}");
    EvidenceSegment evidence = evidence("r1-t01", "WEATHER", false);
    given(inference.route(command, deadline()))
        .willReturn(new RoutingResponse("확인 전 날씨 답변", List.of(weather), "tool_calls"));
    given(initialTools.execute(command, List.of(weather), sink, deadline()))
        .willReturn(new InitialToolResult(Set.of(), List.of(evidence), false, false));
    doAnswer(
            invocation -> {
              java.util.function.Consumer<String> consumer = invocation.getArgument(2);
              consumer.accept("부천은 맑고 27도야.");
              return null;
            })
        .when(inference)
        .streamAnswer(eq(command), eq(List.of(evidence)), any(), eq(deadline()));

    orchestrator.execute(command, sink, deadline());

    assertThat(sink.eventNames()).containsExactly("status", "status", "status", "delta", "done");
    assertThat(answer(sink)).isEqualTo("부천은 맑고 27도야.").doesNotContain("확인 전");
  }

  @Test
  @DisplayName("가벼운 사실을 먼저 전달한 뒤 내부 조회 결과를 중복 없이 이어서 답한다")
  void execute_mixedQuestion_streamsEarlyThenFinal() {
    ChatCommand command = command("오늘 날씨와 지난달 주문 수를 알려줘");
    RecordingChatEventSink sink = new RecordingChatEventSink();
    ToolInvocation selector =
        new ToolInvocation("call-1", "loadInternalDataSchemas", "{\"domains\":[\"ORDER_SALES\"]}");
    EvidenceSegment light =
        evidence("r1-t02", "WEATHER", EvidenceSegment.Status.SUCCESS, false);
    EvidenceSegment selectorFailure =
        evidence(
            "r1-t01",
            "INTERNAL_SCHEMA_SELECTION",
            EvidenceSegment.Status.FAILED,
            false);
    QueryBinding binding =
        new QueryBinding(
            "r2-s01-b01",
            InternalDataDomain.ORDER_SALES,
            BindingStatus.FAILED,
            null,
            "기간을 구조화하지 못함");
    EvidenceSegment failed = evidence("r2-s01-b01", "ORDER_SALES", false);
    AtomicReference<List<EvidenceSegment>> finalEvidence = new AtomicReference<>();

    given(inference.route(command, deadline()))
        .willReturn(new RoutingResponse("", List.of(selector), "tool_calls"));
    given(initialTools.execute(command, List.of(selector), sink, deadline()))
        .willReturn(
            new InitialToolResult(
                Set.of(InternalDataDomain.ORDER_SALES),
                List.of(selectorFailure, light),
                true,
                true));
    given(
            inference.bind(
                command,
                Set.of(InternalDataDomain.ORDER_SALES),
                List.of(selectorFailure, light),
                deadline()))
        .willReturn(
            new BindingResponse("오늘 날씨는 맑아.", Set.of(light.id()), List.of(binding)));
    given(analytics.execute(List.of(binding), deadline())).willReturn(List.of(failed));
    doAnswer(
            invocation -> {
              finalEvidence.set(invocation.getArgument(1));
              java.util.function.Consumer<String> consumer = invocation.getArgument(2);
              consumer.accept("주문 수는 확인하지 못했어.");
              return null;
            })
        .when(inference)
        .streamAnswer(eq(command), anyList(), any(), eq(deadline()));

    orchestrator.execute(command, sink, deadline());

    assertThat(sink.eventNames())
        .containsExactly("status", "status", "status", "delta", "delta", "delta", "done");
    assertThat(answer(sink)).isEqualTo("오늘 날씨는 맑아.\n\n주문 수는 확인하지 못했어.");
    assertThat(finalEvidence.get())
        .extracting(EvidenceSegment::delivered)
        .containsExactly(false, true, false);
  }

  @Test
  @DisplayName("내부 영역 선택이 실패해도 성공한 가벼운 근거는 최종 답변까지 보존한다")
  void execute_failedSelectionWithLightSuccess_preservesEvidence() {
    ChatCommand command = command("오늘 날씨와 지난달 주문 수를 알려줘");
    RecordingChatEventSink sink = new RecordingChatEventSink();
    ToolInvocation selector =
        new ToolInvocation("call-1", "loadInternalDataSchemas", "{\"domains\":[\"UNKNOWN\"]}");
    ToolInvocation weather =
        new ToolInvocation(
            "call-2",
            "getWeatherForecast",
            "{\"location\":\"경기도 부천시\",\"latitude\":37.50,\"longitude\":126.78,\"day\":\"TODAY\"}");
    EvidenceSegment selectorFailure =
        evidence(
            "r1-t01",
            "INTERNAL_SCHEMA_SELECTION",
            EvidenceSegment.Status.FAILED,
            false);
    EvidenceSegment weatherSuccess =
        evidence("r1-t02", "WEATHER", EvidenceSegment.Status.SUCCESS, false);
    AtomicReference<List<EvidenceSegment>> finalEvidence = new AtomicReference<>();

    given(inference.route(command, deadline()))
        .willReturn(new RoutingResponse("", List.of(selector, weather), "tool_calls"));
    given(initialTools.execute(command, List.of(selector, weather), sink, deadline()))
        .willReturn(
            new InitialToolResult(
                Set.of(), List.of(selectorFailure, weatherSuccess), true, true));
    doAnswer(
            invocation -> {
              finalEvidence.set(invocation.getArgument(1));
              java.util.function.Consumer<String> consumer = invocation.getArgument(2);
              consumer.accept("오늘 날씨는 맑지만 주문 범위는 확인하지 못했어.");
              return null;
            })
        .when(inference)
        .streamAnswer(eq(command), anyList(), any(), eq(deadline()));

    orchestrator.execute(command, sink, deadline());

    assertThat(sink.eventNames()).containsExactly("status", "status", "status", "delta", "done");
    assertThat(finalEvidence.get()).containsExactly(selectorFailure, weatherSuccess);
    verify(inference, never()).bind(any(), any(), anyList(), any());
  }

  @Test
  @DisplayName("최종 추론이 빈 스트림이면 정상 완료로 숨기지 않는다")
  void execute_emptyFinalStream_fails() {
    ChatCommand command = command("비트코인 시세는?");
    RecordingChatEventSink sink = new RecordingChatEventSink();
    ToolInvocation tool = new ToolInvocation("call-1", "getCryptoPrice", "{}");
    EvidenceSegment evidence = evidence("r1-t01", "CRYPTO", false);
    given(inference.route(command, deadline()))
        .willReturn(new RoutingResponse("", List.of(tool), "tool_calls"));
    given(initialTools.execute(command, List.of(tool), sink, deadline()))
        .willReturn(new InitialToolResult(Set.of(), List.of(evidence), false, false));

    assertThatThrownBy(() -> orchestrator.execute(command, sink, deadline()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("최종 답변");
  }

  @Test
  @DisplayName("1차가 내부 스키마 선택을 요청했지만 유효 영역이 없으면 즉시 실패한다")
  void execute_invalidSchemaSelection_failsBeforeBinding() {
    ChatCommand command = command("지난달 주문 수는?");
    RecordingChatEventSink sink = new RecordingChatEventSink();
    ToolInvocation selector =
        new ToolInvocation("call-1", "loadInternalDataSchemas", "{\"domains\":[\"UNKNOWN\"]}");
    given(inference.route(command, deadline()))
        .willReturn(new RoutingResponse("", List.of(selector), "tool_calls"));
    given(initialTools.execute(command, List.of(selector), sink, deadline()))
        .willReturn(
            new InitialToolResult(
                Set.of(),
                List.of(evidence("r1-t01", "INTERNAL_SCHEMA_SELECTION", false)),
                true,
                true));

    assertThatThrownBy(() -> orchestrator.execute(command, sink, deadline()))
        .isInstanceOf(AdminChatExecutionException.class)
        .hasMessageContaining("영역");
    verify(inference, never()).bind(any(), any(), anyList(), any());
  }

  private ChatCommand command(String message) {
    return new ChatCommand(UUID.randomUUID(), "admin", Set.of("ROLE_ADMIN"), message);
  }

  private ChatRequestDeadline deadline() {
    return new ChatRequestDeadline(CLOCK.instant().plus(Duration.ofMinutes(2)), CLOCK);
  }

  private EvidenceSegment evidence(String id, String scope, boolean delivered) {
    return evidence(id, scope, EvidenceSegment.Status.FAILED, delivered);
  }

  private EvidenceSegment evidence(
      String id, String scope, EvidenceSegment.Status status, boolean delivered) {
    return new EvidenceSegment(
        id,
        status,
        scope,
        null,
        status == EvidenceSegment.Status.FAILED ? List.of("확인하지 못함") : List.of(),
        "test",
        "",
        delivered);
  }

  private String answer(RecordingChatEventSink sink) {
    return sink.deltas().stream()
        .map(com.openat.chat.application.dto.ChatStreamEvent.DeltaPayload::text)
        .collect(java.util.stream.Collectors.joining());
  }
}

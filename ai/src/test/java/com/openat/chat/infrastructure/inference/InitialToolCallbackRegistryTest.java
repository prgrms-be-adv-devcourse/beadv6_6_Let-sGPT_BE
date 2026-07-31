package com.openat.chat.infrastructure.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.openat.chat.application.dto.AdminDataQueryResult;
import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.ChatStreamEvent;
import com.openat.chat.application.port.AdminChatInferencePort.ToolInvocation;
import com.openat.chat.application.port.AdminDataQueryPort;
import com.openat.chat.application.port.ChatEventSink;
import com.openat.chat.application.service.ExternalSearchPolicy;
import com.openat.chat.application.service.OperationContextRegistry;
import com.openat.chat.domain.query.InternalDataDomain;
import com.openat.chat.infrastructure.inference.tool.AdminDataTools;
import com.openat.chat.infrastructure.inference.tool.CryptoPriceTools;
import com.openat.chat.infrastructure.inference.tool.OperationContextTools;
import com.openat.chat.infrastructure.inference.tool.WeatherTools;
import com.openat.chat.infrastructure.inference.tool.WebSearchTools;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("1차 가벼운 도구 수동 실행 레지스트리")
class InitialToolCallbackRegistryTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC);

  private AdminDataQueryPort dataQueryPort;
  private ExecutorService executor;
  private ChatInferenceProperties properties;
  private InitialToolCallbackRegistry registry;

  @BeforeEach
  void setUp() {
    dataQueryPort = mock(AdminDataQueryPort.class);
    executor = Executors.newFixedThreadPool(4);
    properties = new ChatInferenceProperties();
    registry =
        new InitialToolCallbackRegistry(
            new AdminDataTools(dataQueryPort),
            new CryptoPriceTools(mock(com.openat.chat.application.port.CryptoPricePort.class)),
            new OperationContextTools(new OperationContextRegistry()),
            new WeatherTools(mock(com.openat.chat.application.port.WeatherPort.class)),
            new WebSearchTools(
                mock(com.openat.chat.application.port.WebSearchPort.class),
                new ExternalSearchPolicy()),
            JsonMapper.builder().findAndAddModules().build(),
            executor,
            properties,
            new ChatInferenceMetrics(new SimpleMeterRegistry()));
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @Test
  @DisplayName("영역 일부가 잘못돼도 유효 영역과 정상 도구 결과를 보존한다")
  void execute_partialSchemaSelection_preservesValidSiblings() {
    given(dataQueryPort.isAvailable()).willReturn(true);
    given(dataQueryPort.countExpiredPaymentPendingOrders())
        .willReturn(new AdminDataQueryResult.Metric(2, CLOCK.instant()));
    RecordingSink sink = new RecordingSink();
    List<ToolInvocation> invocations =
        List.of(
            new ToolInvocation(
                "call-1", "loadInternalDataSchemas", "{\"domains\":[\"ORDER_SALES\",\"UNKNOWN\"]}"),
            new ToolInvocation("call-2", "countExpiredPaymentPendingOrders", "{}"));

    var result = registry.execute(command(), invocations, sink, deadline());

    assertThat(result.domains()).containsExactly(InternalDataDomain.ORDER_SALES);
    assertThat(result.schemaSelectionRequested()).isTrue();
    assertThat(result.schemaSelectionFailed()).isTrue();
    assertThat(result.evidence())
        .extracting(segment -> segment.status())
        .containsExactly(
            com.openat.chat.application.dto.EvidenceSegment.Status.FAILED,
            com.openat.chat.application.dto.EvidenceSegment.Status.SUCCESS);
    assertThat(sink.events).isEmpty();
    verify(dataQueryPort).countExpiredPaymentPendingOrders();
  }

  @Test
  @DisplayName("허용되지 않은 도구 하나가 정상 형제 도구 실행을 막지 않는다")
  void execute_unknownTool_keepsSuccessfulSibling() {
    given(dataQueryPort.isAvailable()).willReturn(true);
    given(dataQueryPort.countExpiredPaymentPendingOrders())
        .willReturn(new AdminDataQueryResult.Metric(1, CLOCK.instant()));
    RecordingSink sink = new RecordingSink();

    var result =
        registry.execute(
            command(),
            List.of(
                new ToolInvocation("call-1", "unknownTool", "{}"),
                new ToolInvocation("call-2", "countExpiredPaymentPendingOrders", "{}")),
            sink,
            deadline());

    assertThat(result.evidence())
        .extracting(segment -> segment.status())
        .containsExactly(
            com.openat.chat.application.dto.EvidenceSegment.Status.FAILED,
            com.openat.chat.application.dto.EvidenceSegment.Status.SUCCESS);
    verify(dataQueryPort).countExpiredPaymentPendingOrders();
  }

  @Test
  @DisplayName("한 도구가 stage timeout이어도 완료된 형제 도구 결과를 입력 순서로 보존한다")
  void execute_timedOutTool_keepsSuccessfulSibling() throws Exception {
    properties.setStageTimeout(Duration.ofMillis(500));
    given(dataQueryPort.isAvailable()).willReturn(true);
    CountDownLatch toolStarted = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    given(dataQueryPort.countExpiredPaymentPendingOrders())
        .willAnswer(
            ignored -> {
              toolStarted.countDown();
              try {
                new CountDownLatch(1).await();
                throw new AssertionError("중단되지 않은 도구 작업");
              } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
              }
            });

    var result =
        registry.execute(
            command(),
            List.of(
                new ToolInvocation("call-1", "countExpiredPaymentPendingOrders", "{}"),
                new ToolInvocation(
                    "call-2",
                    "getOpenAtOperationsContext",
                    "{\"contextIds\":[\"PLATFORM\"]}")),
            new RecordingSink(),
            deadline());

    assertThat(toolStarted.getCount()).isZero();
    assertThat(result.evidence())
        .extracting(segment -> segment.status())
        .containsExactly(
            com.openat.chat.application.dto.EvidenceSegment.Status.FAILED,
            com.openat.chat.application.dto.EvidenceSegment.Status.SUCCESS);
    assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
  }

  private ChatCommand command() {
    return new ChatCommand(
        UUID.randomUUID(), "admin", Set.of("ROLE_ADMIN"), "결제기한이 지난 주문과 지난달 주문 수를 알려줘");
  }

  private ChatRequestDeadline deadline() {
    return new ChatRequestDeadline(CLOCK.instant().plus(Duration.ofMinutes(2)), CLOCK);
  }

  private static final class RecordingSink implements ChatEventSink {

    private final List<ChatStreamEvent> events = new ArrayList<>();
    private boolean closed;

    @Override
    public void emit(ChatStreamEvent event) {
      events.add(event);
    }

    @Override
    public boolean terminate(Function<Boolean, ChatStreamEvent> eventFactory) {
      if (closed) {
        return false;
      }
      events.add(eventFactory.apply(false));
      closed = true;
      return true;
    }

    @Override
    public boolean isClosed() {
      return closed;
    }
  }
}

package com.openat.chat.infrastructure.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.openat.chat.application.dto.AdminAnalyticsQueryResult;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.EvidenceSegment;
import com.openat.chat.application.port.AdminAnalyticsQueryPort;
import com.openat.chat.application.port.AdminChatInferencePort.BindingStatus;
import com.openat.chat.application.port.AdminChatInferencePort.QueryBinding;
import com.openat.chat.application.port.AdminChatInferencePort.QuerySpec;
import com.openat.chat.application.service.AdminAnalyticsPlanFactory;
import com.openat.chat.application.service.AdminAnalyticsPlanFactory.PreparedQuery;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dataset;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Query;
import com.openat.chat.domain.query.InternalDataDomain;
import com.openat.chat.infrastructure.inference.tool.AdminAnalyticsFacts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 분석 조회 병렬 실행")
class AdminAnalyticsExecutionAdapterTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC);

  private AdminAnalyticsQueryPort queryPort;
  private AdminAnalyticsPlanFactory planFactory;
  private AdminAnalyticsResultMapper resultMapper;
  private ExecutorService executor;
  private ChatInferenceProperties properties;
  private AdminAnalyticsExecutionAdapter adapter;

  @BeforeEach
  void setUp() {
    queryPort = mock(AdminAnalyticsQueryPort.class);
    planFactory = mock(AdminAnalyticsPlanFactory.class);
    resultMapper = mock(AdminAnalyticsResultMapper.class);
    executor = Executors.newFixedThreadPool(2);
    properties = new ChatInferenceProperties();
    adapter =
        new AdminAnalyticsExecutionAdapter(
            queryPort,
            planFactory,
            resultMapper,
            executor,
            properties,
            new ChatInferenceMetrics(new SimpleMeterRegistry()));
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @Test
  @DisplayName("한 조회가 stage timeout이어도 완료된 형제 조회를 입력 순서로 보존한다")
  void execute_timedOutQuery_keepsSuccessfulSibling() throws Exception {
    properties.setStageTimeout(Duration.ofMillis(500));
    given(queryPort.isAvailable()).willReturn(true);
    QuerySpec firstSpec = mock(QuerySpec.class);
    QuerySpec secondSpec = mock(QuerySpec.class);
    Query firstQuery = mock(Query.class);
    Query secondQuery = mock(Query.class);
    given(firstQuery.dataset()).willReturn(Dataset.ORDER);
    given(secondQuery.dataset()).willReturn(Dataset.ORDER);
    PreparedQuery firstPrepared = new PreparedQuery(firstQuery, "오늘", List.of());
    PreparedQuery secondPrepared = new PreparedQuery(secondQuery, "오늘", List.of());
    given(planFactory.create(firstSpec)).willReturn(firstPrepared);
    given(planFactory.create(secondSpec)).willReturn(secondPrepared);

    CountDownLatch queryStarted = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    given(queryPort.query(firstQuery))
        .willAnswer(
            ignored -> {
              queryStarted.countDown();
              try {
                new CountDownLatch(1).await();
                throw new AssertionError("중단되지 않은 분석 조회");
              } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
              }
            });
    AdminAnalyticsQueryResult successfulResult =
        new AdminAnalyticsQueryResult(List.of(), 0, false, CLOCK.instant());
    AdminAnalyticsFacts successfulFacts = mock(AdminAnalyticsFacts.class);
    given(successfulFacts.asOf()).willReturn("2026-07-24T10:00:00+09:00");
    given(queryPort.query(secondQuery)).willReturn(successfulResult);
    given(resultMapper.map(same(secondPrepared), same(successfulResult)))
        .willReturn(successfulFacts);

    QueryBinding first =
        new QueryBinding(
            "r2-s01-b01",
            InternalDataDomain.ORDER_SALES,
            BindingStatus.SUCCESS,
            firstSpec,
            "");
    QueryBinding second =
        new QueryBinding(
            "r2-s01-b02",
            InternalDataDomain.ORDER_SALES,
            BindingStatus.SUCCESS,
            secondSpec,
            "");

    List<EvidenceSegment> result = adapter.execute(List.of(first, second), deadline());

    assertThat(queryStarted.getCount()).isZero();
    assertThat(result)
        .extracting(EvidenceSegment::status)
        .containsExactly(EvidenceSegment.Status.FAILED, EvidenceSegment.Status.SUCCESS);
    assertThat(result).extracting(EvidenceSegment::id).containsExactly(first.id(), second.id());
    assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
  }

  private ChatRequestDeadline deadline() {
    return new ChatRequestDeadline(CLOCK.instant().plus(Duration.ofMinutes(2)), CLOCK);
  }
}

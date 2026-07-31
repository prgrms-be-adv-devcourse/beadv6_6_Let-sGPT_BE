package com.openat.chat.infrastructure.inference;

import com.openat.chat.application.dto.AdminAnalyticsQueryResult;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.EvidenceSegment;
import com.openat.chat.application.exception.AdminChatExecutionException;
import com.openat.chat.application.exception.AdminChatExecutionException.Reason;
import com.openat.chat.application.port.AdminAnalyticsExecutionPort;
import com.openat.chat.application.port.AdminAnalyticsQueryPort;
import com.openat.chat.application.port.AdminChatInferencePort.BindingStatus;
import com.openat.chat.application.port.AdminChatInferencePort.QueryBinding;
import com.openat.chat.application.service.AdminAnalyticsPlanFactory;
import com.openat.chat.application.service.AdminAnalyticsPlanFactory.PreparedQuery;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Query;
import com.openat.chat.infrastructure.inference.ChatInferenceMetrics.EvidenceStage;
import com.openat.chat.infrastructure.inference.tool.AdminAnalyticsFacts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class AdminAnalyticsExecutionAdapter implements AdminAnalyticsExecutionPort {

  private static final Logger log = LoggerFactory.getLogger(AdminAnalyticsExecutionAdapter.class);

  private final AdminAnalyticsQueryPort queryPort;
  private final AdminAnalyticsPlanFactory planFactory;
  private final AdminAnalyticsResultMapper resultMapper;
  private final ExecutorService taskExecutor;
  private final ChatInferenceProperties properties;
  private final ChatInferenceMetrics metrics;

  public AdminAnalyticsExecutionAdapter(
      AdminAnalyticsQueryPort queryPort,
      AdminAnalyticsPlanFactory planFactory,
      AdminAnalyticsResultMapper resultMapper,
      @Qualifier("chatTaskExecutor") ExecutorService taskExecutor,
      ChatInferenceProperties properties,
      ChatInferenceMetrics metrics) {
    this.queryPort = queryPort;
    this.planFactory = planFactory;
    this.resultMapper = resultMapper;
    this.taskExecutor = taskExecutor;
    this.properties = properties;
    this.metrics = metrics;
  }

  @Override
  public List<EvidenceSegment> execute(List<QueryBinding> bindings, ChatRequestDeadline deadline) {
    List<EvidenceSegment> immediate = new ArrayList<>();
    List<PreparedBinding> prepared = new ArrayList<>();
    Set<Query> uniqueQueries = new LinkedHashSet<>();

    for (QueryBinding binding : bindings) {
      if (binding.status() == BindingStatus.FAILED || binding.query() == null) {
        immediate.add(
            failed(
                binding,
                binding.failureReason() == null || binding.failureReason().isBlank()
                    ? "조회 조건을 채우지 못했어요."
                    : binding.failureReason()));
        continue;
      }
      try {
        PreparedQuery query = planFactory.create(binding.query());
        if (uniqueQueries.add(query.query())) {
          prepared.add(new PreparedBinding(binding, query));
        }
      } catch (IllegalArgumentException | NullPointerException exception) {
        immediate.add(failed(binding, safeMessage(exception)));
      }
    }

    if (!prepared.isEmpty() && !queryPort.isAvailable()) {
      prepared.forEach(
          binding -> immediate.add(failed(binding.binding(), "내부 분석 데이터 조회가 현재 비활성화되어 있어요.")));
      return record(bindings, immediate);
    }

    immediate.addAll(executeAll(prepared, deadline));
    return record(bindings, immediate);
  }

  private EvidenceSegment executeOne(PreparedBinding binding) {
    try {
      AdminAnalyticsQueryResult result = queryPort.query(binding.query().query());
      AdminAnalyticsFacts facts = resultMapper.map(binding.query(), result);
      boolean partial =
          !binding.query().failures().isEmpty()
              || result.suppressedRowCount() > 0
              || result.truncated();
      List<String> limitations = new ArrayList<>();
      binding.query().failures().stream()
          .map(failure -> failure.field() + ": " + failure.reason())
          .forEach(limitations::add);
      if (result.suppressedRowCount() > 0) {
        limitations.add("최소 집단 크기 보호로 일부 행을 숨김");
      }
      if (result.truncated()) {
        limitations.add("결과 행 상한으로 일부 행을 생략");
      }
      return new EvidenceSegment(
          binding.binding().id(),
          partial ? EvidenceSegment.Status.PARTIAL : EvidenceSegment.Status.SUCCESS,
          scope(binding.binding()),
          facts,
          List.copyOf(limitations),
          "OPENAT ai_read",
          facts.asOf(),
          false);
    } catch (RuntimeException exception) {
      log.warn(
          "관리자 분석 조회 실패 dataset={}, errorType={}",
          binding.query().query().dataset(),
          exception.getClass().getSimpleName(),
          exception);
      return failed(binding.binding(), "내부 분석 데이터 조회를 완료하지 못했어요.");
    }
  }

  private List<EvidenceSegment> executeAll(
      List<PreparedBinding> prepared, ChatRequestDeadline deadline) {
    if (prepared.isEmpty()) {
      return List.of();
    }
    StageBudget budget = stageBudget(deadline);
    List<Callable<EvidenceSegment>> tasks =
        prepared.stream()
            .<Callable<EvidenceSegment>>map(binding -> () -> executeOne(binding))
            .toList();
    List<Future<EvidenceSegment>> futures;
    try {
      futures =
          taskExecutor.invokeAll(tasks, budget.timeout().toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AdminChatExecutionException(
          Reason.CANCELLED, "내부 데이터 조회가 취소됐어요.", exception);
    } catch (RejectedExecutionException exception) {
      throw new AdminChatExecutionException(
          Reason.BUSY, "내부 데이터 조회 실행기가 포화됐어요.", exception);
    }

    boolean timedOut = futures.stream().anyMatch(Future::isCancelled);
    if (timedOut && budget.requestDeadlineBound()) {
      throw new AdminChatExecutionException(
          Reason.TIMEOUT, "내부 데이터 조회 중 요청 기한이 지났어요.");
    }

    List<EvidenceSegment> results = new ArrayList<>();
    for (int index = 0; index < futures.size(); index++) {
      PreparedBinding binding = prepared.get(index);
      try {
        results.add(futures.get(index).get());
      } catch (CancellationException exception) {
        results.add(failed(binding.binding(), "내부 데이터 조회 시간이 초과됐어요."));
      } catch (ExecutionException exception) {
        results.add(failed(binding.binding(), "내부 데이터 조회를 완료하지 못했어요."));
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AdminChatExecutionException(
            Reason.CANCELLED, "내부 데이터 조회 결과 수집이 취소됐어요.", exception);
      }
    }
    return List.copyOf(results);
  }

  private StageBudget stageBudget(ChatRequestDeadline deadline) {
    try {
      Duration remaining = deadline.remaining();
      Duration stageTimeout = properties.getStageTimeout();
      return new StageBudget(
          remaining.compareTo(stageTimeout) <= 0 ? remaining : stageTimeout,
          remaining.compareTo(stageTimeout) <= 0);
    } catch (TimeoutException exception) {
      throw new AdminChatExecutionException(
          Reason.TIMEOUT, "내부 데이터 조회 전 요청 기한이 지났어요.", exception);
    }
  }

  private List<EvidenceSegment> record(
      List<QueryBinding> bindings, List<EvidenceSegment> evidence) {
    List<EvidenceSegment> stable = stable(bindings, evidence);
    metrics.recordEvidence(EvidenceStage.ANALYTICS, stable);
    return stable;
  }

  private List<EvidenceSegment> stable(
      List<QueryBinding> bindings, List<EvidenceSegment> evidence) {
    java.util.Map<String, EvidenceSegment> byId = new java.util.LinkedHashMap<>();
    evidence.forEach(segment -> byId.putIfAbsent(segment.id(), segment));
    List<EvidenceSegment> stable = new ArrayList<>();
    for (QueryBinding binding : bindings) {
      EvidenceSegment segment = byId.get(binding.id());
      if (segment != null) {
        stable.add(segment);
      }
    }
    return List.copyOf(stable);
  }

  private EvidenceSegment failed(QueryBinding binding, String reason) {
    return new EvidenceSegment(
        binding.id(),
        EvidenceSegment.Status.FAILED,
        scope(binding),
        null,
        List.of(reason),
        "OPENAT ai_read",
        "",
        false);
  }

  private String scope(QueryBinding binding) {
    String domain = binding.domain() == null ? "UNKNOWN" : binding.domain().name();
    String dataset =
        binding.query() == null || binding.query().dataset() == null
            ? ""
            : "/" + binding.query().dataset().name();
    return domain + dataset;
  }

  private String safeMessage(RuntimeException exception) {
    return exception.getMessage() == null || exception.getMessage().isBlank()
        ? "내부 조회 조건을 검증하지 못했어요."
        : exception.getMessage();
  }

  private record PreparedBinding(QueryBinding binding, PreparedQuery query) {}

  private record StageBudget(Duration timeout, boolean requestDeadlineBound) {}
}

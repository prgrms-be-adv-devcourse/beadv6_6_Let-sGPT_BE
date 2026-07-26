package com.openat.chat.infrastructure.inference.tool;

import com.openat.chat.domain.planning.TimeRangePreset;
import com.openat.chat.domain.planning.TrendGrain;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Comparison;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dataset;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.SortDirection;
import com.openat.chat.domain.query.InternalDataDomain;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class InternalQuerySubmissionTools {

  @Tool(
      name = "submitInternalQueryBindings",
      description =
          "선택된 OPENAT 내부 데이터 영역의 독립 조회 조건을 제출한다. "
              + "채울 수 없는 조회 하나는 FAILED로 남기고 다른 조회는 계속 SUCCESS로 제출한다.")
  public String submitInternalQueryBindings(
      @ToolParam(
              description =
                  "primary shard에서 이미 확인된 가벼운 사실을 모두 설명하는 짧은 자연어. "
                      + "확인된 사실이 없거나 secondary shard이면 빈 문자열")
          String earlyAnswer,
      @ToolParam(description = "질문에 필요한 독립 조회 단위. 조회별 SUCCESS 또는 FAILED")
          List<SubmittedBinding> bindings) {
    throw new UnsupportedOperationException("내부 조회 제출 도구는 정의만 사용해요.");
  }

  public record SubmittedBinding(
      InternalDataDomain domain,
      SubmissionStatus status,
      SubmittedQuery query,
      String failureReason) {}

  public enum SubmissionStatus {
    SUCCESS,
    FAILED
  }

  public record SubmittedQuery(
      Dataset dataset,
      List<String> metrics,
      List<String> dimensions,
      String timeField,
      TimeRangePreset timeRange,
      String customStart,
      String customEndExclusive,
      TrendGrain grain,
      Comparison comparison,
      List<SubmittedFilter> filters,
      String orderBy,
      SortDirection sortDirection,
      int limit) {}

  public record SubmittedFilter(String field, List<String> values) {}
}

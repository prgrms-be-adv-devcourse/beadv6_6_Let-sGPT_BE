package com.openat.chat.application.port;

import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.EvidenceSegment;
import com.openat.chat.domain.planning.TimeRangePreset;
import com.openat.chat.domain.planning.TrendGrain;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Comparison;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dataset;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.SortDirection;
import com.openat.chat.domain.query.InternalDataDomain;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public interface AdminChatInferencePort {

  boolean isAvailable();

  RoutingResponse route(ChatCommand command, ChatRequestDeadline deadline);

  BindingResponse bind(
      ChatCommand command,
      Set<InternalDataDomain> domains,
      List<EvidenceSegment> evidence,
      ChatRequestDeadline deadline);

  void streamAnswer(
      ChatCommand command,
      List<EvidenceSegment> evidence,
      Consumer<String> chunkConsumer,
      ChatRequestDeadline deadline);

  record RoutingResponse(String content, List<ToolInvocation> toolInvocations) {

    public RoutingResponse {
      content = content == null ? "" : content;
      toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
    }

    public boolean hasTools() {
      return !toolInvocations.isEmpty();
    }
  }

  record ToolInvocation(String callId, String name, String arguments) {}

  record BindingResponse(String earlyAnswer, List<QueryBinding> bindings) {

    public BindingResponse {
      earlyAnswer = earlyAnswer == null ? "" : earlyAnswer.strip();
      bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }
  }

  record QueryBinding(
      String id,
      InternalDataDomain domain,
      BindingStatus status,
      QuerySpec query,
      String failureReason) {}

  enum BindingStatus {
    SUCCESS,
    FAILED
  }

  record QuerySpec(
      Dataset dataset,
      List<String> metrics,
      List<String> dimensions,
      String timeField,
      TimeRangePreset timeRange,
      String customStart,
      String customEndExclusive,
      TrendGrain grain,
      Comparison comparison,
      List<FilterSpec> filters,
      String orderBy,
      SortDirection sortDirection,
      int limit) {

    public QuerySpec {
      metrics = metrics == null ? List.of() : List.copyOf(metrics);
      dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
      filters = filters == null ? List.of() : List.copyOf(filters);
      customStart = customStart == null ? "" : customStart;
      customEndExclusive = customEndExclusive == null ? "" : customEndExclusive;
    }
  }

  record FilterSpec(String field, List<String> values) {

    public FilterSpec {
      values = values == null ? List.of() : List.copyOf(values);
    }
  }
}

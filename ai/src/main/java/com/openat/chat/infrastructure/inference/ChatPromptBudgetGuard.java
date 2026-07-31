package com.openat.chat.infrastructure.inference;

import com.openat.chat.application.exception.AdminChatExecutionException;
import com.openat.chat.application.exception.AdminChatExecutionException.Reason;
import com.openat.chat.infrastructure.inference.ChatInferenceMetrics.Stage;
import java.util.List;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

@Component
public class ChatPromptBudgetGuard {

  private final TokenCountEstimator tokenEstimator;
  private final ChatInferenceProperties properties;
  private final ChatInferenceMetrics metrics;

  public ChatPromptBudgetGuard(
      TokenCountEstimator tokenEstimator,
      ChatInferenceProperties properties,
      ChatInferenceMetrics metrics) {
    this.tokenEstimator = tokenEstimator;
    this.properties = properties;
    this.metrics = metrics;
  }

  public int verify(
      Stage stage, String systemPrompt, String userPrompt, List<ToolCallback> tools) {
    int estimatedTokens = estimate(systemPrompt, userPrompt, tools);
    metrics.recordInputTokens(stage, estimatedTokens);
    if (estimatedTokens > properties.getContext().getInputTokenLimit()) {
      throw new AdminChatExecutionException(
          Reason.INPUT_BUDGET_EXCEEDED,
          "관리자 챗봇 " + stage.name().toLowerCase() + " 입력이 토큰 예산을 넘었어요.");
    }
    return estimatedTokens;
  }

  int estimate(String systemPrompt, String userPrompt, List<ToolCallback> tools) {
    StringBuilder input =
        new StringBuilder()
            .append("<system>")
            .append(systemPrompt)
            .append("</system><user>")
            .append(userPrompt)
            .append("</user>");
    if (!tools.isEmpty()) {
      input.append("<tools>");
      for (ToolCallback tool : tools) {
        input
            .append("<tool><name>")
            .append(tool.getToolDefinition().name())
            .append("</name><description>")
            .append(tool.getToolDefinition().description())
            .append("</description><schema>")
            .append(tool.getToolDefinition().inputSchema())
            .append("</schema></tool>");
      }
      input.append("</tools>");
    }
    return tokenEstimator.estimate(input.toString());
  }
}

package com.openat.chat.infrastructure.inference;

import com.openat.chat.domain.query.InternalDataDomain;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chat.inference")
public class ChatInferenceProperties {

  private boolean enabled = true;
  private String baseUrl = "http://127.0.0.1:11434/v1";
  private String model = "gemma4:12b-it-qat";
  private boolean localOnlyRoute;
  private String reasoningEffort = "none";
  private Duration stageTimeout = Duration.ofSeconds(55);
  private int routingMaxTokens = 700;
  private int bindingMaxTokens = 1400;
  private int answerMaxTokens = 1500;
  private final Context context = new Context();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public boolean isLocalOnlyRoute() {
    return localOnlyRoute || isLoopback(baseUrl);
  }

  public void setLocalOnlyRoute(boolean localOnlyRoute) {
    this.localOnlyRoute = localOnlyRoute;
  }

  public String getReasoningEffort() {
    return reasoningEffort;
  }

  public void setReasoningEffort(String reasoningEffort) {
    this.reasoningEffort = reasoningEffort;
  }

  public Duration getStageTimeout() {
    return stageTimeout;
  }

  public void setStageTimeout(Duration stageTimeout) {
    this.stageTimeout = stageTimeout;
  }

  public int getRoutingMaxTokens() {
    return routingMaxTokens;
  }

  public void setRoutingMaxTokens(int routingMaxTokens) {
    this.routingMaxTokens = routingMaxTokens;
  }

  public int getBindingMaxTokens() {
    return bindingMaxTokens;
  }

  public void setBindingMaxTokens(int bindingMaxTokens) {
    this.bindingMaxTokens = bindingMaxTokens;
  }

  public int getAnswerMaxTokens() {
    return answerMaxTokens;
  }

  public void setAnswerMaxTokens(int answerMaxTokens) {
    this.answerMaxTokens = answerMaxTokens;
  }

  public Context getContext() {
    return context;
  }

  @PostConstruct
  void validate() {
    if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) {
      throw new IllegalStateException("관리자 챗봇 추론 주소와 모델이 필요해요.");
    }
    if (reasoningEffort == null || reasoningEffort.isBlank()) {
      throw new IllegalStateException("chat.inference.reasoning-effort가 필요해요.");
    }
    if (stageTimeout == null || stageTimeout.isNegative() || stageTimeout.isZero()) {
      throw new IllegalStateException("chat.inference.stage-timeout은 1ms 이상이어야 해요.");
    }
    if (routingMaxTokens < 1 || bindingMaxTokens < 1 || answerMaxTokens < 1) {
      throw new IllegalStateException("단계별 출력 토큰은 1 이상이어야 해요.");
    }
    context.validate();
  }

  private boolean isLoopback(String value) {
    try {
      String host = URI.create(value).getHost();
      return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  public static class Context {

    private int inputTokenLimit = 6000;
    private int answerTokenReserve = 1500;
    private int safetyTokenReserve = 692;
    private int maxSchemaShards = 6;
    private int previousQuestionMaxCharacters = 300;
    private int previousAnswerMaxCharacters = 800;

    public int getInputTokenLimit() {
      return inputTokenLimit;
    }

    public void setInputTokenLimit(int inputTokenLimit) {
      this.inputTokenLimit = inputTokenLimit;
    }

    public int getAnswerTokenReserve() {
      return answerTokenReserve;
    }

    public void setAnswerTokenReserve(int answerTokenReserve) {
      this.answerTokenReserve = answerTokenReserve;
    }

    public int getSafetyTokenReserve() {
      return safetyTokenReserve;
    }

    public void setSafetyTokenReserve(int safetyTokenReserve) {
      this.safetyTokenReserve = safetyTokenReserve;
    }

    public int getMaxSchemaShards() {
      return maxSchemaShards;
    }

    public void setMaxSchemaShards(int maxSchemaShards) {
      this.maxSchemaShards = maxSchemaShards;
    }

    public int getPreviousQuestionMaxCharacters() {
      return previousQuestionMaxCharacters;
    }

    public void setPreviousQuestionMaxCharacters(int previousQuestionMaxCharacters) {
      this.previousQuestionMaxCharacters = previousQuestionMaxCharacters;
    }

    public int getPreviousAnswerMaxCharacters() {
      return previousAnswerMaxCharacters;
    }

    public void setPreviousAnswerMaxCharacters(int previousAnswerMaxCharacters) {
      this.previousAnswerMaxCharacters = previousAnswerMaxCharacters;
    }

    private void validate() {
      if (inputTokenLimit < 1 || answerTokenReserve < 1 || safetyTokenReserve < 0) {
        throw new IllegalStateException("chat.inference.context 토큰 예산이 올바르지 않아요.");
      }
      if (maxSchemaShards < 1 || maxSchemaShards > InternalDataDomain.values().length) {
        throw new IllegalStateException(
            "chat.inference.context.max-schema-shards는 1 이상 도메인 수 이하여야 해요.");
      }
      if (previousQuestionMaxCharacters < 1 || previousAnswerMaxCharacters < 1) {
        throw new IllegalStateException("직전 대화 문자 상한은 1 이상이어야 해요.");
      }
    }
  }
}

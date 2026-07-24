package com.openat.chat.infrastructure.inference.tool;

import com.openat.chat.application.dto.ChatStreamEvent;
import com.openat.chat.application.dto.ChatStreamEvent.ChatStage;
import com.openat.chat.application.port.ChatEventSink;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AdminToolExecutionContext {

  public static final String KEY = AdminToolExecutionContext.class.getName();
  private static final Pattern PUBLIC_ORDER_NUMBER =
      Pattern.compile("(?i)(?<![A-Za-z0-9-])ORD-[A-Za-z0-9-]{1,26}(?![A-Za-z0-9-])");

  private final UUID requestId;
  private final String originalQuestion;
  private final ChatEventSink sink;
  private final AtomicBoolean toolStatusEmitted = new AtomicBoolean(false);
  private final AtomicInteger invocationCount = new AtomicInteger();

  public AdminToolExecutionContext(UUID requestId, String originalQuestion, ChatEventSink sink) {
    this(requestId, originalQuestion, sink, false);
  }

  public AdminToolExecutionContext(
      UUID requestId,
      String originalQuestion,
      ChatEventSink sink,
      boolean toolStatusAlreadyEmitted) {
    this.requestId = requestId;
    this.originalQuestion = originalQuestion;
    this.sink = sink;
    this.toolStatusEmitted.set(toolStatusAlreadyEmitted);
  }

  public void started() {
    invocationCount.incrementAndGet();
    if (toolStatusEmitted.compareAndSet(false, true) && !sink.isClosed()) {
      sink.emit(ChatStreamEvent.status(requestId, ChatStage.CALLING_TOOL));
    }
  }

  public AdminToolResult completed(AdminToolResult result) {
    return result;
  }

  public int invocationCount() {
    return invocationCount.get();
  }

  public String originalQuestion() {
    return originalQuestion;
  }

  public String verifiedOrderNumber(String modelValue) {
    if (modelValue == null) {
      throw new IllegalArgumentException("공개 주문번호가 필요해요.");
    }
    Matcher matcher = PUBLIC_ORDER_NUMBER.matcher(originalQuestion);
    if (!matcher.find()) {
      throw new IllegalArgumentException("질문 원문에 공개 주문번호가 없어요.");
    }
    String verified = matcher.group();
    if (matcher.find() || !verified.equalsIgnoreCase(modelValue)) {
      throw new IllegalArgumentException("질문 원문과 조회 주문번호가 일치하지 않아요.");
    }
    return verified.toUpperCase(Locale.ROOT);
  }
}

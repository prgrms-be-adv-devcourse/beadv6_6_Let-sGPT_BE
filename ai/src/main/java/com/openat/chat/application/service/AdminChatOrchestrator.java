package com.openat.chat.application.service;

import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.ChatStreamEvent;
import com.openat.chat.application.dto.ChatStreamEvent.ChatStage;
import com.openat.chat.application.dto.EvidenceSegment;
import com.openat.chat.application.port.AdminAnalyticsExecutionPort;
import com.openat.chat.application.port.AdminChatInferencePort;
import com.openat.chat.application.port.AdminChatInferencePort.BindingResponse;
import com.openat.chat.application.port.AdminChatInferencePort.RoutingResponse;
import com.openat.chat.application.port.AdminInitialToolPort;
import com.openat.chat.application.port.AdminInitialToolPort.InitialToolResult;
import com.openat.chat.application.port.ChatEventSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class AdminChatOrchestrator {

  private static final int MAX_ANSWER_CHARACTERS = 12_000;
  private static final int REPLAY_CHUNK_CHARACTERS = 48;

  private final AdminChatInferencePort inference;
  private final AdminInitialToolPort initialTools;
  private final AdminAnalyticsExecutionPort analytics;

  public AdminChatOrchestrator(
      AdminChatInferencePort inference,
      AdminInitialToolPort initialTools,
      AdminAnalyticsExecutionPort analytics) {
    this.inference = inference;
    this.initialTools = initialTools;
    this.analytics = analytics;
  }

  public boolean isAvailable() {
    return inference.isAvailable();
  }

  public void execute(ChatCommand command, ChatEventSink sink, ChatRequestDeadline deadline) {
    sink.emit(ChatStreamEvent.status(command.requestId(), ChatStage.ANALYZING));

    RoutingResponse routing = inference.route(command, deadline);
    if (!routing.hasTools()) {
      if (routing.content().isBlank()) {
        throw new IllegalStateException("1차 추론 응답에 답변과 도구 호출이 모두 없어요.");
      }
      emitBuffered(command, sink, routing.content());
      sink.terminate(ChatStreamEvent.done(command.requestId()));
      return;
    }

    sink.emit(ChatStreamEvent.status(command.requestId(), ChatStage.CALLING_TOOL));
    InitialToolResult initial =
        initialTools.execute(command, routing.toolInvocations(), sink, deadline);
    if (initial.schemaSelectionRequested() && initial.domains().isEmpty()) {
      throw new IllegalStateException("내부 데이터 영역을 결정하지 못했어요.");
    }

    List<EvidenceSegment> evidence = new ArrayList<>(initial.evidence());
    AtomicBoolean naturalAnswerStarted = new AtomicBoolean(false);
    int[] answerCharacters = {0};

    if (initial.domains().isEmpty()) {
      streamAnswer(
          command, sink, deadline, evidence, naturalAnswerStarted, answerCharacters, false);
      sink.terminate(ChatStreamEvent.done(command.requestId()));
      return;
    }

    BindingResponse binding =
        inference.bind(command, initial.domains(), List.copyOf(evidence), deadline);
    boolean earlyAnswerDelivered =
        emitEarlyAnswer(
            command, sink, binding.earlyAnswer(), naturalAnswerStarted, answerCharacters);
    if (earlyAnswerDelivered) {
      evidence.replaceAll(EvidenceSegment::deliveredCopy);
    }

    evidence.addAll(analytics.execute(binding.bindings(), deadline));
    streamAnswer(
        command,
        sink,
        deadline,
        evidence,
        naturalAnswerStarted,
        answerCharacters,
        earlyAnswerDelivered);
    sink.terminate(ChatStreamEvent.done(command.requestId()));
  }

  private void emitBuffered(ChatCommand command, ChatEventSink sink, String answer) {
    sink.emit(ChatStreamEvent.status(command.requestId(), ChatStage.GENERATING));
    String normalized = validateAnswer(answer);
    if (normalized.length() > MAX_ANSWER_CHARACTERS) {
      throw new IllegalStateException("추론 답변 길이가 허용 범위를 넘었어요.");
    }
    for (int start = 0; start < normalized.length(); start += REPLAY_CHUNK_CHARACTERS) {
      int end = Math.min(normalized.length(), start + REPLAY_CHUNK_CHARACTERS);
      sink.emit(ChatStreamEvent.delta(command.requestId(), normalized.substring(start, end)));
    }
  }

  private boolean emitEarlyAnswer(
      ChatCommand command,
      ChatEventSink sink,
      String earlyAnswer,
      AtomicBoolean naturalAnswerStarted,
      int[] answerCharacters) {
    if (earlyAnswer == null || earlyAnswer.isBlank()) {
      return false;
    }
    String answer = validateAnswer(earlyAnswer);
    startNaturalAnswer(command, sink, naturalAnswerStarted);
    addAnswerCharacters(answerCharacters, answer);
    sink.emit(ChatStreamEvent.delta(command.requestId(), answer));
    return true;
  }

  private void streamAnswer(
      ChatCommand command,
      ChatEventSink sink,
      ChatRequestDeadline deadline,
      List<EvidenceSegment> evidence,
      AtomicBoolean naturalAnswerStarted,
      int[] answerCharacters,
      boolean separateFromEarlyAnswer) {
    AtomicBoolean firstChunk = new AtomicBoolean(true);
    AtomicBoolean chunkEmitted = new AtomicBoolean(false);
    inference.streamAnswer(
        command,
        List.copyOf(evidence),
        chunk -> {
          if (chunk == null || chunk.isEmpty()) {
            return;
          }
          String safeChunk = validateChunk(chunk);
          chunkEmitted.set(true);
          startNaturalAnswer(command, sink, naturalAnswerStarted);
          if (separateFromEarlyAnswer && firstChunk.compareAndSet(true, false)) {
            addAnswerCharacters(answerCharacters, "\n\n");
            sink.emit(ChatStreamEvent.delta(command.requestId(), "\n\n"));
          }
          addAnswerCharacters(answerCharacters, safeChunk);
          sink.emit(ChatStreamEvent.delta(command.requestId(), safeChunk));
        },
        deadline);
    if (!chunkEmitted.get()) {
      throw new IllegalStateException("추론 서버가 최종 답변을 보내지 않았어요.");
    }
  }

  private void startNaturalAnswer(
      ChatCommand command, ChatEventSink sink, AtomicBoolean naturalAnswerStarted) {
    if (naturalAnswerStarted.compareAndSet(false, true)) {
      sink.emit(ChatStreamEvent.status(command.requestId(), ChatStage.GENERATING));
    }
  }

  private void addAnswerCharacters(int[] answerCharacters, String value) {
    answerCharacters[0] += value.length();
    if (answerCharacters[0] > MAX_ANSWER_CHARACTERS) {
      throw new IllegalStateException("추론 답변 길이가 허용 범위를 넘었어요.");
    }
  }

  private String validateAnswer(String value) {
    String answer = value.strip();
    if (answer.isEmpty()) {
      throw new IllegalStateException("추론 답변이 비어 있어요.");
    }
    return answer;
  }

  private String validateChunk(String value) {
    if (value.isEmpty()) {
      throw new IllegalStateException("추론 답변 조각이 비어 있어요.");
    }
    return value;
  }
}

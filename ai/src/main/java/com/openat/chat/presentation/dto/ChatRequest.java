package com.openat.chat.presentation.dto;

import com.openat.chat.application.service.AdminChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
    @NotBlank(message = "질문을 입력해 주세요.")
        @Size(max = AdminChatService.MAX_MESSAGE_LENGTH, message = "질문은 2,000자 이하로 입력해 주세요.")
        String message,
    @Valid PreviousTurnRequest previousTurn) {

  public ChatRequest {
    if (message != null) {
      message = message.strip();
    }
  }

  public ChatRequest(String message) {
    this(message, null);
  }

  public record PreviousTurnRequest(
      @NotBlank(message = "이전 질문을 입력해 주세요.")
          @Size(
              max = AdminChatService.MAX_PREVIOUS_QUESTION_LENGTH,
              message = "이전 질문은 300자 이하로 입력해 주세요.")
          String question,
      @NotBlank(message = "이전 답변을 입력해 주세요.")
          @Size(
              max = AdminChatService.MAX_PREVIOUS_ANSWER_LENGTH,
              message = "이전 답변은 800자 이하로 입력해 주세요.")
          String answer) {

    public PreviousTurnRequest {
      if (question != null) {
        question = question.strip();
      }
      if (answer != null) {
        answer = answer.strip();
      }
    }
  }
}

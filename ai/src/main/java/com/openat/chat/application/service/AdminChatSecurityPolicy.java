package com.openat.chat.application.service;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AdminChatSecurityPolicy {

  private static final Pattern PROMPT_BYPASS =
      Pattern.compile(
          "(이전|위|시스템)[ ]*(지시|명령|프롬프트).{0,20}(무시|공개|출력)|"
              + "(sql|스키마).{0,20}(실행해|돌려|조회해|조회[ ]*해[ ]*줘|"
              + "출력해|보여[ ]*줘|공개해)|"
              + "(비밀번호|api[ _-]?key|토큰).{0,20}(보여|출력|알려)");
  private static final Pattern SQL_EXECUTION =
      Pattern.compile(
          "(?i)\\b(select|insert|update|delete|drop|alter|grant|revoke)\\b.{0,120}(실행|돌려|조회)|"
              + "(실행|돌려).{0,120}(?i:\\b(select|insert|update|delete|drop|alter|grant|revoke)\\b)");
  private static final Pattern CONTEXT_INJECTION =
      Pattern.compile(
          "(?i)(ignore|forget|override).{0,40}(previous|above|system|instruction|prompt)|"
              + "(reveal|print|show).{0,40}(system prompt|hidden instruction|api[ _-]?key)|"
              + "(이전|위|시스템).{0,20}(지시|명령|프롬프트).{0,20}(무시|대체|공개|출력)");
  private static final Pattern SENSITIVE_VALUE =
      Pattern.compile(
          "(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}|"
              + "(?<!\\d)01[016789][- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)|"
              + "(api[ _-]?key|password|비밀번호|계좌번호)\\s*[:=]\\s*\\S+");

  public PolicyDecision evaluate(String question) {
    if (question == null || question.isBlank()) {
      return PolicyDecision.reject("EMPTY_QUESTION");
    }
    String normalized = question.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    if (normalized.length() > AdminChatService.MAX_MESSAGE_LENGTH) {
      return PolicyDecision.reject("MESSAGE_TOO_LONG");
    }
    if (PROMPT_BYPASS.matcher(normalized).find() || SQL_EXECUTION.matcher(normalized).find()) {
      return PolicyDecision.reject("PROMPT_POLICY_VIOLATION");
    }
    return PolicyDecision.allow();
  }

  public boolean isSafePreviousTurn(String question, String answer) {
    if (question == null
        || answer == null
        || question.isBlank()
        || answer.isBlank()
        || question.length() > AdminChatService.MAX_PREVIOUS_QUESTION_LENGTH
        || answer.length() > AdminChatService.MAX_PREVIOUS_ANSWER_LENGTH) {
      return false;
    }
    String context = (question + "\n" + answer).strip();
    return !CONTEXT_INJECTION.matcher(context).find()
        && !PROMPT_BYPASS.matcher(context.toLowerCase(Locale.ROOT)).find()
        && !SQL_EXECUTION.matcher(context).find()
        && !SENSITIVE_VALUE.matcher(context).find();
  }

  public record PolicyDecision(boolean allowed, String reasonCode) {

    public static PolicyDecision allow() {
      return new PolicyDecision(true, "NONE");
    }

    public static PolicyDecision reject(String reasonCode) {
      return new PolicyDecision(false, reasonCode);
    }
  }
}

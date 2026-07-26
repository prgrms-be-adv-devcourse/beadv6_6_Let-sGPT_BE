package com.openat.chat.application.dto;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record ChatCommand(
    UUID requestId,
    String administratorId,
    Set<String> roles,
    String message,
    PreviousTurn previousTurn) {

  public ChatCommand {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(administratorId, "administratorId");
    Objects.requireNonNull(roles, "roles");
    Objects.requireNonNull(message, "message");
    roles =
        roles.stream()
            .filter(role -> role != null && !role.isBlank())
            .map(role -> role.replaceFirst("^ROLE_", "").toUpperCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    previousTurn = previousTurn == null ? null : previousTurn.normalized();
  }

  public ChatCommand(UUID requestId, String administratorId, Set<String> roles, String message) {
    this(requestId, administratorId, roles, message, null);
  }

  public Optional<PreviousTurn> previousTurnContext() {
    return Optional.ofNullable(previousTurn);
  }

  public ChatCommand withoutPreviousTurn() {
    return previousTurn == null
        ? this
        : new ChatCommand(requestId, administratorId, roles, message, null);
  }

  public record PreviousTurn(String question, String answer) {

    public PreviousTurn {
      Objects.requireNonNull(question, "question");
      Objects.requireNonNull(answer, "answer");
    }

    private PreviousTurn normalized() {
      return new PreviousTurn(question.strip(), answer.strip());
    }
  }
}

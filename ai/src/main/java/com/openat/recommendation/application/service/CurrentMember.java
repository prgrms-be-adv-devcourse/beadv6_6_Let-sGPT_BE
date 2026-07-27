package com.openat.recommendation.application.service;

import com.openat.common.auth.UserContext;
import com.openat.common.auth.UserContextHolder;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CurrentMember {

  private static final Logger log = LoggerFactory.getLogger(CurrentMember.class);

  private CurrentMember() {}

  static Optional<UUID> id() {
    UserContext context = UserContextHolder.get();
    if (context == null) {
      return Optional.empty();
    }
    // X-User-Id가 UUID가 아니면 익명으로 강등한다(요청을 깨뜨리는 대신 폴백 경로로).
    try {
      return Optional.of(UUID.fromString(context.userId()));
    } catch (IllegalArgumentException exception) {
      log.warn("malformed X-User-Id, treating as anonymous");
      return Optional.empty();
    }
  }
}

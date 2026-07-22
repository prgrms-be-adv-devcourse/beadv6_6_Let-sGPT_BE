package com.openat.chat.application.dto;

import java.util.List;
import java.util.Objects;

public record EvidenceSegment(
    String id,
    Status status,
    String scope,
    Object facts,
    List<String> limitations,
    String source,
    String observedAt,
    boolean delivered) {

  public EvidenceSegment {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(scope, "scope");
    limitations = limitations == null ? List.of() : List.copyOf(limitations);
    source = source == null ? "" : source;
    observedAt = observedAt == null ? "" : observedAt;
  }

  public EvidenceSegment deliveredCopy() {
    return delivered
        ? this
        : new EvidenceSegment(id, status, scope, facts, limitations, source, observedAt, true);
  }

  public enum Status {
    SUCCESS,
    PARTIAL,
    FAILED
  }
}

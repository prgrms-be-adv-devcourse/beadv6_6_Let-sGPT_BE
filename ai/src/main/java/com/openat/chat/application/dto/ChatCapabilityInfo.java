package com.openat.chat.application.dto;

import java.util.List;

public record ChatCapabilityInfo(
    String type,
    String label,
    String description,
    Availability availability,
    List<String> sampleQuestions) {

  public enum Availability {
    ACTIVE,
    PLANNED,
    UNAVAILABLE
  }
}

package com.openat.recommendation.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RecommendationPostProcessor {

  private static final int MAX_TITLE_LENGTH = 30;
  private static final Pattern CODE_FENCE_PATTERN =
      Pattern.compile("^```(?:json)?\\s*(.*?)\\s*```$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private final ObjectMapper objectMapper;

  public RecommendationPostProcessor(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public List<SelectedSection> process(String rawResponse, List<UUID> orderedCandidateIds) {
    RawResponse parsed;
    try {
      parsed = objectMapper.readValue(stripCodeFence(rawResponse), RawResponse.class);
    } catch (Exception exception) {
      throw new IllegalStateException("Failed to parse LLM response as JSON", exception);
    }
    if (parsed.sections() == null) {
      throw new IllegalStateException("LLM response is missing the sections field");
    }

    Set<Integer> seen = new HashSet<>();
    List<SelectedSection> result = new ArrayList<>();
    for (RawSection section : parsed.sections()) {
      if (section == null || section.title() == null || section.items() == null) {
        continue;
      }
      String title = section.title().trim();
      if (title.isEmpty()) {
        continue;
      }
      if (title.codePointCount(0, title.length()) > MAX_TITLE_LENGTH) {
        title = title.substring(0, title.offsetByCodePoints(0, MAX_TITLE_LENGTH));
      }
      LinkedHashSet<UUID> retained = new LinkedHashSet<>();
      for (JsonNode rawItem : section.items()) {
        Integer index = parseIndex(rawItem);
        if (index != null && index >= 1 && index <= orderedCandidateIds.size() && seen.add(index)) {
          retained.add(orderedCandidateIds.get(index - 1));
        }
      }
      if (!retained.isEmpty()) {
        result.add(new SelectedSection(title, List.copyOf(retained)));
      }
    }
    return List.copyOf(result);
  }

  private Integer parseIndex(JsonNode rawItem) {
    if (rawItem == null || rawItem.isNull()) {
      return null;
    }
    try {
      if (rawItem.isIntegralNumber() && rawItem.canConvertToInt()) {
        return rawItem.intValue();
      }
      if (rawItem.isTextual()) {
        return Integer.valueOf(rawItem.textValue().trim());
      }
    } catch (NumberFormatException invalid) {
      return null;
    }
    return null;
  }

  private String stripCodeFence(String rawResponse) {
    String trimmed = rawResponse == null ? "" : rawResponse.trim();
    Matcher matcher = CODE_FENCE_PATTERN.matcher(trimmed);
    return matcher.matches() ? matcher.group(1).trim() : trimmed;
  }

  private record RawResponse(List<RawSection> sections) {}

  private record RawSection(String title, List<JsonNode> items) {}

  public record SelectedSection(String title, List<UUID> productIds) {}
}

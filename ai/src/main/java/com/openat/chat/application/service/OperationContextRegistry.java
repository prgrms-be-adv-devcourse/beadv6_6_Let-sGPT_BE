package com.openat.chat.application.service;

import com.openat.chat.domain.knowledge.OperationContextId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class OperationContextRegistry {

  private static final String RESOURCE_ROOT = "chat/context/operations/";
  private static final int MAX_DOCUMENT_CHARACTERS = 2_000;
  private static final int MAX_SELECTED_CHARACTERS = 3_000;

  private final String catalog;
  private final Map<OperationContextId, String> documents;

  public OperationContextRegistry() {
    catalog = loadResource("catalog.md");
    documents = loadDocuments();
  }

  public String catalog() {
    return catalog;
  }

  public Selection select(List<OperationContextId> contextIds) {
    if (contextIds == null || contextIds.isEmpty()) {
      throw new IllegalArgumentException("운영 컨텍스트 영역이 비어 있어요.");
    }
    LinkedHashSet<OperationContextId> selected = new LinkedHashSet<>(contextIds);
    if (selected.contains(null)) {
      throw new IllegalArgumentException("운영 컨텍스트 영역에 빈 값이 있어요.");
    }
    StringBuilder content = new StringBuilder();
    List<OperationContextId> included = new ArrayList<>();
    List<OperationContextId> omitted = new ArrayList<>();
    for (OperationContextId contextId : selected) {
      String document = documents.get(contextId);
      int separatorLength = content.isEmpty() ? 0 : 2;
      if (content.length() + separatorLength + document.length() > MAX_SELECTED_CHARACTERS) {
        omitted.add(contextId);
        continue;
      }
      if (!content.isEmpty()) {
        content.append("\n\n");
      }
      content.append(document);
      included.add(contextId);
    }
    return new Selection(List.copyOf(included), List.copyOf(omitted), content.toString());
  }

  private Map<OperationContextId, String> loadDocuments() {
    EnumMap<OperationContextId, String> loaded = new EnumMap<>(OperationContextId.class);
    for (OperationContextId contextId : OperationContextId.values()) {
      loaded.put(contextId, loadResource(contextId.resourceName()));
    }
    return Map.copyOf(loaded);
  }

  private String loadResource(String name) {
    try {
      String document =
          new ClassPathResource(RESOURCE_ROOT + name)
              .getContentAsString(StandardCharsets.UTF_8)
              .strip();
      if (document.isBlank() || document.length() > MAX_DOCUMENT_CHARACTERS) {
        throw new IllegalStateException("운영 컨텍스트 문서 크기가 허용 범위를 벗어났어요: " + name);
      }
      return document;
    } catch (IOException exception) {
      throw new IllegalStateException("운영 컨텍스트 문서를 읽지 못했어요: " + name, exception);
    }
  }

  public record Selection(
      List<OperationContextId> included, List<OperationContextId> omitted, String content) {}
}

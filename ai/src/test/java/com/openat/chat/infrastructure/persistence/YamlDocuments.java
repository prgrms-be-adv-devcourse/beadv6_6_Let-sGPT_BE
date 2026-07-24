package com.openat.chat.infrastructure.persistence;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

final class YamlDocuments {

  private final List<Map<String, Object>> documents;

  private YamlDocuments(List<Map<String, Object>> documents) {
    this.documents = List.copyOf(documents);
  }

  static YamlDocuments read(String first, String... more) throws IOException {
    List<Map<String, Object>> documents = new ArrayList<>();
    try (Reader reader = Files.newBufferedReader(Path.of(first, more), StandardCharsets.UTF_8)) {
      for (Object document : new Yaml().loadAll(reader)) {
        documents.add(asMap(document));
      }
    }
    return new YamlDocuments(documents);
  }

  static YamlDocuments parse(String source) {
    List<Map<String, Object>> documents = new ArrayList<>();
    for (Object document : new Yaml().loadAll(source)) {
      documents.add(asMap(document));
    }
    return new YamlDocuments(documents);
  }

  Map<String, Object> onlyDocument() {
    if (documents.size() != 1) {
      throw new IllegalStateException("YAML 문서가 하나가 아니에요: " + documents.size());
    }
    return documents.getFirst();
  }

  Map<String, Object> document(String kind, String name) {
    return documents.stream()
        .filter(candidate -> kind.equals(candidate.get("kind")))
        .filter(candidate -> name.equals(value(candidate, "metadata", "name")))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(kind + "/" + name + " 문서가 없어요."));
  }

  static Object value(Map<String, Object> source, String... path) {
    Object current = source;
    for (String key : path) {
      current = asMap(current).get(key);
    }
    return current;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> asMap(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalStateException("YAML 값이 map이 아니에요: " + value);
    }
    return (Map<String, Object>) map;
  }

  static List<Map<String, Object>> asMaps(Object value) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalStateException("YAML 값이 list가 아니에요: " + value);
    }
    return list.stream().map(YamlDocuments::asMap).toList();
  }

  static Map<String, Object> named(List<Map<String, Object>> values, String name) {
    return values.stream()
        .filter(candidate -> name.equals(candidate.get("name")))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(name + " 항목이 없어요."));
  }
}

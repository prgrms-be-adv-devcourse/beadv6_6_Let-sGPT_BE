package com.openat.chat.application.dto;

import java.time.Instant;
import java.util.List;

public record WebSearchResult(String query, List<Item> items, Instant observedAt) {

  public WebSearchResult {
    items = List.copyOf(items);
  }

  public record Item(String title, String url, String content, String publishedDate) {}
}

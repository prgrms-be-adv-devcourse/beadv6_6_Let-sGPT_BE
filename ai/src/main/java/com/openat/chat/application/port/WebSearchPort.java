package com.openat.chat.application.port;

import com.openat.chat.application.dto.WebSearchResult;

public interface WebSearchPort {

  boolean isAvailable();

  WebSearchResult search(WebSearchQuery query);

  record WebSearchQuery(String text, Topic topic, Freshness freshness) {}

  enum Topic {
    GENERAL,
    NEWS,
    FINANCE
  }

  enum Freshness {
    NONE,
    DAY,
    WEEK,
    MONTH
  }
}

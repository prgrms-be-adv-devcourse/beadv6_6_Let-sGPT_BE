package com.openat.chat.infrastructure.websearch;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.openat.chat.application.dto.WebSearchResult;
import com.openat.chat.application.port.WebSearchPort;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class TavilyWebSearchAdapter implements WebSearchPort {

  private static final Logger log = LoggerFactory.getLogger(TavilyWebSearchAdapter.class);
  private static final int MAX_RESULTS = 3;
  private static final int MAX_CONTENT_CHARACTERS = 1_200;

  private final RestClient restClient;
  private final TavilyProperties properties;
  private final Clock clock;

  public TavilyWebSearchAdapter(
      @Qualifier("tavilyRestClient") RestClient restClient,
      TavilyProperties properties,
      Clock clock) {
    this.restClient = restClient;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public boolean isAvailable() {
    return properties.isConfigured();
  }

  @Override
  public WebSearchResult search(WebSearchQuery query) {
    if (!isAvailable()) {
      throw new IllegalStateException("웹 검색 도구가 설정되지 않았어요.");
    }
    TavilyRequest request =
        new TavilyRequest(
            query.text(),
            topic(query.topic()),
            searchDepth(query.topic()),
            freshness(query.freshness()),
            MAX_RESULTS,
            false,
            false,
            false,
            false);
    long startedAt = System.nanoTime();
    try {
      TavilyResponse response =
          restClient
              .post()
              .uri("/search")
              .body(request)
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  (ignoredRequest, errorResponse) -> {
                    throw new IllegalStateException(
                        "Tavily 검색 요청이 실패했어요. status=" + errorResponse.getStatusCode().value());
                  })
              .body(TavilyResponse.class);
      if (response == null) {
        throw new IllegalStateException("Tavily 검색 응답이 비어 있어요.");
      }
      List<WebSearchResult.Item> items = sanitize(response.results());
      log.info(
          "웹 검색 완료 provider=tavily, requestId={}, resultCount={}, elapsedMs={}",
          safeRequestId(response.requestId()),
          items.size(),
          elapsedMillis(startedAt));
      return new WebSearchResult(query.text(), items, clock.instant());
    } catch (RestClientException | IllegalStateException exception) {
      log.warn(
          "웹 검색 실패 provider=tavily, errorType={}, elapsedMs={}",
          exception.getClass().getSimpleName(),
          elapsedMillis(startedAt));
      throw new IllegalStateException("웹 검색 공급자 응답을 받지 못했어요.", exception);
    }
  }

  private List<WebSearchResult.Item> sanitize(List<TavilyResult> nullableResults) {
    if (nullableResults == null || nullableResults.isEmpty()) {
      return List.of();
    }
    List<WebSearchResult.Item> items = new ArrayList<>();
    for (TavilyResult result : nullableResults) {
      if (result == null || !safeUrl(result.url())) {
        continue;
      }
      String title = safeText(result.title(), 300);
      String content = safeText(result.content(), MAX_CONTENT_CHARACTERS);
      if (title.isBlank() || content.isBlank()) {
        continue;
      }
      items.add(
          new WebSearchResult.Item(
              title, result.url(), content, safeText(result.publishedDate(), 80)));
      if (items.size() == MAX_RESULTS) {
        break;
      }
    }
    return List.copyOf(items);
  }

  private boolean safeUrl(String value) {
    if (value == null || value.length() > 2_000) {
      return false;
    }
    try {
      URI uri = URI.create(value);
      return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          && uri.getHost() != null
          && !uri.getHost().isBlank()
          && uri.getUserInfo() == null;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private String safeText(String value, int maxLength) {
    if (value == null) {
      return "";
    }
    String normalized =
        value
            .replaceAll("<[^>]+>", " ")
            .replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", " ")
            .replaceAll("\\s+", " ")
            .strip();
    return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
  }

  private String topic(Topic topic) {
    if (topic == null) {
      return "general";
    }
    return switch (topic) {
      case GENERAL -> "general";
      case NEWS -> "news";
      case FINANCE -> "finance";
    };
  }

  private String searchDepth(Topic topic) {
    return topic == Topic.FINANCE ? "basic" : "fast";
  }

  private String freshness(Freshness freshness) {
    if (freshness == null || freshness == Freshness.NONE) {
      return null;
    }
    return freshness.name().toLowerCase(java.util.Locale.ROOT);
  }

  private String safeRequestId(String requestId) {
    return requestId == null || requestId.length() > 100 ? "unknown" : requestId;
  }

  private long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record TavilyRequest(
      String query,
      String topic,
      @JsonProperty("search_depth") String searchDepth,
      @JsonProperty("time_range") String timeRange,
      @JsonProperty("max_results") int maxResults,
      @JsonProperty("include_answer") boolean includeAnswer,
      @JsonProperty("include_raw_content") boolean includeRawContent,
      @JsonProperty("include_images") boolean includeImages,
      @JsonProperty("auto_parameters") boolean autoParameters) {}

  private record TavilyResponse(
      List<TavilyResult> results, @JsonProperty("request_id") String requestId) {}

  private record TavilyResult(
      String url,
      String title,
      String content,
      @JsonProperty("published_date") String publishedDate,
      Double score) {}
}

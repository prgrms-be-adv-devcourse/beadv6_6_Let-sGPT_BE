package com.openat.chat.infrastructure.websearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.openat.chat.application.port.WebSearchPort.Freshness;
import com.openat.chat.application.port.WebSearchPort.Topic;
import com.openat.chat.application.port.WebSearchPort.WebSearchQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("Tavily 웹 검색 어댑터")
class TavilyWebSearchAdapterTest {

  @Test
  @DisplayName("무료 플랜에 맞는 fast·3건·원문 제외 계약으로 검색한다")
  void search_usesBoundedFastContract() {
    RestClient.Builder builder =
        RestClient.builder()
            .baseUrl("https://api.tavily.com")
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-key");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    TavilyProperties properties = new TavilyProperties();
    properties.setApiKey("test-key");
    TavilyWebSearchAdapter adapter =
        new TavilyWebSearchAdapter(
            builder.build(),
            properties,
            Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC));
    server
        .expect(requestTo("https://api.tavily.com/search"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
        .andExpect(
            content()
                .json(
                    """
                    {
                      "query": "오늘 AI 뉴스",
                      "topic": "news",
                      "search_depth": "fast",
                      "time_range": "day",
                      "max_results": 3,
                      "include_answer": false,
                      "include_raw_content": false,
                      "include_images": false,
                      "auto_parameters": false
                    }
                    """))
        .andRespond(
            withSuccess(
                """
                {
                  "results": [
                    {
                      "url": "https://example.com/news",
                      "title": "AI update",
                      "content": "<b>new</b> release",
                      "published_date": "2026-07-24",
                      "score": 0.9
                    }
                  ],
                  "request_id": "request-1"
                }
                """,
                MediaType.APPLICATION_JSON));

    var result = adapter.search(new WebSearchQuery("오늘 AI 뉴스", Topic.NEWS, Freshness.DAY));

    server.verify();
    assertThat(result.items()).hasSize(1);
    assertThat(result.items().getFirst().content()).isEqualTo("new release");
    assertThat(result.observedAt()).isEqualTo(Instant.parse("2026-07-24T01:00:00Z"));
  }

  @Test
  @DisplayName("비정상 URL과 과도한 본문은 모델 컨텍스트에 그대로 넣지 않는다")
  void search_sanitizesUntrustedResults() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://api.tavily.com");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    TavilyProperties properties = new TavilyProperties();
    properties.setApiKey("test-key");
    TavilyWebSearchAdapter adapter =
        new TavilyWebSearchAdapter(builder.build(), properties, Clock.systemUTC());
    server
        .expect(requestTo("https://api.tavily.com/search"))
        .andRespond(
            withSuccess(
                """
                {
                  "results": [
                    {"url":"javascript:alert(1)","title":"bad","content":"bad"},
                    {"url":"https://example.com/good","title":"good","content":"%s"}
                  ],
                  "request_id":"request-2"
                }
                """
                    .formatted("가".repeat(2_000)),
                MediaType.APPLICATION_JSON));

    var result = adapter.search(new WebSearchQuery("공개 정보", Topic.GENERAL, Freshness.NONE));

    assertThat(result.items()).hasSize(1);
    assertThat(result.items().getFirst().content()).hasSize(1_200);
  }

  @Test
  @DisplayName("금융 검색은 Tavily fast 제한을 피하도록 basic 깊이를 사용한다")
  void search_finance_usesBasicDepth() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://api.tavily.com");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    TavilyProperties properties = new TavilyProperties();
    properties.setApiKey("test-key");
    TavilyWebSearchAdapter adapter =
        new TavilyWebSearchAdapter(builder.build(), properties, Clock.systemUTC());
    server
        .expect(requestTo("https://api.tavily.com/search"))
        .andExpect(
            content()
                .json(
                    """
                    {
                      "query": "Bitcoin KRW price",
                      "topic": "finance",
                      "search_depth": "basic",
                      "time_range": "day",
                      "max_results": 3,
                      "include_answer": false,
                      "include_raw_content": false,
                      "include_images": false,
                      "auto_parameters": false
                    }
                    """))
        .andRespond(
            withSuccess(
                """
                {"results":[],"request_id":"request-finance"}
                """,
                MediaType.APPLICATION_JSON));

    adapter.search(new WebSearchQuery("Bitcoin KRW price", Topic.FINANCE, Freshness.DAY));

    server.verify();
  }
}

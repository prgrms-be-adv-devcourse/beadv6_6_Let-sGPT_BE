package com.openat.chat.infrastructure.inference.tool;

import com.openat.chat.application.dto.WebSearchResult;
import com.openat.chat.application.port.WebSearchPort;
import com.openat.chat.application.port.WebSearchPort.Freshness;
import com.openat.chat.application.port.WebSearchPort.Topic;
import com.openat.chat.application.port.WebSearchPort.WebSearchQuery;
import com.openat.chat.application.service.ExternalSearchPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class WebSearchTools {

  private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);

  private final WebSearchPort searchPort;
  private final ExternalSearchPolicy searchPolicy;

  public WebSearchTools(WebSearchPort searchPort, ExternalSearchPolicy searchPolicy) {
    this.searchPort = searchPort;
    this.searchPolicy = searchPolicy;
  }

  @Tool(
      name = "searchWeb",
      description =
          "최신 뉴스, 원/달러 환율, 주가, KOSPI·KOSDAQ 지수, 현재 가격, 최근 사건처럼 학습 지식만으로 답할 수 없는 공개 웹 정보를 검색한다. 사용자가 검색을 직접 말하지 않아도 현재 시장 정보에는 반드시 사용한다. OPENAT 내부 데이터에는 사용하지 않는다.")
  public AdminToolResult searchWeb(
      @ToolParam(description = "Tavily에 보낼 200자 이내 영어 검색어. 한국어 질문도 의미와 지역·통화·시점을 보존해 영어로 번역한다.")
          String query,
      @ToolParam(description = "일반 웹은 GENERAL, 뉴스는 NEWS, 가격·시장 정보는 FINANCE") Topic topic,
      @ToolParam(description = "최신성 범위. 제한이 없으면 NONE, 오늘 DAY, 최근은 WEEK 또는 MONTH")
          Freshness freshness,
      ToolContext toolContext) {
    AdminToolExecutionContext context = AdminToolContexts.required(toolContext);
    context.started();
    try {
      searchPolicy.validate(context.originalQuestion());
      searchPolicy.validate(query);
      if (!searchPort.isAvailable()) {
        return context.completed(
            AdminToolResult.failed("WEB_SEARCH_UNAVAILABLE", "웹 검색 도구가 설정되지 않았어요."));
      }
      WebSearchResult result = searchPort.search(new WebSearchQuery(query, topic, freshness));
      if (result.items().isEmpty()) {
        return context.completed(
            AdminToolResult.partial(
                "WEB_SEARCH_EMPTY",
                "조건에 맞는 공개 웹 검색 결과를 찾지 못했어요.",
                new WebSearchFacts(
                    result.query(), result.items(), result.observedAt().toString())));
      }
      return context.completed(
          AdminToolResult.success(
              new WebSearchFacts(result.query(), result.items(), result.observedAt().toString())));
    } catch (IllegalArgumentException exception) {
      return context.completed(
          AdminToolResult.failed("WEB_SEARCH_BLOCKED", exception.getMessage()));
    } catch (RuntimeException exception) {
      log.warn("웹 검색 도구 실패 errorType={}", exception.getClass().getSimpleName());
      return context.completed(
          AdminToolResult.failed("WEB_SEARCH_PROVIDER_FAILED", "웹 검색 결과를 가져오지 못했어요."));
    }
  }

  public record WebSearchFacts(
      String query, java.util.List<WebSearchResult.Item> results, String observedAt) {}
}

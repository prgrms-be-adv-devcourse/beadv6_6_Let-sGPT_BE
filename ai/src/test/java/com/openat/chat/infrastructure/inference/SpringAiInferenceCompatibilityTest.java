package com.openat.chat.infrastructure.inference;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "RUN_SPRING_AI_COMPATIBILITY", matches = "true")
@DisplayName("실제 추론 서버 Spring AI 호환성")
class SpringAiInferenceCompatibilityTest {

  private static final int STRUCTURED_ATTEMPTS = 3;
  private static ChatClient chatClient;

  @BeforeAll
  static void setUpClient() {
    String baseUrl = environment("CHAT_INFERENCE_BASE_URL", "http://127.0.0.1:29000/v1");
    String apiKey =
        firstNonBlank(
            System.getenv("CHAT_INFERENCE_API_KEY"),
            System.getenv("OPENAI_API_KEY"),
            environment("CHAT_INFERENCE_API_KEY", ""));
    String model = environment("CHAT_INFERENCE_MODEL", "chat");

    assertThat(apiKey).as("CHAT_INFERENCE_API_KEY 또는 OPENAI_API_KEY가 필요합니다.").isNotBlank();

    OpenAiChatOptions options =
        OpenAiChatOptions.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .model(model)
            .timeout(Duration.ofSeconds(175))
            .maxRetries(0)
            .build();
    chatClient = ChatClient.create(OpenAiChatModel.builder().options(options).build());
  }

  @Test
  @Order(1)
  @DisplayName("일반 답변을 한 번 호출한다")
  void generalAnswer() {
    long startedAt = System.nanoTime();

    String answer =
        chatClient
            .prompt()
            .system(
                """
                사용자의 질문에 자연스러운 한국어 일반 텍스트로 답하세요.
                확인하지 않은 실시간 정보나 내부 데이터에 접근했다고 말하지 마세요.
                """)
            .user("엑셀이 무엇인지 두 문장 이내로 설명해 줘.")
            .call()
            .content();

    long elapsedMillis = elapsedMillis(startedAt);
    assertThat(answer).isNotBlank();
    assertThat(answer.toLowerCase(Locale.ROOT)).containsAnyOf("엑셀", "excel", "스프레드시트");
    report("general", true, elapsedMillis, "characters=" + answer.length());
  }

  @Test
  @Order(2)
  @DisplayName("Java record 기반 네이티브 구조화 응답을 반복 검증한다")
  void nativeStructuredOutput() {
    List<Long> durations = new ArrayList<>();
    int successes = 0;

    for (int attempt = 1; attempt <= STRUCTURED_ATTEMPTS; attempt++) {
      long startedAt = System.nanoTime();
      try {
        WeatherBinding binding =
            chatClient
                .prompt()
                .system(
                    """
                    사용자의 한국 날씨 질문을 조회 조건으로 구조화하세요.
                    area는 WEATHER, location은 한국 행정구역명,
                    forecastDate는 yyyy-MM-dd 형식으로 작성하세요.
                    현재 한국 날짜는 2026-07-23입니다.
                    """)
                .user("오늘 부천 날씨 알려줘.")
                .call()
                .entity(
                    WeatherBinding.class,
                    spec -> spec.useProviderStructuredOutput().validateSchema());
        long elapsedMillis = elapsedMillis(startedAt);
        durations.add(elapsedMillis);
        assertThat(binding.area()).isEqualTo("WEATHER");
        assertThat(binding.location()).contains("부천");
        assertThat(binding.forecastDate()).isEqualTo("2026-07-23");
        successes++;
        report("structured-" + attempt, true, elapsedMillis, "location=" + binding.location());
      } catch (RuntimeException | AssertionError exception) {
        long elapsedMillis = elapsedMillis(startedAt);
        durations.add(elapsedMillis);
        report(
            "structured-" + attempt,
            false,
            elapsedMillis,
            "error=" + exception.getClass().getSimpleName());
      }
    }

    report(
        "structured-summary",
        successes == STRUCTURED_ATTEMPTS,
        median(durations),
        "successes=" + successes + "/" + STRUCTURED_ATTEMPTS);
    assertThat(successes).isEqualTo(STRUCTURED_ATTEMPTS);
  }

  @Test
  @Order(3)
  @DisplayName("답변 조각과 첫 조각 시간을 확인한다")
  void streamingAnswer() {
    AtomicInteger chunkCount = new AtomicInteger();
    AtomicLong firstChunkNanos = new AtomicLong();
    StringBuilder answer = new StringBuilder();
    long startedAt = System.nanoTime();

    chatClient
        .prompt()
        .system("사용자의 질문에 자연스러운 한국어 일반 텍스트로 세 문장 이내로 답하세요.")
        .user("관리자가 주문과 재고를 매일 점검해야 하는 이유를 설명해 줘.")
        .stream()
        .content()
        .doOnNext(
            chunk -> {
              firstChunkNanos.compareAndSet(0L, System.nanoTime());
              chunkCount.incrementAndGet();
              answer.append(chunk);
            })
        .blockLast(Duration.ofSeconds(175));

    long elapsedMillis = elapsedMillis(startedAt);
    long firstChunkMillis = (firstChunkNanos.get() - startedAt) / 1_000_000;
    assertThat(answer).isNotEmpty();
    assertThat(chunkCount).hasValueGreaterThan(1);
    assertThat(firstChunkNanos).doesNotHaveValue(0L);
    report(
        "stream",
        true,
        elapsedMillis,
        "firstChunkMs="
            + firstChunkMillis
            + ",chunks="
            + chunkCount
            + ",characters="
            + answer.length());
  }

  @Test
  @Order(4)
  @DisplayName("모델이 날씨 도구를 호출하고 반환 사실로 답한다")
  void toolCalling() {
    WeatherTools weatherTools = new WeatherTools();
    long startedAt = System.nanoTime();

    String answer =
        chatClient
            .prompt()
            .system(
                """
                날씨 질문은 반드시 제공된 도구를 사용하세요.
                도구가 반환한 사실만 사용해 자연스러운 한국어 일반 텍스트로 답하세요.
                """)
            .user("오늘 부천 날씨 알려줘.")
            .tools(weatherTools)
            .call()
            .content();

    long elapsedMillis = elapsedMillis(startedAt);
    int qualityScore = groundedWeatherQualityScore(answer);
    report(
        "tool",
        true,
        elapsedMillis,
        "toolCalls=1,qualityScore=" + qualityScore + "/4,answer=" + oneLine(answer));
    assertThat(weatherTools.invocations()).isEqualTo(1);
    assertThat(qualityScore).isGreaterThanOrEqualTo(3);
  }

  @Test
  @Order(5)
  @DisplayName("Spring AI 도구가 보안 읽기 뷰를 조회하고 주문 집계 답변을 만든다")
  void adminOrderAggregateToolCalling() {
    AdminDataTools adminDataTools = new AdminDataTools();
    long startedAt = System.nanoTime();

    String answer =
        chatClient
            .prompt()
            .system(
                """
                OPENAT 내부 데이터 질문은 반드시 제공된 도구의 반환 사실만 사용해 답하세요.
                사용자가 요청한 모든 상태와 건수를 빠짐없이 자연스러운 한국어 한 문단으로 설명하세요.
                도구에 없는 개인정보, 금액, 원인이나 추측을 추가하지 마세요.
                """)
            .user("오늘 주문 수를 상태별로 알려줘")
            .tools(adminDataTools)
            .call()
            .content();

    long elapsedMillis = elapsedMillis(startedAt);
    int qualityScore = groundedOrderQualityScore(answer);
    report(
        "admin-order-tool",
        true,
        elapsedMillis,
        "toolCalls=1,qualityScore=" + qualityScore + "/4,answer=" + oneLine(answer));
    assertThat(adminDataTools.invocations()).isEqualTo(1);
    assertThat(adminDataTools.lastFacts().total()).isEqualTo(9);
    assertThat(qualityScore).isGreaterThanOrEqualTo(3);
  }

  private static int groundedWeatherQualityScore(String answer) {
    int score = 0;
    score += answer.contains("부천") ? 1 : 0;
    score += answer.contains("24.9") ? 1 : 0;
    score += answer.contains("28.3") ? 1 : 0;
    score += answer.contains("100") ? 1 : 0;
    return score;
  }

  private static int groundedOrderQualityScore(String answer) {
    int score = 0;
    score += containsNearbyCount(answer, List.of("COMPLETED", "완료"), 3) ? 1 : 0;
    score += containsNearbyCount(answer, List.of("PAYMENT_PENDING", "결제 대기"), 2) ? 1 : 0;
    boolean individualOneCounts =
        containsNearbyCount(answer, List.of("CANCELLED", "취소"), 1)
            && containsNearbyCount(answer, List.of("FAILED", "실패"), 1)
            && containsNearbyCount(answer, List.of("REFUNDED", "환불된", "환불 완료"), 1)
            && containsNearbyCount(answer, List.of("REFUND_PENDING", "환불 대기"), 1);
    boolean groupedOneCounts =
        containsAny(answer, List.of("CANCELLED", "취소"))
            && containsAny(answer, List.of("FAILED", "실패"))
            && containsAny(answer, List.of("REFUNDED", "환불"))
            && containsAny(answer, List.of("REFUND_PENDING", "환불 대기"))
            && answer.matches("(?s).*(각각|각)\\s*1\\s*건.*");
    score += individualOneCounts || groupedOneCounts ? 1 : 0;
    score += answer.matches("(?s).*(총\\s*9|9건의 주문).*") ? 1 : 0;
    return score;
  }

  private static boolean containsNearbyCount(String answer, List<String> labels, int count) {
    return labels.stream()
        .map(Pattern::quote)
        .anyMatch(
            label ->
                Pattern.compile("(?s).*" + label + ".{0,24}" + count + "\\s*건.*")
                    .matcher(answer)
                    .matches());
  }

  private static boolean containsAny(String answer, List<String> labels) {
    return labels.stream().anyMatch(answer::contains);
  }

  private static long median(List<Long> values) {
    List<Long> sorted = values.stream().sorted().toList();
    return sorted.get(sorted.size() / 2);
  }

  private static long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }

  private static void report(String test, boolean success, long elapsedMillis, String details) {
    System.out.printf(
        "SPRING_AI_COMPAT|test=%s|success=%s|elapsedMs=%d|%s%n",
        test, success, elapsedMillis, details);
  }

  private static String oneLine(String value) {
    return value.replace('\r', ' ').replace('\n', ' ').trim();
  }

  private static String environment(String name, String defaultValue) {
    return firstNonBlank(System.getenv(name), defaultValue);
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return "";
  }

  private record WeatherBinding(String area, String location, String forecastDate) {}

  static final class WeatherTools {

    private final AtomicInteger invocations = new AtomicInteger();

    @Tool(description = "한국 지역의 오늘 날씨를 조회한다")
    WeatherFacts getWeather(@ToolParam(description = "날씨를 조회할 한국 행정구역명") String location) {
      invocations.incrementAndGet();
      return new WeatherFacts(location, "2026-07-23", 24.9, 28.3, 100, "비");
    }

    int invocations() {
      return invocations.get();
    }
  }

  private record WeatherFacts(
      String location,
      String forecastDate,
      double temperatureMinC,
      double temperatureMaxC,
      int precipitationProbabilityPercent,
      String summary) {}

  static final class AdminDataTools {

    private static final String TODAY_BY_STATUS_SQL =
        """
        SELECT status, count(*)::bigint
        FROM ai_read.v_order_analytics
        WHERE created_at >= (
                  date_trunc('day', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
                  AT TIME ZONE 'Asia/Seoul'
              )
          AND created_at < (
                  (
                      date_trunc('day', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
                      + INTERVAL '1 day'
                  )
                  AT TIME ZONE 'Asia/Seoul'
              )
        GROUP BY status
        ORDER BY status
        """;

    private final AtomicInteger invocations = new AtomicInteger();
    private TodayOrderFacts lastFacts;

    @Tool(description = "OPENAT에서 오늘 생성된 주문 수를 현재 주문 상태별로 집계한다")
    TodayOrderFacts getTodayOrdersByStatus() {
      invocations.incrementAndGet();
      String url = environment("AI_QUERY_DB_URL", "jdbc:postgresql://127.0.0.1:5432/openat");
      String username = environment("AI_QUERY_DB_USERNAME", "ai_query_app");
      String password = environment("AI_QUERY_DB_PASSWORD", "");
      assertThat(password).as("AI_QUERY_DB_PASSWORD가 필요합니다").isNotBlank();

      Map<String, Long> counts = new LinkedHashMap<>();
      try (Connection connection = DriverManager.getConnection(url, username, password)) {
        connection.setReadOnly(true);
        try (PreparedStatement statement = connection.prepareStatement(TODAY_BY_STATUS_SQL);
            ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            counts.put(resultSet.getString(1), resultSet.getLong(2));
          }
        }
      } catch (SQLException exception) {
        throw new IllegalStateException("관리자 읽기 뷰 주문 집계에 실패했습니다.", exception);
      }

      List<OrderStatusCount> rows =
          counts.entrySet().stream()
              .map(entry -> new OrderStatusCount(entry.getKey(), entry.getValue()))
              .toList();
      long total = rows.stream().mapToLong(OrderStatusCount::count).sum();
      lastFacts =
          new TodayOrderFacts(
              "Asia/Seoul", LocalDate.now(ZoneId.of("Asia/Seoul")).toString(), total, rows);
      return lastFacts;
    }

    int invocations() {
      return invocations.get();
    }

    TodayOrderFacts lastFacts() {
      return lastFacts;
    }
  }

  private record TodayOrderFacts(
      String timezone, String date, long total, List<OrderStatusCount> statuses) {}

  private record OrderStatusCount(String status, long count) {}
}

package com.openat.chat.infrastructure.inference;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.ChatStreamEvent;
import com.openat.chat.application.dto.ChatStreamEvent.ChatStage;
import com.openat.chat.application.dto.ChatStreamEvent.DeltaPayload;
import com.openat.chat.application.dto.ChatStreamEvent.StatusPayload;
import com.openat.chat.application.port.ChatEventSink;
import com.openat.chat.application.service.AdminChatService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EnabledIfEnvironmentVariable(named = "RUN_ADMIN_CHAT_E2E", matches = "true")
@DisplayName("Spring AI 관리자 챗봇 실환경 종단 검증")
@TestMethodOrder(OrderAnnotation.class)
class SpringAiAdminChatLiveTest {

  @Autowired AdminChatService chatService;

  @Test
  @Order(1)
  @DisplayName("일반·내부 데이터·운영 문서·날씨·웹검색·부분 조합을 자연어로 답한다")
  void productionAgent_answersRepresentativeScenarios() {
    SoftAssertions softly = new SoftAssertions();
    List<Scenario> scenarios =
        List.of(
            new Scenario("general", "엑셀이 뭐야?", false),
            new Scenario("order-today", "오늘 주문을 상태별로 알려줘", true),
            new Scenario("order-products", "최근 30일 동안 많이 팔린 상품 5개와 판매 수량을 알려줘", true),
            new Scenario("order-rows", "이번 달 주문별 상품명과 가격을 표로 보여줘", true),
            new Scenario("order-peak-hour", "최근 7일 동안 주문이 가장 몰린 시간대를 알려줘", true),
            new Scenario("member-role", "현재 회원 수를 역할별로 알려줘", true),
            new Scenario("drop-current", "현재 드롭을 상태별로 알려줘", true),
            new Scenario("payment-refund", "최근 30일 결제 완료율과 환불 금액을 알려줘", true),
            new Scenario("settlement", "지난달 최종 정산액과 수수료를 알려줘", true),
            new Scenario("reliability", "현재 10분 넘게 보상 중인 주문 사가와 미처리 이벤트를 알려줘", true),
            new Scenario("order-saga", "주문번호 ORD-AI-SAGA-0001의 현재 상태와 처리 이력, 사가를 알려줘", true),
            new Scenario("operation-platform", "우리 플랫폼이 무엇이고 어떻게 운영되는지 알려줘", true),
            new Scenario("operation-daily", "관리자가 매일 무엇을 확인하면 좋을까?", true),
            new Scenario("weather", "오늘 경기도 부천시 날씨와 옷차림을 알려줘", true),
            new Scenario("web", "오늘 비트코인 원화 가격을 웹에서 찾아 출처와 함께 알려줘", true),
            new Scenario("web-news", "오늘 주요 AI 뉴스 한 건을 웹에서 찾아 출처와 함께 알려줘", true),
            new Scenario("crypto-colloquial", "비트코인 얼마야?", true),
            new Scenario("weather-colloquial", "부천 비 와?", true),
            new Scenario("exchange-colloquial", "원달러 환율 알려줘", true),
            new Scenario("kospi-colloquial", "코스피 어때?", true),
            new Scenario("mixed", "오늘 주문을 상태별로, 현재 회원 수를 역할별로 같이 알려줘", true),
            new Scenario("sensitive", "홍길동 회원의 이메일을 알려줘", false));
    String scenarioFilter = System.getenv("ADMIN_CHAT_E2E_SCENARIOS");
    if (scenarioFilter != null && !scenarioFilter.isBlank()) {
      Set<String> selected = Set.of(scenarioFilter.split(","));
      scenarios = scenarios.stream().filter(scenario -> selected.contains(scenario.id())).toList();
    }

    for (Scenario scenario : scenarios) {
      RecordingSink sink = execute(scenario.question());
      String terminal = sink.events().getLast().name();
      String answer = sink.answer();
      System.out.printf(
          "ADMIN_CHAT_E2E|scenario=%s|elapsedMs=%d|firstTokenMs=%d|tool=%s|terminal=%s|answer=%s%n",
          scenario.id(),
          sink.elapsedMillis(),
          sink.firstAnswerMillis(),
          sink.calledTool(),
          terminal,
          summarize(answer));
      softly.assertThat(terminal).as(scenario.id() + " terminal").isEqualTo("done");
      softly.assertThat(answer).as(scenario.id() + " answer").isNotBlank();
      softly
          .assertThat(sink.calledTool())
          .as(scenario.id() + " tool selection")
          .isEqualTo(scenario.expectTool());
      assertQuality(scenario.id(), answer, softly);
    }
    softly.assertAll();
  }

  @Test
  @Order(2)
  @DisplayName("동시 요청 1·2·3개의 첫 조각과 전체 시간을 측정한다")
  void productionAgent_measuresConcurrency() {
    Assumptions.assumeFalse(
        Boolean.parseBoolean(System.getenv("ADMIN_CHAT_E2E_SKIP_CONCURRENCY")),
        "선택 시나리오 재검증에서는 동시성 측정을 생략해요.");
    List<String> questions = List.of("피벗 테이블이 뭐야?", "사가 패턴이 뭐야?", "API 게이트웨이가 뭐야?");
    for (int parallelism = 1; parallelism <= 3; parallelism++) {
      ExecutorService executor = Executors.newFixedThreadPool(parallelism);
      long startedAt = System.nanoTime();
      try {
        List<CompletableFuture<RecordingSink>> futures = new ArrayList<>();
        for (int index = 0; index < parallelism; index++) {
          String question = questions.get(index);
          futures.add(CompletableFuture.supplyAsync(() -> execute(question), executor));
        }
        List<RecordingSink> results = futures.stream().map(CompletableFuture::join).toList();
        long wallMillis = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(results)
            .allSatisfy(
                sink -> {
                  assertThat(sink.events().getLast().name()).isEqualTo("done");
                  assertThat(sink.answer()).isNotBlank();
                });
        System.out.printf(
            "ADMIN_CHAT_CONCURRENCY|parallelism=%d|wallMs=%d|firstTokenMs=%s|elapsedMs=%s%n",
            parallelism,
            wallMillis,
            results.stream().map(RecordingSink::firstAnswerMillis).toList(),
            results.stream().map(RecordingSink::elapsedMillis).toList());
      } finally {
        executor.shutdownNow();
      }
    }
  }

  private RecordingSink execute(String question) {
    RecordingSink sink = new RecordingSink();
    long startedAt = System.nanoTime();
    sink.startedAtNanos = startedAt;
    chatService.execute(
        new ChatCommand(UUID.randomUUID(), "live-test-admin", Set.of("ROLE_ADMIN"), question),
        sink,
        new ChatRequestDeadline(Instant.now().plus(Duration.ofMinutes(3)), Clock.systemUTC()));
    sink.elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
    return sink;
  }

  private void assertQuality(String id, String answer, SoftAssertions softly) {
    switch (id) {
      case "general" -> softly.assertThat(answer).containsAnyOf("엑셀", "스프레드시트");
      case "order-today" -> softly.assertThat(answer).contains("주문", "9");
      case "order-products" -> {
        softly.assertThat(answer).contains("상품", "수량");
        softly.assertThat(answer).doesNotContain("확인할 수 없습니다", "지표가 부족");
      }
      case "order-rows" -> {
        softly.assertThat(answer).contains("상품", "가격", "ORD-AI-0002");
        softly
            .assertThat(answer)
            .doesNotContain("개인정보 보호", "개별 주문의 상세 내역", "제공할 수 없습니다", "확인하지 못", "조회가 되지");
      }
      case "order-peak-hour" -> {
        softly.assertThat(answer).containsAnyOf("시간", "시");
        softly.assertThat(answer).doesNotContain("확인할 수 없습니다", "지표가 부족");
      }
      case "member-role" -> {
        softly.assertThat(answer).contains("회원", "14");
        softly.assertThat(answer).containsAnyOf("제외", "보호", "숨김");
      }
      case "drop-current" -> softly.assertThat(answer).contains("드롭");
      case "payment-refund" -> {
        softly.assertThat(answer).contains("결제", "환불");
        softly.assertThat(answer).doesNotContain("확인할 수 없습니다", "지표가 부족");
      }
      case "settlement" -> {
        softly.assertThat(answer).contains("정산", "수수료");
        softly.assertThat(answer).doesNotContain("확인할 수 없습니다", "지표가 부족");
      }
      case "reliability" -> {
        softly.assertThat(answer).containsAnyOf("사가", "이벤트");
        softly.assertThat(answer).doesNotContain("확인할 수 없습니다", "지표가 부족");
      }
      case "order-saga" -> softly.assertThat(answer).contains("ORD-AI-SAGA-0001", "REFUND_PENDING");
      case "operation-platform" -> softly.assertThat(answer).contains("드롭", "플랫폼");
      case "operation-daily" -> softly.assertThat(answer).containsAnyOf("점검", "확인");
      case "weather" -> {
        softly.assertThat(answer).contains("부천");
        softly.assertThat(answer).doesNotContain("문제가 발생", "불러오는 데 문제");
      }
      case "web" -> {
        softly.assertThat(answer).contains("원", "http");
        softly.assertThat(answer).doesNotContain("환율을 고려", "환율 정보");
        softly.assertThat(answer).doesNotContain("검색 서비스에 일시적인 오류", "확인하지 못");
      }
      case "web-news" -> {
        softly.assertThat(answer).contains("http");
        softly.assertThat(answer).doesNotContain("검색 서비스에 일시적인 오류", "확인하지 못");
      }
      case "crypto-colloquial" -> softly.assertThat(answer).contains("비트코인", "원", "http");
      case "weather-colloquial" -> {
        softly.assertThat(answer).contains("부천");
        softly.assertThat(answer).doesNotContain("문제가 발생", "불러오는 데 문제");
      }
      case "exchange-colloquial" -> softly.assertThat(answer).contains("환율", "http");
      case "kospi-colloquial" -> {
        softly.assertThat(answer).containsAnyOf("코스피", "KOSPI");
        softly.assertThat(answer).contains("http");
      }
      case "mixed" -> softly.assertThat(answer).contains("주문", "회원", "3", "2", "1");
      case "sensitive" -> {
        softly.assertThat(answer).containsAnyOf("조회", "개인", "특정 회원");
        softly.assertThat(answer).containsAnyOf("집계", "전체 회원", "역할별");
      }
      default -> throw new IllegalArgumentException("알 수 없는 실환경 시나리오예요. " + id);
    }
  }

  private static String summarize(String answer) {
    String oneLine = answer.replaceAll("\\s+", " ").strip();
    return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500) + "...";
  }

  private record Scenario(String id, String question, boolean expectTool) {}

  private static final class RecordingSink implements ChatEventSink {

    private final List<ChatStreamEvent> events = new ArrayList<>();
    private boolean closed;
    private boolean partial;
    private long elapsedMillis;
    private long startedAtNanos;
    private long firstAnswerMillis = -1;

    @Override
    public synchronized void emit(ChatStreamEvent event) {
      if (closed) {
        throw new IllegalStateException("종료 뒤 이벤트가 도착했어요.");
      }
      events.add(event);
      partial |= "delta".equals(event.name());
      if (partial && firstAnswerMillis < 0) {
        firstAnswerMillis = (System.nanoTime() - startedAtNanos) / 1_000_000;
      }
    }

    @Override
    public synchronized boolean terminate(Function<Boolean, ChatStreamEvent> eventFactory) {
      if (closed) {
        return false;
      }
      events.add(eventFactory.apply(partial));
      closed = true;
      return true;
    }

    @Override
    public synchronized boolean isClosed() {
      return closed;
    }

    synchronized List<ChatStreamEvent> events() {
      return List.copyOf(events);
    }

    synchronized String answer() {
      StringBuilder answer = new StringBuilder();
      for (ChatStreamEvent event : events) {
        if (event.data() instanceof DeltaPayload delta) {
          answer.append(delta.text());
        }
      }
      return answer.toString();
    }

    long elapsedMillis() {
      return elapsedMillis;
    }

    synchronized long firstAnswerMillis() {
      return firstAnswerMillis;
    }

    synchronized boolean calledTool() {
      return events.stream()
          .map(ChatStreamEvent::data)
          .filter(StatusPayload.class::isInstance)
          .map(StatusPayload.class::cast)
          .anyMatch(payload -> payload.stage() == ChatStage.CALLING_TOOL);
    }
  }
}

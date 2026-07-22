package com.openat.chat.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openat.chat.application.dto.AdminAnalyticsQueryResult.Row;
import com.openat.chat.application.dto.AdminAnalyticsQueryResult.Series;
import com.openat.chat.application.port.DataQueryCapabilityState;
import com.openat.chat.domain.planning.AggregateTimeScope;
import com.openat.chat.domain.planning.PlanningDateRange;
import com.openat.chat.domain.planning.TrendGrain;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Comparison;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dataset;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dimension;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.FilterField;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Measure;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Query;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.SortDirection;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.TimeField;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("관리자 분석 읽기 어댑터")
class JdbcAdminAnalyticsQueryAdapterTest {

  private static final Instant NOW = Instant.parse("2026-07-24T06:00:00Z");
  private JdbcAdminAnalyticsQueryAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter =
        new JdbcAdminAnalyticsQueryAdapter(
            mock(NamedParameterJdbcTemplate.class),
            mock(DataQueryCapabilityState.class),
            mock(PlatformTransactionManager.class),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("카탈로그로 고정된 주문 지표·분류만 SQL로 컴파일한다")
  void compile_orderRanking_usesFixedCatalog() {
    Query query =
        new Query(
            Dataset.ORDER,
            List.of(Measure.COMPLETED_QUANTITY, Measure.GROSS_COMPLETED_AMOUNT),
            List.of(Dimension.PRODUCT_NAME),
            TimeField.COMPLETED_AT,
            AggregateTimeScope.CREATED_PERIOD,
            range(),
            TrendGrain.NONE,
            Comparison.NONE,
            Map.of(FilterField.STATUS, List.of("COMPLETED")),
            Measure.COMPLETED_QUANTITY,
            SortDirection.DESC,
            5);

    JdbcAdminAnalyticsQueryAdapter.CompiledQuery compiled =
        adapter.compile(query, query.period(), NOW);

    assertThat(compiled.sql())
        .contains("FROM ai_read.v_order_analytics")
        .contains("SUM(quantity)")
        .contains("product_name::text AS dimension_0")
        .contains("status IN (:filter0)")
        .contains("ORDER BY measure_0 DESC")
        .doesNotContain("COMPLETED");
    assertThat(compiled.parameters().getValue("filter0")).isEqualTo(List.of("COMPLETED"));
    assertThat(compiled.parameters().getValue("fetchLimit")).isEqualTo(6);
  }

  @Test
  @DisplayName("주문별 상품명과 가격은 고정된 공개 필드만 SQL로 컴파일한다")
  void compile_orderRows_usesOnlySafePublicFields() {
    Query query =
        new Query(
            Dataset.ORDER,
            List.of(Measure.ORDER_QUANTITY, Measure.ORDER_UNIT_PRICE, Measure.ORDER_TOTAL_PRICE),
            List.of(Dimension.ORDER_NUMBER, Dimension.PRODUCT_NAME),
            TimeField.CREATED_AT,
            AggregateTimeScope.CREATED_PERIOD,
            range(),
            TrendGrain.NONE,
            Comparison.NONE,
            Map.of(),
            Measure.ORDER_TOTAL_PRICE,
            SortDirection.DESC,
            20);

    JdbcAdminAnalyticsQueryAdapter.CompiledQuery compiled =
        adapter.compile(query, query.period(), NOW);

    assertThat(compiled.sql())
        .contains("FROM ai_read.v_order_analytics")
        .contains("order_number::text AS dimension_0")
        .contains("product_name::text AS dimension_1")
        .contains("MAX(quantity)::numeric")
        .contains("MAX(unit_price)::numeric")
        .contains("MAX(total_price)::numeric")
        .contains("created_at >= :fromInclusive")
        .contains("created_at < :toExclusive")
        .doesNotContain("member_id", "seller_id", "email", "phone");
    assertThat(compiled.parameters().getValue("fetchLimit")).isEqualTo(21);
  }

  @Test
  @DisplayName("개별 상품의 미설정 가격은 0원으로 치환하지 않는다")
  void compile_productRows_preservesUnconfiguredPrice() {
    Query query =
        new Query(
            Dataset.PRODUCT,
            List.of(Measure.PRODUCT_PRICE),
            List.of(Dimension.PRODUCT_ID, Dimension.PRODUCT_NAME),
            TimeField.NONE,
            AggregateTimeScope.CURRENT_SNAPSHOT,
            null,
            TrendGrain.NONE,
            Comparison.NONE,
            Map.of(),
            Measure.PRODUCT_PRICE,
            SortDirection.DESC,
            20);

    JdbcAdminAnalyticsQueryAdapter.CompiledQuery compiled = adapter.compile(query, null, NOW);

    assertThat(compiled.sql())
        .contains("MAX(price)::numeric AS measure_0")
        .doesNotContain("COALESCE(MAX(price)");
  }

  @Test
  @DisplayName("자유 입력 상품명은 SQL 문자열에 결합하지 않고 바인딩한다")
  void compile_freeTextFilter_bindsValue() {
    String productName = "상품'); DROP TABLE orders.orders; --";
    Query query =
        new Query(
            Dataset.DROP,
            List.of(Measure.REMAINING_STOCK),
            List.of(Dimension.PRODUCT_NAME),
            TimeField.NONE,
            AggregateTimeScope.CURRENT_SNAPSHOT,
            null,
            TrendGrain.NONE,
            Comparison.NONE,
            Map.of(FilterField.PRODUCT_NAME, List.of(productName)),
            Measure.REMAINING_STOCK,
            SortDirection.ASC,
            10);

    JdbcAdminAnalyticsQueryAdapter.CompiledQuery compiled = adapter.compile(query, null, NOW);

    assertThat(compiled.sql()).contains("product_name IN (:filter0)").doesNotContain(productName);
    assertThat(compiled.parameters().getValue("filter0")).isEqualTo(List.of(productName));
  }

  @Test
  @DisplayName("시간대와 요일 분류는 KST 기간 열에서만 계산한다")
  void compile_hourlyPayment_usesKstTimeColumn() {
    Query query =
        new Query(
            Dataset.PAYMENT,
            List.of(Measure.APPROVED_PAYMENT_COUNT),
            List.of(Dimension.HOUR_OF_DAY),
            TimeField.APPROVED_AT,
            AggregateTimeScope.CREATED_PERIOD,
            range(),
            TrendGrain.NONE,
            Comparison.NONE,
            Map.of(),
            Measure.APPROVED_PAYMENT_COUNT,
            SortDirection.DESC,
            20);

    JdbcAdminAnalyticsQueryAdapter.CompiledQuery compiled =
        adapter.compile(query, query.period(), NOW);

    assertThat(compiled.sql())
        .contains("approved_at >= :fromInclusive")
        .contains("approved_at < :toExclusive")
        .contains("approved_at AT TIME ZONE 'Asia/Seoul'");
  }

  @Test
  @DisplayName("현재 미처리 이벤트는 기간 조건 없이 고정 지표로 컴파일한다")
  void compile_pendingEventsSnapshot_hasNoTimePredicate() {
    Query query =
        new Query(
            Dataset.EVENT_PIPELINE,
            List.of(Measure.PENDING_EVENT_COUNT),
            List.of(),
            TimeField.NONE,
            AggregateTimeScope.CURRENT_SNAPSHOT,
            null,
            TrendGrain.NONE,
            Comparison.NONE,
            Map.of(),
            Measure.PENDING_EVENT_COUNT,
            SortDirection.DESC,
            10);

    JdbcAdminAnalyticsQueryAdapter.CompiledQuery compiled = adapter.compile(query, null, NOW);

    assertThat(compiled.sql())
        .contains("FROM ai_read.v_event_pipeline_analytics")
        .contains("status IN ('PENDING', 'READY')")
        .doesNotContain(":fromInclusive", ":toExclusive");
  }

  @Test
  @DisplayName("보호 집계는 공개 limit 전에 최대 201개 후보를 조회한다")
  void compile_protectedDataset_fetchesCandidatesBeforePublicLimit() {
    Query query =
        new Query(
            Dataset.MEMBER_CURRENT,
            List.of(Measure.MEMBER_COUNT),
            List.of(Dimension.ROLE),
            TimeField.NONE,
            AggregateTimeScope.CURRENT_SNAPSHOT,
            null,
            TrendGrain.NONE,
            Comparison.NONE,
            Map.of(),
            Measure.MEMBER_COUNT,
            SortDirection.DESC,
            3);

    JdbcAdminAnalyticsQueryAdapter.CompiledQuery compiled = adapter.compile(query, null, NOW);

    assertThat(compiled.parameters().getValue("fetchLimit")).isEqualTo(201);
  }

  @Test
  @DisplayName("회원 가입과 탈퇴의 기여자 수는 요청 지표별 보수적 하한을 사용한다")
  void compile_memberRegistration_usesMetricSpecificContributorCount() {
    Query newOnly = memberRegistration(List.of(Measure.NEW_MEMBER_COUNT));
    Query both =
        memberRegistration(List.of(Measure.NEW_MEMBER_COUNT, Measure.WITHDRAWN_MEMBER_COUNT));

    assertThat(adapter.compile(newOnly, newOnly.period(), NOW).sql())
        .contains("SUM(new_member_count)")
        .doesNotContain("SUM(new_member_count + withdrawn_member_count)");
    assertThat(adapter.compile(both, both.period(), NOW).sql())
        .contains(
            "LEAST(COALESCE(SUM(new_member_count), 0), COALESCE(SUM(withdrawn_member_count), 0))");
  }

  @Test
  @DisplayName("1차와 보완 억제는 시간 버킷별로 독립 적용한다")
  void suppressProtectedRows_appliesSecondarySuppressionPerBucket() {
    Instant firstBucket = Instant.parse("2026-07-01T00:00:00Z");
    Instant secondBucket = Instant.parse("2026-07-02T00:00:00Z");
    Row primarySuppressed = row(firstBucket, "LOCAL", 4);
    Row secondarySuppressed = row(firstBucket, "KAKAO", 10);
    Row otherBucket = row(secondBucket, "GOOGLE", 10);

    JdbcAdminAnalyticsQueryAdapter.QueryBatch result =
        adapter.suppressProtectedRows(
            List.of(primarySuppressed, secondarySuppressed, otherBucket),
            Dataset.MEMBER_REGISTRATION,
            false);

    assertThat(result.rows()).containsExactly(otherBucket);
    assertThat(result.suppressedRowCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("보호 집계는 k=5부터 공개한다")
  void suppressProtectedRows_allowsMinimumGroupSize() {
    Row minimum = row(Instant.parse("2026-07-01T00:00:00Z"), "LOCAL", 5);

    JdbcAdminAnalyticsQueryAdapter.QueryBatch result =
        adapter.suppressProtectedRows(List.of(minimum), Dataset.MEMBER_REGISTRATION, false);

    assertThat(result.rows()).containsExactly(minimum);
    assertThat(result.suppressedRowCount()).isZero();
  }

  private Query memberRegistration(List<Measure> measures) {
    return new Query(
        Dataset.MEMBER_REGISTRATION,
        measures,
        List.of(Dimension.PLATFORM),
        TimeField.PERIOD_START,
        AggregateTimeScope.CREATED_PERIOD,
        range(),
        TrendGrain.DAY,
        Comparison.NONE,
        Map.of(),
        measures.getFirst(),
        SortDirection.DESC,
        10);
  }

  private Row row(Instant bucket, String platform, long contributors) {
    return new Row(
        Series.CURRENT,
        bucket,
        Map.of("PLATFORM", platform),
        Map.of("NEW_MEMBER_COUNT", BigDecimal.valueOf(contributors)),
        contributors);
  }

  private PlanningDateRange range() {
    ZoneId kst = ZoneId.of("Asia/Seoul");
    return new PlanningDateRange(
        ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, kst),
        ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, kst));
  }
}

package com.openat.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.chat.application.port.AdminChatInferencePort.FilterSpec;
import com.openat.chat.application.port.AdminChatInferencePort.QuerySpec;
import com.openat.chat.domain.planning.TimeRangePreset;
import com.openat.chat.domain.planning.TrendGrain;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Comparison;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dataset;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dimension;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.FilterField;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Measure;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.SortDirection;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.TimeField;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("구조화 응답의 고정 분석 계획 변환")
class AdminAnalyticsPlanFactoryTest {

  private final AdminAnalyticsPlanFactory factory =
      new AdminAnalyticsPlanFactory(
          new AdminQueryPeriodResolver(
              Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC)));

  @Test
  @DisplayName("잘못된 선택 필드는 버리고 실행 가능한 형제 필드로 계획을 만든다")
  void create_partiallyInvalidFields_salvagesValidFields() {
    QuerySpec request =
        new QuerySpec(
            Dataset.ORDER,
            List.of("ORDER_COUNT", "MEMBER_COUNT", "ORDER_COUNT"),
            List.of("PRODUCT_NAME", "ROLE"),
            "UNKNOWN_TIME",
            TimeRangePreset.LAST_MONTH,
            "",
            "",
            TrendGrain.WEEK,
            Comparison.PREVIOUS_PERIOD,
            List.of(
                new FilterSpec("STATUS", List.of("COMPLETED")),
                new FilterSpec("PLATFORM", List.of("LOCAL"))),
            "MEMBER_COUNT",
            SortDirection.DESC,
            99);

    AdminAnalyticsPlanFactory.PreparedQuery result = factory.create(request);

    assertThat(result.query().measures()).containsExactly(Measure.ORDER_COUNT);
    assertThat(result.query().dimensions()).containsExactly(Dimension.PRODUCT_NAME);
    assertThat(result.query().timeField()).isEqualTo(TimeField.CREATED_AT);
    assertThat(result.query().filters())
        .containsExactlyEntriesOf(java.util.Map.of(FilterField.STATUS, List.of("COMPLETED")));
    assertThat(result.query().orderBy()).isEqualTo(Measure.ORDER_COUNT);
    assertThat(result.query().limit()).isEqualTo(20);
    assertThat(result.failures())
        .extracting(AdminAnalyticsPlanFactory.FieldFailure::field)
        .contains("metrics", "dimensions", "timeField", "filters", "orderBy", "limit");
  }

  @Test
  @DisplayName("이번 달 주문별 상품명과 가격 요청을 실행 가능한 계획으로 변환한다")
  void create_monthlyOrderRows_preservesSafeDetailFields() {
    QuerySpec request =
        new QuerySpec(
            Dataset.ORDER,
            List.of("ORDER_QUANTITY", "ORDER_UNIT_PRICE", "ORDER_TOTAL_PRICE"),
            List.of("ORDER_NUMBER", "PRODUCT_NAME"),
            "CREATED_AT",
            TimeRangePreset.THIS_MONTH,
            "",
            "",
            TrendGrain.NONE,
            Comparison.NONE,
            List.of(),
            "ORDER_TOTAL_PRICE",
            SortDirection.DESC,
            20);

    AdminAnalyticsPlanFactory.PreparedQuery result = factory.create(request);

    assertThat(result.query().measures())
        .containsExactly(
            Measure.ORDER_QUANTITY, Measure.ORDER_UNIT_PRICE, Measure.ORDER_TOTAL_PRICE);
    assertThat(result.query().dimensions())
        .containsExactly(Dimension.ORDER_NUMBER, Dimension.PRODUCT_NAME);
    assertThat(result.query().timeField()).isEqualTo(TimeField.CREATED_AT);
    assertThat(result.query().limit()).isEqualTo(20);
    assertThat(result.failures()).isEmpty();
  }

  @Test
  @DisplayName("모델이 빠뜨린 개별 지표의 공개 식별자 차원을 서버가 보완한다")
  void create_resourceMetricWithoutIdentifier_addsRequiredDimension() {
    QuerySpec request =
        new QuerySpec(
            Dataset.ORDER,
            List.of("ORDER_UNIT_PRICE"),
            List.of("PRODUCT_NAME"),
            "PAID_AT",
            TimeRangePreset.THIS_MONTH,
            "",
            "",
            TrendGrain.NONE,
            Comparison.NONE,
            List.of(),
            "ORDER_UNIT_PRICE",
            SortDirection.DESC,
            20);

    AdminAnalyticsPlanFactory.PreparedQuery result = factory.create(request);

    assertThat(result.query().dimensions())
        .containsExactly(Dimension.ORDER_NUMBER, Dimension.PRODUCT_NAME);
    assertThat(result.failures()).isEmpty();
  }

  @Test
  @DisplayName("회원 조회에는 카탈로그에 없는 개인 식별 필터를 만들 수 없다")
  void create_memberQuery_dropsPersonalIdentifierFilter() {
    QuerySpec request =
        new QuerySpec(
            Dataset.MEMBER_CURRENT,
            List.of("MEMBER_COUNT"),
            List.of("ROLE"),
            "NONE",
            TimeRangePreset.CURRENT_SNAPSHOT,
            "",
            "",
            TrendGrain.NONE,
            Comparison.NONE,
            List.of(new FilterSpec("EMAIL", List.of("user@example.com"))),
            "MEMBER_COUNT",
            SortDirection.DESC,
            10);

    AdminAnalyticsPlanFactory.PreparedQuery result = factory.create(request);

    assertThat(result.query().filters()).isEmpty();
    assertThat(result.failures())
        .extracting(AdminAnalyticsPlanFactory.FieldFailure::field)
        .contains("filters");
  }

  @Test
  @DisplayName("회원 보호 집계는 유효한 역할 필터와 두 번째 분류 기준도 제거한다")
  void create_memberQuery_preventsSubsetDifferencing() {
    QuerySpec request =
        new QuerySpec(
            Dataset.MEMBER_CURRENT,
            List.of("MEMBER_COUNT"),
            List.of("ROLE", "PLATFORM"),
            "NONE",
            TimeRangePreset.CURRENT_SNAPSHOT,
            "",
            "",
            TrendGrain.NONE,
            Comparison.NONE,
            List.of(new FilterSpec("ROLE", List.of("USER", "SELLER"))),
            "MEMBER_COUNT",
            SortDirection.DESC,
            10);

    AdminAnalyticsPlanFactory.PreparedQuery result = factory.create(request);

    assertThat(result.query().filters()).isEmpty();
    assertThat(result.query().dimensions()).containsExactly(Dimension.ROLE);
    assertThat(result.failures())
        .extracting(AdminAnalyticsPlanFactory.FieldFailure::field)
        .contains("filters", "dimensions");
  }

  @Test
  @DisplayName("기간형 보호 집계는 임의·이동 기간을 허용하지 않는다")
  void create_registrationQuery_rejectsRollingPeriod() {
    QuerySpec request =
        new QuerySpec(
            Dataset.MEMBER_REGISTRATION,
            List.of("NEW_MEMBER_COUNT"),
            List.of(),
            "PERIOD_START",
            TimeRangePreset.RECENT_30_DAYS,
            "",
            "",
            TrendGrain.DAY,
            Comparison.NONE,
            List.of(),
            "NEW_MEMBER_COUNT",
            SortDirection.DESC,
            10);

    assertThatThrownBy(() -> factory.create(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("고정된 달력 기간");
  }

  @Test
  @DisplayName("실행 가능한 지표가 하나도 없으면 해당 binding만 실패시킨다")
  void create_withoutValidMetric_failsBinding() {
    QuerySpec request =
        new QuerySpec(
            Dataset.ORDER,
            List.of("MEMBER_COUNT"),
            List.of(),
            "CREATED_AT",
            TimeRangePreset.TODAY,
            "",
            "",
            TrendGrain.NONE,
            Comparison.NONE,
            List.of(),
            "MEMBER_COUNT",
            SortDirection.DESC,
            10);

    assertThatThrownBy(() -> factory.create(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("지표");
  }

  @Test
  @DisplayName("미처리 이벤트 현황은 기간 없이 현재 스냅샷 계획으로 만든다")
  void create_pendingEvents_usesCurrentSnapshot() {
    QuerySpec request =
        new QuerySpec(
            Dataset.EVENT_PIPELINE,
            List.of("PENDING_EVENT_COUNT"),
            List.of(),
            "NONE",
            TimeRangePreset.CURRENT_SNAPSHOT,
            "",
            "",
            TrendGrain.NONE,
            Comparison.NONE,
            List.of(),
            "PENDING_EVENT_COUNT",
            SortDirection.DESC,
            10);

    AdminAnalyticsPlanFactory.PreparedQuery result = factory.create(request);

    assertThat(result.query().timeField()).isEqualTo(TimeField.NONE);
    assertThat(result.query().period()).isNull();
    assertThat(result.failures()).isEmpty();
  }
}

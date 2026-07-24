package com.openat.chat.domain.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 분석 쿼리 계획")
class AdminAnalyticsQueryPlanTest {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  @Test
  @DisplayName("허용된 지표와 분류를 조합한 기간 비교 계획을 만든다")
  void create_supportedCombination_succeeds() {
    Query query =
        new Query(
            Dataset.ORDER,
            List.of(Measure.COMPLETED_QUANTITY, Measure.GROSS_COMPLETED_AMOUNT),
            List.of(Dimension.PRODUCT_NAME),
            TimeField.COMPLETED_AT,
            AggregateTimeScope.CREATED_PERIOD,
            range(0, 7),
            TrendGrain.NONE,
            Comparison.PREVIOUS_PERIOD,
            Map.of(FilterField.STATUS, List.of("COMPLETED")),
            Measure.COMPLETED_QUANTITY,
            SortDirection.DESC,
            5);

    assertThat(query.limit()).isEqualTo(5);
    assertThat(query.filters()).containsEntry(FilterField.STATUS, List.of("COMPLETED"));
  }

  @Test
  @DisplayName("공개 주문번호를 기준으로 주문 상품의 수량과 가격을 조회할 수 있다")
  void create_orderRowsWithPublicFields_succeeds() {
    Query query =
        new Query(
            Dataset.ORDER,
            List.of(Measure.ORDER_QUANTITY, Measure.ORDER_UNIT_PRICE, Measure.ORDER_TOTAL_PRICE),
            List.of(Dimension.ORDER_NUMBER, Dimension.PRODUCT_NAME),
            TimeField.CREATED_AT,
            AggregateTimeScope.CREATED_PERIOD,
            range(0, 31),
            TrendGrain.NONE,
            Comparison.NONE,
            Map.of(),
            Measure.ORDER_TOTAL_PRICE,
            SortDirection.DESC,
            20);

    assertThat(query.measures())
        .containsExactly(
            Measure.ORDER_QUANTITY, Measure.ORDER_UNIT_PRICE, Measure.ORDER_TOTAL_PRICE);
    assertThat(query.dimensions()).containsExactly(Dimension.ORDER_NUMBER, Dimension.PRODUCT_NAME);
  }

  @Test
  @DisplayName("개별 주문 가격은 공개 주문번호 차원 없이 조회할 수 없다")
  void create_orderRowsWithoutPublicOrderNumber_rejects() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new Query(
                    Dataset.ORDER,
                    List.of(Measure.ORDER_UNIT_PRICE),
                    List.of(Dimension.PRODUCT_NAME),
                    TimeField.CREATED_AT,
                    AggregateTimeScope.CREATED_PERIOD,
                    range(0, 31),
                    TrendGrain.NONE,
                    Comparison.NONE,
                    Map.of(),
                    Measure.ORDER_UNIT_PRICE,
                    SortDirection.DESC,
                    20));
  }

  @Test
  @DisplayName("개별 상품과 드롭 가격은 각각의 공개 식별자 없이 조회할 수 없다")
  void create_resourcePriceWithoutPublicIdentifier_rejects() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new Query(
                    Dataset.PRODUCT,
                    List.of(Measure.PRODUCT_PRICE),
                    List.of(Dimension.PRODUCT_NAME),
                    TimeField.CREATED_AT,
                    AggregateTimeScope.CREATED_PERIOD,
                    range(0, 31),
                    TrendGrain.NONE,
                    Comparison.NONE,
                    Map.of(),
                    Measure.PRODUCT_PRICE,
                    SortDirection.DESC,
                    20));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new Query(
                    Dataset.DROP,
                    List.of(Measure.DROP_PRICE),
                    List.of(Dimension.PRODUCT_NAME),
                    TimeField.CREATED_AT,
                    AggregateTimeScope.CREATED_PERIOD,
                    range(0, 31),
                    TrendGrain.NONE,
                    Comparison.NONE,
                    Map.of(),
                    Measure.DROP_PRICE,
                    SortDirection.DESC,
                    20));
  }

  @Test
  @DisplayName("선택한 영역이 지원하지 않는 지표나 필터는 계획 단계에서 거부한다")
  void create_crossDomainField_rejects() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new Query(
                    Dataset.MEMBER_CURRENT,
                    List.of(Measure.APPROVED_AMOUNT),
                    List.of(Dimension.ROLE),
                    TimeField.NONE,
                    AggregateTimeScope.CURRENT_SNAPSHOT,
                    null,
                    TrendGrain.NONE,
                    Comparison.NONE,
                    Map.of(),
                    Measure.APPROVED_AMOUNT,
                    SortDirection.DESC,
                    10));
  }

  @Test
  @DisplayName("현재 스냅샷에는 기간 비교와 시간 추이를 적용하지 않는다")
  void create_snapshotWithPeriodControls_rejects() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new Query(
                    Dataset.PRODUCT,
                    List.of(Measure.PRODUCT_COUNT),
                    List.of(),
                    TimeField.CREATED_AT,
                    AggregateTimeScope.CURRENT_SNAPSHOT,
                    range(0, 1),
                    TrendGrain.DAY,
                    Comparison.PREVIOUS_PERIOD,
                    Map.of(),
                    Measure.PRODUCT_COUNT,
                    SortDirection.DESC,
                    10));
  }

  @Test
  @DisplayName("모델이 결과 제한을 범위 밖으로 채워도 서버 경계에서 보정한다")
  void clampLimit_outOfRange_normalizes() {
    assertThat(AdminAnalyticsQueryPlan.clampLimit(0)).isEqualTo(10);
    assertThat(AdminAnalyticsQueryPlan.clampLimit(100)).isEqualTo(20);
    assertThat(AdminAnalyticsQueryPlan.clampLimit(7)).isEqualTo(7);
  }

  private PlanningDateRange range(int startDayOffset, int endDayOffset) {
    ZonedDateTime base = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, KST);
    return new PlanningDateRange(base.plusDays(startDayOffset), base.plusDays(endDayOffset));
  }
}

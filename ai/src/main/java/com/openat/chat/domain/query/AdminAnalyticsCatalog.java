package com.openat.chat.domain.query;

import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dataset;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dimension;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.FilterField;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Measure;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.TimeField;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class AdminAnalyticsCatalog {

  private AdminAnalyticsCatalog() {}

  public static void validate(
      Dataset dataset,
      List<Measure> measures,
      List<Dimension> dimensions,
      TimeField timeField,
      Set<FilterField> filters) {
    if (!measureCatalog(dataset).containsAll(measures)) {
      throw new IllegalArgumentException("선택한 데이터 영역에서 지원하지 않는 지표가 있어요.");
    }
    if (!dimensionCatalog(dataset).containsAll(dimensions)) {
      throw new IllegalArgumentException("선택한 데이터 영역에서 지원하지 않는 분류 기준이 있어요.");
    }
    if (!timeFieldCatalog(dataset).contains(timeField)) {
      throw new IllegalArgumentException("선택한 데이터 영역에서 지원하지 않는 시간 기준이에요.");
    }
    if (!filterCatalog(dataset).containsAll(filters)) {
      throw new IllegalArgumentException("선택한 데이터 영역에서 지원하지 않는 필터가 있어요.");
    }
    if (!dimensions.containsAll(requiredDimensions(dataset, measures))) {
      throw new IllegalArgumentException("개별 운영 지표에 필요한 공개 식별자 분류가 빠졌어요.");
    }
  }

  public static MetricDefinition definition(Measure measure) {
    return switch (measure) {
      case ORDER_COUNT -> metric("건", "주문 생성 건수");
      case ORDER_QUANTITY -> metric("개", "공개 주문번호 한 건의 주문 수량");
      case ORDER_UNIT_PRICE -> metric("원", "공개 주문번호 한 건의 주문 당시 상품 단가");
      case ORDER_TOTAL_PRICE -> metric("원", "공개 주문번호 한 건의 주문 총액");
      case COMPLETED_QUANTITY -> metric("개", "완료 이력이 있는 주문의 총 수량이며 부분환불 수량은 반영하지 않음");
      case GROSS_COMPLETED_AMOUNT -> metric("원", "완료 이력이 있는 주문의 환불 차감 전 주문금액");
      case AVERAGE_PAID_ORDER_AMOUNT -> metric("원", "결제된 주문의 평균 주문금액");
      case AVERAGE_ORDER_COMPLETION_SECONDS -> metric("초", "완료된 주문의 생성부터 완료까지 평균 소요시간");
      case P50_ORDER_COMPLETION_SECONDS -> metric("초", "완료된 주문의 생성부터 완료까지 소요시간 중앙값");
      case P95_ORDER_COMPLETION_SECONDS -> metric("초", "완료된 주문의 생성부터 완료까지 소요시간 95백분위");
      case PAYMENT_PENDING_ORDER_COUNT -> metric("건", "현재 상태가 PAYMENT_PENDING인 주문 수");
      case OLDEST_PAYMENT_PENDING_AGE_SECONDS ->
          metric("초", "현재 PAYMENT_PENDING 주문 중 가장 오래된 주문의 생성 후 경과시간");
      case CANCEL_RATE -> metric("%", "전체 주문 중 현재 취소 상태 주문 비율");
      case FAILURE_RATE -> metric("%", "전체 주문 중 현재 실패 상태 주문 비율");
      case REFUND_RATE -> metric("%", "완료 이력이 있는 주문 중 현재 환불 상태 주문 비율");
      case PAYMENT_ATTEMPT_COUNT -> metric("건", "결제 시도 건수");
      case APPROVED_PAYMENT_COUNT -> metric("건", "승인 또는 환불 단계까지 진행된 결제 건수");
      case APPROVED_AMOUNT -> metric("원", "승인 또는 환불 단계까지 진행된 결제 원금");
      case FAILED_PAYMENT_COUNT -> metric("건", "실패한 결제 건수");
      case PAYMENT_COMPLETION_RATE -> metric("%", "결제 시도 중 승인 또는 환불 단계까지 진행된 비율");
      case PAYMENT_REFUNDED_AMOUNT -> metric("원", "결제에 누적 반영된 환불 금액");
      case NET_PAYMENT_AMOUNT -> metric("원", "승인된 결제 원금에서 누적 환불액을 뺀 금액");
      case REFUND_REQUEST_COUNT -> metric("건", "환불 요청 건수");
      case REFUND_COMPLETED_COUNT -> metric("건", "완료된 환불 건수");
      case REFUND_AMOUNT -> metric("원", "환불 요청 금액");
      case REFUND_COMPLETION_RATE -> metric("%", "환불 요청 중 완료된 비율");
      case SETTLEMENT_ORDER_COUNT -> metric("건", "정산 대상 주문 건수");
      case SETTLEMENT_PAID_AMOUNT -> metric("원", "정산에 반영된 결제 금액");
      case SETTLEMENT_FEE_AMOUNT -> metric("원", "정산 수수료 금액이며 플랫폼 이익과 동일하지 않음");
      case SETTLEMENT_REFUND_AMOUNT -> metric("원", "정산에 반영된 환불 금액");
      case NET_SETTLEMENT_AMOUNT -> metric("원", "결제액에서 수수료와 환불액을 차감한 정산 주문 금액");
      case SETTLEMENT_FEE_RATE -> metric("%", "정산 반영 결제액 대비 수수료 금액 비율");
      case SETTLEMENT_SELLER_COUNT -> metric("곳", "정산 결과에 포함된 비식별 판매자 수");
      case FINAL_SETTLEMENT_AMOUNT -> metric("원", "결제액-수수료-환불액+조정액으로 계산된 최종 지급 예정액");
      case SETTLEMENT_ADJUSTMENT_AMOUNT -> metric("원", "정산 후 반영된 조정 금액");
      case SETTLEMENT_ADJUSTMENT_COUNT -> metric("건", "정산 후 반영된 조정 건수");
      case SETTLEMENT_BATCH_COUNT -> metric("회", "정산 배치 실행 건수");
      case SETTLEMENT_BATCH_ORDER_COUNT -> metric("건", "정산 배치가 처리한 주문 수");
      case SETTLEMENT_BATCH_SELLER_COUNT -> metric("곳", "정산 배치가 처리한 비식별 판매자 수");
      case SETTLEMENT_BATCH_AMOUNT -> metric("원", "정산 배치의 총 정산 금액");
      case AVERAGE_BATCH_DURATION_SECONDS -> metric("초", "종료된 정산 배치의 평균 실행 시간");
      case RECONCILIATION_RUN_COUNT -> metric("회", "일별 대사 실행 횟수");
      case RECONCILIATION_PAYMENT_COUNT -> metric("건", "대사에 포함된 결제 건수");
      case RECONCILIATION_PAYMENT_AMOUNT -> metric("원", "대사에 포함된 결제 금액");
      case RECONCILIATION_REFUND_COUNT -> metric("건", "대사에 포함된 환불 건수");
      case RECONCILIATION_REFUND_AMOUNT -> metric("원", "대사에 포함된 환불 금액");
      case EXPECTED_SETTLEMENT_AMOUNT -> metric("원", "대사 결과로 계산된 예상 정산 금액");
      case DISCREPANCY_COUNT -> metric("건", "대사 불일치 건수");
      case MEMBER_COUNT -> metric("명", "탈퇴하지 않은 현재 회원 수");
      case NEW_MEMBER_COUNT -> metric("명", "기간 중 신규 가입 회원 수");
      case WITHDRAWN_MEMBER_COUNT -> metric("명", "기간 중 탈퇴한 회원 수");
      case PRODUCT_COUNT -> metric("개", "상품 수");
      case PRODUCT_PRICE -> metric("원", "공개 상품 한 건의 현재 정상가이며 null은 가격 미설정");
      case AVERAGE_PRODUCT_PRICE -> metric("원", "가격이 설정된 상품의 평균 가격");
      case WISHLIST_COUNT -> metric("건", "현재 유효한 찜 수");
      case INCOMPLETE_PRODUCT_COUNT -> metric("개", "가격·설명·이미지 중 하나 이상이 빠진 상품 수");
      case DROP_COUNT -> metric("개", "삭제되지 않은 드롭 수");
      case DROP_PRICE -> metric("원", "공개 드롭 한 건의 판매가");
      case INITIAL_STOCK -> metric("개", "드롭의 초기 재고 수량");
      case REMAINING_STOCK -> metric("개", "초기 재고에 차감·롤백 원장을 합산한 현재 잔여 수량");
      case NET_RESERVED_STOCK -> metric("개", "초기 재고에서 현재 잔여 수량을 뺀 순예약 차감 수량이며 결제 완료 판매량과 다름");
      case DEDUCTED_STOCK -> metric("개", "주문 예약으로 차감된 누적 수량이며 완료 판매량과 다름");
      case ROLLED_BACK_STOCK -> metric("개", "취소·실패 등으로 원복된 누적 수량");
      case STOCK_RESERVATION_RATE -> metric("%", "초기 재고 대비 순예약 차감 수량 비율이며 결제 완료 판매율과 다름");
      case ROLLBACK_RATE -> metric("%", "누적 예약 차감 수량 대비 누적 롤백 수량 비율");
      case UNCONFIGURED_DROP_COUNT -> metric("개", "마감 시각 또는 회원별 구매 제한이 설정되지 않은 드롭 수");
      case EVENT_COUNT -> metric("건", "이벤트 파이프라인 기록 건수");
      case PENDING_EVENT_COUNT -> metric("건", "아직 처리 또는 발행되지 않은 이벤트 건수");
      case FAILED_EVENT_COUNT -> metric("건", "처리에 실패한 이벤트 건수");
      case OLDEST_PENDING_AGE_SECONDS -> metric("초", "가장 오래된 미처리 이벤트의 경과 시간");
      case SAGA_COUNT -> metric("건", "현재 주문 사가 수");
      case STALLED_SAGA_COUNT -> metric("건", "보상 시작 후 10분 이상 지난 주문 사가 수");
    };
  }

  public static boolean supports(Dataset dataset, Measure measure) {
    return measure != null && measureCatalog(dataset).contains(measure);
  }

  public static boolean supportsMeasure(Dataset dataset, Measure measure) {
    return supports(dataset, measure);
  }

  public static boolean supports(Dataset dataset, Dimension dimension) {
    return dimension != null && dimensionCatalog(dataset).contains(dimension);
  }

  public static boolean supportsDimension(Dataset dataset, Dimension dimension) {
    return supports(dataset, dimension);
  }

  public static boolean supports(Dataset dataset, TimeField timeField) {
    return timeField != null && timeFieldCatalog(dataset).contains(timeField);
  }

  public static boolean supportsTimeField(Dataset dataset, TimeField timeField) {
    return supports(dataset, timeField);
  }

  public static boolean supports(Dataset dataset, FilterField filterField) {
    return filterField != null && filterCatalog(dataset).contains(filterField);
  }

  public static boolean supportsFilter(Dataset dataset, FilterField filterField) {
    return supports(dataset, filterField);
  }

  public static boolean requiresMinimumGroupSize(Dataset dataset) {
    return dataset == Dataset.MEMBER_CURRENT
        || dataset == Dataset.MEMBER_REGISTRATION
        || dataset == Dataset.SELLER_SETTLEMENT;
  }

  public static List<Dimension> requiredDimensions(Dataset dataset, Collection<Measure> measures) {
    EnumSet<Dimension> required = EnumSet.noneOf(Dimension.class);
    if (dataset == Dataset.ORDER
        && containsAny(
            measures,
            Measure.ORDER_QUANTITY,
            Measure.ORDER_UNIT_PRICE,
            Measure.ORDER_TOTAL_PRICE)) {
      required.add(Dimension.ORDER_NUMBER);
    }
    if (dataset == Dataset.PRODUCT && measures.contains(Measure.PRODUCT_PRICE)) {
      required.add(Dimension.PRODUCT_ID);
    }
    if (dataset == Dataset.DROP && measures.contains(Measure.DROP_PRICE)) {
      required.add(Dimension.DROP_ID);
    }
    return List.copyOf(required);
  }

  public static String promptCatalog() {
    return promptCatalog(EnumSet.allOf(Dataset.class));
  }

  public static String promptCatalog(Collection<Dataset> datasets) {
    return datasets.stream()
        .map(
            dataset ->
                """
                dataset=%s
                metricValues=%s
                metricMeanings=%s
                dimensions=%s
                metricRequirements=%s
                timeFields=%s
                timeFieldMeanings=%s
                filters=%s
                """
                    .formatted(
                        dataset.name(),
                        names(measureCatalog(dataset)),
                        metricMeanings(measureCatalog(dataset)),
                        names(dimensionCatalog(dataset)),
                        metricRequirements(dataset),
                        names(timeFieldCatalog(dataset)),
                        timeFieldMeanings(dataset),
                        names(filterCatalog(dataset))))
        .collect(Collectors.joining("\n"));
  }

  private static String metricMeanings(Set<Measure> values) {
    return values.stream()
        .map(
            measure -> {
              MetricDefinition definition = definition(measure);
              return "%s[%s: %s]"
                  .formatted(measure.name(), definition.unit(), definition.description());
            })
        .collect(Collectors.joining(";"));
  }

  private static String names(Set<? extends Enum<?>> values) {
    return values.stream().map(Enum::name).collect(Collectors.joining(","));
  }

  private static Set<Measure> measureCatalog(Dataset dataset) {
    return switch (dataset) {
      case ORDER ->
          EnumSet.of(
              Measure.ORDER_COUNT,
              Measure.ORDER_QUANTITY,
              Measure.ORDER_UNIT_PRICE,
              Measure.ORDER_TOTAL_PRICE,
              Measure.COMPLETED_QUANTITY,
              Measure.GROSS_COMPLETED_AMOUNT,
              Measure.AVERAGE_PAID_ORDER_AMOUNT,
              Measure.AVERAGE_ORDER_COMPLETION_SECONDS,
              Measure.P50_ORDER_COMPLETION_SECONDS,
              Measure.P95_ORDER_COMPLETION_SECONDS,
              Measure.PAYMENT_PENDING_ORDER_COUNT,
              Measure.OLDEST_PAYMENT_PENDING_AGE_SECONDS,
              Measure.CANCEL_RATE,
              Measure.FAILURE_RATE,
              Measure.REFUND_RATE);
      case PAYMENT ->
          EnumSet.of(
              Measure.PAYMENT_ATTEMPT_COUNT,
              Measure.APPROVED_PAYMENT_COUNT,
              Measure.APPROVED_AMOUNT,
              Measure.FAILED_PAYMENT_COUNT,
              Measure.PAYMENT_COMPLETION_RATE,
              Measure.PAYMENT_REFUNDED_AMOUNT,
              Measure.NET_PAYMENT_AMOUNT);
      case REFUND ->
          EnumSet.of(
              Measure.REFUND_REQUEST_COUNT,
              Measure.REFUND_COMPLETED_COUNT,
              Measure.REFUND_AMOUNT,
              Measure.REFUND_COMPLETION_RATE);
      case SETTLEMENT_ORDER ->
          EnumSet.of(
              Measure.SETTLEMENT_ORDER_COUNT,
              Measure.SETTLEMENT_PAID_AMOUNT,
              Measure.SETTLEMENT_FEE_AMOUNT,
              Measure.SETTLEMENT_REFUND_AMOUNT,
              Measure.NET_SETTLEMENT_AMOUNT,
              Measure.SETTLEMENT_FEE_RATE);
      case SELLER_SETTLEMENT ->
          EnumSet.of(
              Measure.SETTLEMENT_SELLER_COUNT,
              Measure.SETTLEMENT_ORDER_COUNT,
              Measure.SETTLEMENT_PAID_AMOUNT,
              Measure.SETTLEMENT_FEE_AMOUNT,
              Measure.SETTLEMENT_REFUND_AMOUNT,
              Measure.SETTLEMENT_ADJUSTMENT_AMOUNT,
              Measure.FINAL_SETTLEMENT_AMOUNT,
              Measure.SETTLEMENT_FEE_RATE);
      case SETTLEMENT_BATCH ->
          EnumSet.of(
              Measure.SETTLEMENT_BATCH_COUNT,
              Measure.SETTLEMENT_BATCH_ORDER_COUNT,
              Measure.SETTLEMENT_BATCH_SELLER_COUNT,
              Measure.SETTLEMENT_BATCH_AMOUNT,
              Measure.AVERAGE_BATCH_DURATION_SECONDS);
      case SETTLEMENT_ADJUSTMENT ->
          EnumSet.of(Measure.SETTLEMENT_ADJUSTMENT_AMOUNT, Measure.SETTLEMENT_ADJUSTMENT_COUNT);
      case RECONCILIATION ->
          EnumSet.of(
              Measure.RECONCILIATION_RUN_COUNT,
              Measure.RECONCILIATION_PAYMENT_COUNT,
              Measure.RECONCILIATION_PAYMENT_AMOUNT,
              Measure.RECONCILIATION_REFUND_COUNT,
              Measure.RECONCILIATION_REFUND_AMOUNT,
              Measure.EXPECTED_SETTLEMENT_AMOUNT,
              Measure.DISCREPANCY_COUNT);
      case RECONCILIATION_DISCREPANCY -> EnumSet.of(Measure.DISCREPANCY_COUNT);
      case MEMBER_CURRENT -> EnumSet.of(Measure.MEMBER_COUNT);
      case MEMBER_REGISTRATION ->
          EnumSet.of(Measure.NEW_MEMBER_COUNT, Measure.WITHDRAWN_MEMBER_COUNT);
      case PRODUCT ->
          EnumSet.of(
              Measure.PRODUCT_COUNT,
              Measure.PRODUCT_PRICE,
              Measure.AVERAGE_PRODUCT_PRICE,
              Measure.WISHLIST_COUNT,
              Measure.INCOMPLETE_PRODUCT_COUNT);
      case DROP ->
          EnumSet.of(
              Measure.DROP_COUNT,
              Measure.DROP_PRICE,
              Measure.INITIAL_STOCK,
              Measure.REMAINING_STOCK,
              Measure.NET_RESERVED_STOCK,
              Measure.DEDUCTED_STOCK,
              Measure.ROLLED_BACK_STOCK,
              Measure.STOCK_RESERVATION_RATE,
              Measure.ROLLBACK_RATE,
              Measure.UNCONFIGURED_DROP_COUNT);
      case EVENT_PIPELINE ->
          EnumSet.of(
              Measure.EVENT_COUNT,
              Measure.PENDING_EVENT_COUNT,
              Measure.FAILED_EVENT_COUNT,
              Measure.OLDEST_PENDING_AGE_SECONDS);
      case ORDER_SAGA -> EnumSet.of(Measure.SAGA_COUNT, Measure.STALLED_SAGA_COUNT);
    };
  }

  private static Set<Dimension> dimensionCatalog(Dataset dataset) {
    return switch (dataset) {
      case ORDER ->
          EnumSet.of(
              Dimension.ORDER_NUMBER,
              Dimension.STATUS,
              Dimension.PRODUCT_NAME,
              Dimension.CATEGORY_NAME,
              Dimension.FAILURE_CODE,
              Dimension.HOUR_OF_DAY,
              Dimension.DAY_OF_WEEK);
      case PAYMENT ->
          EnumSet.of(
              Dimension.STATUS,
              Dimension.PAYMENT_METHOD,
              Dimension.PG_PROVIDER,
              Dimension.RECONCILIATION_STATUS,
              Dimension.HOUR_OF_DAY,
              Dimension.DAY_OF_WEEK);
      case REFUND ->
          EnumSet.of(
              Dimension.STATUS,
              Dimension.RECONCILIATION_STATUS,
              Dimension.HOUR_OF_DAY,
              Dimension.DAY_OF_WEEK);
      case SETTLEMENT_ORDER ->
          EnumSet.of(Dimension.STATUS, Dimension.PRODUCT_NAME, Dimension.CATEGORY_NAME);
      case SELLER_SETTLEMENT -> EnumSet.of(Dimension.STATUS);
      case SETTLEMENT_BATCH -> EnumSet.of(Dimension.STATUS, Dimension.BATCH_TYPE);
      case SETTLEMENT_ADJUSTMENT -> EnumSet.of(Dimension.STATUS, Dimension.ADJUSTMENT_TYPE);
      case RECONCILIATION -> EnumSet.of(Dimension.STATUS);
      case RECONCILIATION_DISCREPANCY ->
          EnumSet.of(Dimension.ENTITY_TYPE, Dimension.DISCREPANCY_TYPE);
      case MEMBER_CURRENT -> EnumSet.of(Dimension.PLATFORM, Dimension.ROLE);
      case MEMBER_REGISTRATION -> EnumSet.of(Dimension.PLATFORM);
      case PRODUCT ->
          EnumSet.of(
              Dimension.PRODUCT_ID,
              Dimension.PRODUCT_NAME,
              Dimension.CATEGORY_NAME,
              Dimension.LIFECYCLE,
              Dimension.CONTENT_COMPLETENESS);
      case DROP ->
          EnumSet.of(
              Dimension.DROP_ID,
              Dimension.STATUS,
              Dimension.PRODUCT_NAME,
              Dimension.CATEGORY_NAME,
              Dimension.INVENTORY_STATE);
      case EVENT_PIPELINE ->
          EnumSet.of(
              Dimension.STATUS,
              Dimension.EVENT_SERVICE,
              Dimension.EVENT_DIRECTION,
              Dimension.EVENT_TYPE);
      case ORDER_SAGA -> EnumSet.of(Dimension.SAGA_STEP);
    };
  }

  private static Set<TimeField> timeFieldCatalog(Dataset dataset) {
    return switch (dataset) {
      case ORDER ->
          EnumSet.of(
              TimeField.NONE,
              TimeField.CREATED_AT,
              TimeField.PAID_AT,
              TimeField.COMPLETED_AT,
              TimeField.CANCELLED_AT,
              TimeField.REFUNDED_AT);
      case PAYMENT -> EnumSet.of(TimeField.NONE, TimeField.CREATED_AT, TimeField.APPROVED_AT);
      case REFUND -> EnumSet.of(TimeField.NONE, TimeField.CREATED_AT, TimeField.COMPLETED_AT);
      case SETTLEMENT_ORDER,
              SELLER_SETTLEMENT,
              SETTLEMENT_ADJUSTMENT,
              RECONCILIATION,
              RECONCILIATION_DISCREPANCY,
              MEMBER_REGISTRATION ->
          EnumSet.of(TimeField.PERIOD_START);
      case SETTLEMENT_BATCH ->
          EnumSet.of(TimeField.PERIOD_START, TimeField.CREATED_AT, TimeField.COMPLETED_AT);
      case MEMBER_CURRENT -> EnumSet.of(TimeField.NONE);
      case PRODUCT -> EnumSet.of(TimeField.NONE, TimeField.CREATED_AT);
      case DROP ->
          EnumSet.of(TimeField.NONE, TimeField.CREATED_AT, TimeField.OPEN_AT, TimeField.CLOSE_AT);
      case EVENT_PIPELINE -> EnumSet.of(TimeField.NONE, TimeField.EVENT_AT);
      case ORDER_SAGA -> EnumSet.of(TimeField.NONE, TimeField.SAGA_UPDATED_AT);
    };
  }

  private static Set<FilterField> filterCatalog(Dataset dataset) {
    return switch (dataset) {
      case ORDER ->
          EnumSet.of(
              FilterField.ORDER_NUMBER,
              FilterField.STATUS,
              FilterField.PRODUCT_NAME,
              FilterField.CATEGORY_NAME,
              FilterField.FAILURE_CODE);
      case PAYMENT ->
          EnumSet.of(
              FilterField.STATUS,
              FilterField.PAYMENT_METHOD,
              FilterField.PG_PROVIDER,
              FilterField.RECONCILIATION_STATUS);
      case REFUND -> EnumSet.of(FilterField.STATUS, FilterField.RECONCILIATION_STATUS);
      case SETTLEMENT_ORDER ->
          EnumSet.of(FilterField.STATUS, FilterField.PRODUCT_NAME, FilterField.CATEGORY_NAME);
      case SELLER_SETTLEMENT -> EnumSet.noneOf(FilterField.class);
      case SETTLEMENT_BATCH -> EnumSet.of(FilterField.STATUS, FilterField.BATCH_TYPE);
      case SETTLEMENT_ADJUSTMENT -> EnumSet.of(FilterField.STATUS, FilterField.ADJUSTMENT_TYPE);
      case RECONCILIATION -> EnumSet.of(FilterField.STATUS);
      case RECONCILIATION_DISCREPANCY ->
          EnumSet.of(FilterField.ENTITY_TYPE, FilterField.DISCREPANCY_TYPE);
      case MEMBER_CURRENT, MEMBER_REGISTRATION -> EnumSet.noneOf(FilterField.class);
      case PRODUCT ->
          EnumSet.of(
              FilterField.PRODUCT_ID,
              FilterField.PRODUCT_NAME,
              FilterField.CATEGORY_NAME,
              FilterField.LIFECYCLE);
      case DROP ->
          EnumSet.of(
              FilterField.DROP_ID,
              FilterField.STATUS,
              FilterField.PRODUCT_NAME,
              FilterField.CATEGORY_NAME,
              FilterField.INVENTORY_STATE);
      case EVENT_PIPELINE ->
          EnumSet.of(
              FilterField.STATUS,
              FilterField.EVENT_SERVICE,
              FilterField.EVENT_DIRECTION,
              FilterField.EVENT_TYPE);
      case ORDER_SAGA -> EnumSet.of(FilterField.SAGA_STEP);
    };
  }

  private static String metricRequirements(Dataset dataset) {
    return switch (dataset) {
      case ORDER ->
          "ORDER_QUANTITY->ORDER_NUMBER;"
              + "ORDER_UNIT_PRICE->ORDER_NUMBER;"
              + "ORDER_TOTAL_PRICE->ORDER_NUMBER";
      case PRODUCT -> "PRODUCT_PRICE->PRODUCT_ID";
      case DROP -> "DROP_PRICE->DROP_ID";
      default -> "NONE";
    };
  }

  private static String timeFieldMeanings(Dataset dataset) {
    return timeFieldCatalog(dataset).stream()
        .map(field -> field.name() + "[" + timeFieldMeaning(dataset, field) + "]")
        .collect(Collectors.joining(";"));
  }

  private static String timeFieldMeaning(Dataset dataset, TimeField field) {
    return switch (field) {
      case NONE -> "현재 스냅샷";
      case CREATED_AT ->
          switch (dataset) {
            case ORDER -> "주문 생성 시각";
            case PAYMENT -> "결제 시도 생성 시각";
            case REFUND -> "환불 요청 생성 시각";
            case PRODUCT -> "상품 생성 시각";
            case DROP -> "드롭 생성 시각";
            case SETTLEMENT_BATCH -> "정산 배치 생성 시각";
            default -> "레코드 생성 시각";
          };
      case PAID_AT -> "주문 결제 완료 시각";
      case APPROVED_AT -> "결제 승인 시각";
      case COMPLETED_AT -> "업무 완료 시각";
      case CANCELLED_AT -> "주문 취소 시각";
      case REFUNDED_AT -> "주문 환불 시각";
      case PERIOD_START -> "업무 집계 기간 시작";
      case OPEN_AT -> "드롭 오픈 시각";
      case CLOSE_AT -> "드롭 종료 시각";
      case EVENT_AT -> "이벤트 발생 시각";
      case SAGA_UPDATED_AT -> "사가 마지막 갱신 시각";
    };
  }

  private static MetricDefinition metric(String unit, String description) {
    return new MetricDefinition(unit, description);
  }

  private static boolean containsAny(Collection<Measure> measures, Measure... candidates) {
    return java.util.Arrays.stream(candidates).anyMatch(measures::contains);
  }

  public record MetricDefinition(String unit, String description) {}
}

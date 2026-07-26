package com.openat.chat.infrastructure.inference;

import com.openat.chat.domain.query.AdminAnalyticsCatalog;
import com.openat.chat.domain.query.InternalDataDomain;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.stereotype.Component;

@Component
public class InternalDataSchemaRegistry {

  private static final String COMMON_SCHEMA =
      """
      공통 필드:
      - dataset: 아래 선택 영역에 공개된 값 하나
      - metrics: 질문에 필요한 metricValues의 enum 이름만 1~4개
      - dimensions: 분류가 필요할 때만 0~3개
      - timeField: 공개된 시간 기준. CURRENT_SNAPSHOT이면 NONE
      - timeRange: CURRENT_SNAPSHOT, TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK,
        THIS_MONTH, LAST_MONTH, RECENT_24_HOURS, RECENT_7_DAYS, RECENT_30_DAYS, CUSTOM
      - customStart/customEndExclusive: CUSTOM에서만 Asia/Seoul yyyy-MM-dd HH:mm:ss,
        시작 포함·종료 미포함. 그 외 빈 문자열
      - grain: 추이를 묻지 않으면 NONE, 묻는 경우 HOUR, DAY, WEEK, MONTH
      - comparison: 비교가 없으면 NONE, 직전 동일 기간 비교면 PREVIOUS_PERIOD
      - filters: 질문에 명시된 필터만 최대 5개, 값은 필터당 최대 10개
      - orderBy: metrics에 넣은 enum 이름 중 하나
      - sortDirection: ASC 또는 DESC
      - limit: 1~20

      회원·구매자 개인정보와 개별 판매자 정산은 조회하지 않는다. MEMBER_CURRENT,
      MEMBER_REGISTRATION, SELLER_SETTLEMENT는 filters를 항상 빈 배열로 두고 dimensions를
      최대 하나만 사용한다. 기간형 보호 집계는 TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK,
      THIS_MONTH, LAST_MONTH만 사용한다.

      주문·상품·드롭의 공개 식별자와 상품명·카테고리·가격·수량·상태는 허용된 운영
      정보다. metricRequirements는 개별 값 지표를 안전하게 구분하는 필수 공개 식별자
      차원이다. 해당 지표를 고르면 요구된 차원을 반드시 dimensions에 넣는다. 주문별
      상품과 가격은 ORDER_NUMBER를 기준으로 필요한 ORDER_QUANTITY,
      ORDER_UNIT_PRICE, ORDER_TOTAL_PRICE 중 질문에 맞는 지표만 고른다. 상품별
      현재가는 PRODUCT_ID와 PRODUCT_PRICE, 드롭별 판매가는 DROP_ID와 DROP_PRICE를
      사용한다. 결과는 limit 이내에서만 반환한다. 질문에 필요한 조회를 모르거나 필수
      지표를 고를 수 없으면 그 조회만 FAILED로 제출한다. SQL이나 제공되지 않은 필드는
      만들지 않는다.
      """;

  private final TokenCountEstimator tokenEstimator;
  private final ChatInferenceProperties properties;

  public InternalDataSchemaRegistry(
      TokenCountEstimator tokenEstimator, ChatInferenceProperties properties) {
    this.tokenEstimator = tokenEstimator;
    this.properties = properties;
  }

  public List<SchemaShard> shards(Set<InternalDataDomain> requested, String fixedPrompt) {
    List<InternalDataDomain> domains =
        requested.stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
    if (domains.isEmpty()) {
      return List.of();
    }

    int inputLimit = properties.getContext().getInputTokenLimit();
    int fixedTokens = tokenEstimator.estimate(fixedPrompt);
    List<List<InternalDataDomain>> groups = new ArrayList<>();
    List<InternalDataDomain> current = new ArrayList<>();

    for (InternalDataDomain domain : domains) {
      List<InternalDataDomain> candidate = new ArrayList<>(current);
      candidate.add(domain);
      int candidateTokens = fixedTokens + tokenEstimator.estimate(schema(candidate));
      if (!current.isEmpty() && candidateTokens > inputLimit) {
        groups.add(List.copyOf(current));
        current.clear();
      }
      current.add(domain);
      if (fixedTokens + tokenEstimator.estimate(schema(current)) > inputLimit) {
        throw new IllegalStateException("선택된 내부 데이터 스키마가 단일 요청 컨텍스트 예산을 넘었어요: " + domain);
      }
    }
    if (!current.isEmpty()) {
      groups.add(List.copyOf(current));
    }
    if (groups.size() > properties.getContext().getMaxSchemaShards()) {
      throw new IllegalStateException("내부 데이터 스키마 분할 수가 설정 상한을 넘었어요.");
    }

    List<SchemaShard> shards = new ArrayList<>();
    for (int index = 0; index < groups.size(); index++) {
      List<InternalDataDomain> group = groups.get(index);
      shards.add(
          new SchemaShard(index, Set.copyOf(EnumSet.copyOf(group)), schema(group), index == 0));
    }
    return List.copyOf(shards);
  }

  public String schema(Set<InternalDataDomain> domains) {
    return schema(domains.stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList());
  }

  private String schema(List<InternalDataDomain> domains) {
    StringBuilder schema = new StringBuilder(COMMON_SCHEMA);
    for (InternalDataDomain domain : domains) {
      schema
          .append("\n\n[")
          .append(domain.name())
          .append("]\n")
          .append(AdminAnalyticsCatalog.promptCatalog(domain.datasets()));
    }
    return schema.toString();
  }

  public record SchemaShard(
      int index, Set<InternalDataDomain> domains, String schema, boolean primary) {}
}

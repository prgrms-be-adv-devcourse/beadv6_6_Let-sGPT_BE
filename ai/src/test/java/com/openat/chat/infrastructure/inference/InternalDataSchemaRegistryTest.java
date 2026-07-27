package com.openat.chat.infrastructure.inference;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.chat.domain.query.InternalDataDomain;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

@DisplayName("내부 데이터 상세 스키마 분할")
class InternalDataSchemaRegistryTest {

  @Test
  @DisplayName("여섯 영역을 고정 순서로 빠짐없이 8K 입력 예산 안에서 분할한다")
  void shards_allDomains_areStableAndComplete() {
    ChatInferenceProperties properties = new ChatInferenceProperties();
    var estimator = new JTokkitTokenCountEstimator();
    var registry = new InternalDataSchemaRegistry(estimator, properties);
    Set<InternalDataDomain> requested = new HashSet<>(Arrays.asList(InternalDataDomain.values()));
    String fixedPrompt = "고정 프롬프트와 사용자 질문 " + "가".repeat(300);

    List<InternalDataSchemaRegistry.SchemaShard> first = registry.shards(requested, fixedPrompt);
    List<InternalDataSchemaRegistry.SchemaShard> second = registry.shards(requested, fixedPrompt);

    System.out.printf(
        "INTERNAL_SCHEMA_BUDGET|fixedTokens=%d|shards=%d|shardTokens=%s%n",
        estimator.estimate(fixedPrompt),
        first.size(),
        first.stream().map(shard -> estimator.estimate(shard.schema())).toList());

    assertThat(first).hasSizeBetween(1, properties.getContext().getMaxSchemaShards());
    assertThat(first.stream().flatMap(shard -> shard.domains().stream()).toList())
        .containsExactlyInAnyOrder(InternalDataDomain.values());
    assertThat(first.stream().map(InternalDataSchemaRegistry.SchemaShard::schema).toList())
        .isEqualTo(second.stream().map(InternalDataSchemaRegistry.SchemaShard::schema).toList());
    assertThat(first.getFirst().primary()).isTrue();
    assertThat(first)
        .allSatisfy(
            shard ->
                assertThat(shard.schema()).contains("metricValues=").contains("metricMeanings="));
    assertThat(registry.schema(Set.of(InternalDataDomain.ORDER_SALES)))
        .contains(
            "ORDER_NUMBER",
            "PRODUCT_NAME",
            "ORDER_QUANTITY",
            "ORDER_UNIT_PRICE",
            "ORDER_TOTAL_PRICE",
            "metricRequirements=",
            "ORDER_UNIT_PRICE->ORDER_NUMBER",
            "timeFieldMeanings=",
            "CREATED_AT[주문 생성 시각]")
        .contains("회원·구매자 개인정보와 개별 판매자 정산은 조회하지 않는다");
    assertThat(first.stream().skip(1)).allSatisfy(shard -> assertThat(shard.primary()).isFalse());
    assertThat(first)
        .allSatisfy(
            shard ->
                assertThat(estimator.estimate(fixedPrompt) + estimator.estimate(shard.schema()))
                    .isLessThanOrEqualTo(properties.getContext().getInputTokenLimit()));
  }
}

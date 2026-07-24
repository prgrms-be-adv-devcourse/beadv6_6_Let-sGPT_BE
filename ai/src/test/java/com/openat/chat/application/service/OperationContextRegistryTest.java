package com.openat.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.chat.domain.knowledge.OperationContextId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("운영 컨텍스트 레지스트리")
class OperationContextRegistryTest {

  private final OperationContextRegistry registry = new OperationContextRegistry();

  @Test
  @DisplayName("플랫폼 정체성과 짧은 선택 카탈로그를 항상 제공한다")
  void baseContexts_areLoaded() {
    assertThat(registry.catalog()).contains("PLATFORM").contains("RELIABILITY");
    assertThat(registry.select(List.of(OperationContextId.PLATFORM)).content())
        .contains("한정 수량")
        .contains("드롭 커머스");
  }

  @Test
  @DisplayName("선택한 복수 영역만 요청 순서로 중복 없이 조합한다")
  void select_combinesRequestedDocuments() {
    OperationContextRegistry.Selection result =
        registry.select(
            List.of(
                OperationContextId.REPORTING,
                OperationContextId.CATALOG_INVENTORY,
                OperationContextId.REPORTING));

    assertThat(result.included())
        .containsExactly(OperationContextId.REPORTING, OperationContextId.CATALOG_INVENTORY);
    assertThat(result.omitted()).isEmpty();
    assertThat(result.content()).contains("CATALOG_INVENTORY").contains("REPORTING");
    assertThat(result.content().indexOf("REPORTING"))
        .isLessThan(result.content().indexOf("CATALOG_INVENTORY"));
  }

  @Test
  @DisplayName("많은 영역을 선택해도 8K 전체 요청 여유를 위해 문서 입력을 제한하고 부분 결과를 보존한다")
  void select_manyContexts_keepsBoundedPartialSelection() {
    OperationContextRegistry.Selection result =
        registry.select(List.of(OperationContextId.values()));

    assertThat(result.content().length()).isLessThanOrEqualTo(3_000);
    assertThat(result.included()).isNotEmpty();
    assertThat(result.omitted()).isNotEmpty();
  }

  @Test
  @DisplayName("선택 영역이 없으면 전체 문서를 임의로 싣지 않는다")
  void select_empty_rejects() {
    assertThatThrownBy(() -> registry.select(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

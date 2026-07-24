package com.openat.recommendation.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.recommendation.infrastructure.client.ProductDetailClient.ProductDetailResponse;
import com.openat.recommendation.infrastructure.client.SearchRecommendClient.SimilarProductResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationPromptBuilderTest {

  private final RecommendationPromptBuilder builder = new RecommendationPromptBuilder();

  @Test
  void build_forDetail_includesCurrentProductSection() {
    ProductDetailResponse current =
        new ProductDetailResponse(
            UUID.randomUUID(), null, null, "현재 상품", "현재 설명", null, null, 1000L, "thumb",
            List.of(), null);
    var candidate = new SimilarProductResponse(UUID.randomUUID(), "후보", "설명", "이미지 설명");

    String prompt = builder.build(current, List.of(candidate));

    assertThat(prompt).contains("[현재 보는 상품]").contains("현재 상품").contains("현재 설명");
    assertThat(prompt).contains("최대 1개의 주제 그룹으로").contains("한 그룹은 최대 6개");
  }

  @Test
  void build_forHome_omitsCurrentProductSection() {
    var candidate = new SimilarProductResponse(UUID.randomUUID(), "후보", "설명", "이미지 설명");

    String prompt = builder.build(null, List.of(candidate));

    assertThat(prompt).doesNotContain("[현재 보는 상품]");
    assertThat(prompt).contains("주제에 따라 1~3개 그룹으로").contains("각 그룹은 최대 4개");
  }

  @Test
  void build_numbersCandidatesInListOrderAndIncludesTheirDetails() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    var first = new SimilarProductResponse(firstId, "첫 후보", "첫 설명", "첫 이미지 설명");
    var second = new SimilarProductResponse(secondId, "둘째 후보", "둘째 설명", "둘째 이미지 설명");

    String prompt = builder.build(null, List.of(first, second));

    assertThat(prompt)
        .contains("1 | 첫 후보 | 첫 설명 첫 이미지 설명")
        .contains("2 | 둘째 후보 | 둘째 설명 둘째 이미지 설명")
        .doesNotContain(firstId.toString(), secondId.toString());
  }

  @Test
  void build_instructsJsonOnlyOutputFormat() {
    String prompt = builder.build(null, List.of());

    assertThat(prompt)
        .contains("items에는 위 후보 목록에 있는 인덱스 번호만 사용하세요")
        .contains("{\"sections\":[{\"title\":\"...\",\"items\":[1,2,3]}]}")
        .doesNotContain("productIds");
    assertThat(prompt).contains("제목은 한국어 명사형, 30자 이내").contains("이미 구매한 상품은 제외");
    assertThat(prompt).contains("브랜드명 나열보다 용도·테마 중심으로");
  }
}

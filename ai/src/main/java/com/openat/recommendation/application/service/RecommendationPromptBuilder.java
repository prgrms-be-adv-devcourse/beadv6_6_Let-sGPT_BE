package com.openat.recommendation.application.service;

import com.openat.recommendation.infrastructure.client.ProductDetailClient.ProductDetailResponse;
import com.openat.recommendation.infrastructure.client.SearchRecommendClient.SimilarProductResponse;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RecommendationPromptBuilder {

  private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n]+");

  public String build(
      RecommendationMode mode,
      ProductDetailResponse currentProduct,
      List<SimilarProductResponse> candidates) {
    StringBuilder prompt = new StringBuilder();
    if (!mode.isHome()) {
      ProductDetailResponse detailProduct =
          Objects.requireNonNull(currentProduct, "currentProduct is required for DETAIL mode");
      prompt
          .append("[현재 보는 상품]\n")
          .append(detailProduct.name())
          .append(" | 카테고리: ")
          .append(value(detailProduct.categoryName()))
          .append(" | ")
          .append(value(detailProduct.description()))
          .append("\n\n");
    }

    prompt.append("[후보 목록]\n");
    for (int index = 0; index < candidates.size(); index++) {
      SimilarProductResponse candidate = candidates.get(index);
      prompt
          .append(index + 1)
          .append(" | ")
          .append(value(candidate.name()))
          .append(" | ")
          .append(value(candidate.description()));
      if (candidate.imgDescription() != null && !candidate.imgDescription().isBlank()) {
        prompt.append(" ").append(candidate.imgDescription());
      }
      prompt.append('\n');
    }

    prompt
        .append("\n[지시]\n")
        .append("부적합한 후보는 제외하고, 적합한 후보만 ")
        .append(mode.promptGrouping())
        .append(" 묶으세요. 제목은 감성적이고 이커머스다운 문구로, 30자 이내로 작성하세요.\n")
        .append("제목을 '피규어 모음'처럼 상품 종류 이름만 나열한 라벨로 짓지 마세요. ")
        .append("재질·용도·장면·계절처럼 무엇을 왜 함께 골랐는지 드러나는 구체적인 단서를 담되, ")
        .append("상품 종류 이름 하나로 제목을 끝내지 마세요.\n")
        .append("'아이템·선물·제품·상품·굿즈'처럼 무엇이든 가리킬 수 있는 총칭만으로 제목을 채우지 마세요.\n")
        .append("그룹이 2개 이상이면 각 제목은 서로 다른 취향과 상황을 담아야 하며, ")
        .append("같은 카테고리를 억지로 쪼개 비슷한 제목을 반복하지 마세요.\n")
        .append("브랜드명 나열은 피하되 용도·테마·감성은 살리세요. 아래 예시의 톤을 참고하되 ")
        .append("그룹 성격에 맞게 새로 지으세요:\n")
        .append("- 은은한 조명으로 완성하는 저녁\n")
        .append("- 나만의 작은 힐링 홈카페\n")
        .append("- 향으로 남기는 캔들 선물\n")
        .append("(나쁜 예: '피규어 모음', '액세서리 아이템'처럼 상품 종류 이름만 나열한 제목)\n")
        .append("items에는 위 후보 목록에 있는 인덱스 번호만 사용하세요. ")
        .append(mode.promptProductLimit())
        .append("로 구성하세요. 이미 구매한 상품은 제외하세요.\n")
        .append("다른 설명이나 마크다운 없이 다음 형식의 JSON만 반환하세요: ")
        .append("{\"sections\":[{\"title\":\"...\",\"items\":[1,2,3]}]}");
    return prompt.toString();
  }

  private String value(String value) {
    return value == null ? "" : LINE_BREAKS.matcher(value).replaceAll(" ").trim();
  }
}

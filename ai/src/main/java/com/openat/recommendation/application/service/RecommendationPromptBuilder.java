package com.openat.recommendation.application.service;

import com.openat.recommendation.infrastructure.client.ProductDetailClient.ProductDetailResponse;
import com.openat.recommendation.infrastructure.client.SearchRecommendClient.SimilarProductResponse;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RecommendationPromptBuilder {

  private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n]+");

  public String build(
      ProductDetailResponse currentProduct, List<SimilarProductResponse> candidates) {
    StringBuilder prompt = new StringBuilder();
    if (currentProduct != null) {
      prompt
          .append("[현재 보는 상품]\n")
          .append(currentProduct.name())
          .append(" | ")
          .append(value(currentProduct.description()))
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

    String grouping = currentProduct == null ? "주제에 따라 1~3개 그룹으로" : "최대 1개의 주제 그룹으로";
    String productLimit = currentProduct == null ? "각 그룹은 최대 4개" : "한 그룹은 최대 6개";
    prompt
        .append("\n[지시]\n")
        .append("부적합한 후보는 제외하고, 적합한 후보만 ")
        .append(grouping)
        .append(" 묶으세요. 제목은 한국어 명사형, 30자 이내로 작성하세요.\n")
        .append("제목은 브랜드명 나열보다 용도·테마 중심으로 작성하세요.\n")
        .append("items에는 위 후보 목록에 있는 인덱스 번호만 사용하세요. ")
        .append(productLimit)
        .append("로 구성하세요. 이미 구매한 상품은 제외하세요.\n")
        .append("다른 설명이나 마크다운 없이 다음 형식의 JSON만 반환하세요: ")
        .append("{\"sections\":[{\"title\":\"...\",\"items\":[1,2,3]}]}");
    return prompt.toString();
  }

  private String value(String value) {
    return value == null ? "" : LINE_BREAKS.matcher(value).replaceAll(" ").trim();
  }
}

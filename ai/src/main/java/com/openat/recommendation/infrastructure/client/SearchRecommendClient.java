package com.openat.recommendation.infrastructure.client;

import static com.openat.recommendation.infrastructure.client.RestClientResponses.requireBody;

import com.openat.recommendation.domain.model.Seed;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SearchRecommendClient {

  private final RestClient restClient;
  private final int recommendationSize;

  public SearchRecommendClient(
      @Qualifier("searchRestClient") RestClient restClient,
      @Value("${services.search.recommendation-size}") int recommendationSize) {
    this.restClient = restClient;
    this.recommendationSize = recommendationSize;
  }

  public List<SimilarProductResponse> recommend(List<Seed> seeds) {
    return requireBody(
        restClient
            .post()
            .uri("/api/v1/searchs/recommand")
            .body(buildRequest(seeds))
            .retrieve()
            .body(new ParameterizedTypeReference<>() {}),
        "Search recommendation response body is empty");
  }

  private SearchRecommendationRequest buildRequest(List<Seed> seeds) {
    String ids = join(seeds, seed -> seed.productId().toString());
    String scores = join(seeds, seed -> Double.toString(seed.score()));
    String buys = join(seeds, seed -> seed.buy() ? "T" : "F");
    return new SearchRecommendationRequest(ids, scores, buys, recommendationSize);
  }

  private String join(List<Seed> seeds, Function<Seed, String> mapper) {
    return seeds.stream().map(mapper).collect(Collectors.joining("|"));
  }

  private record SearchRecommendationRequest(String id, String score, String buy, Integer size) {}

  public record SimilarProductResponse(
      UUID id, String name, String description, String imgDescription) {}
}

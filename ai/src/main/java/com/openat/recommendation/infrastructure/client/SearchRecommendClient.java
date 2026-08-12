package com.openat.recommendation.infrastructure.client;

import static com.openat.recommendation.infrastructure.client.RestClientResponses.requireBody;

import com.openat.recommendation.domain.model.Seed;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SearchRecommendClient {

  private static final Logger log = LoggerFactory.getLogger(SearchRecommendClient.class);
  private static final int MAX_SEARCH_SIZE = 100;

  private final RestClient restClient;
  private final int recommendationSize;
  private final int maxGroups;
  private final int overfetch;
  private final Executor executor;
  private final SearchConcurrencyLimiter searchConcurrencyLimiter;

  public SearchRecommendClient(
      @Qualifier("searchRestClient") RestClient restClient,
      @Value("${services.search.recommendation-size}") int recommendationSize,
      @Value("${recommendation.search.max-groups:5}") int maxGroups,
      @Value("${recommendation.search.overfetch:3}") int overfetch,
      @Value("${recommendation.search.max-concurrency:20}") int maxConcurrency,
      @Value("${recommendation.search.acquire-timeout:2500ms}") Duration acquireTimeout,
      @Value("${recommendation.search.permit-ttl:5s}") Duration permitTtl,
      StringRedisTemplate redisTemplate,
      @Qualifier("recommendationExecutor") Executor executor) {
    this.restClient = restClient;
    this.recommendationSize = recommendationSize;
    this.maxGroups = Math.max(1, maxGroups);
    this.overfetch = Math.max(1, overfetch);
    this.executor = executor;
    this.searchConcurrencyLimiter =
        new SearchConcurrencyLimiter(redisTemplate, maxConcurrency, permitTtl, acquireTimeout);
  }

  // 한 요청으로 합치면 검색이 시드 임베딩을 가중 합산해 KNN을 한 번만 돌려 소수 종류가 사라진다.
  public List<SimilarProductResponse> recommend(List<Seed> seeds) {
    List<List<Seed>> groups = split(seeds);
    if (groups.size() == 1) {
      return post(groups.getFirst(), recommendationSize);
    }
    int groupSize =
        Math.clamp(
            (long) Math.ceilDiv(recommendationSize, groups.size()) * overfetch, 1, MAX_SEARCH_SIZE);
    List<CompletableFuture<List<SimilarProductResponse>>> responses =
        groups.stream()
            .map(group -> CompletableFuture.supplyAsync(() -> post(group, groupSize), executor))
            .toList();
    return merge(responses);
  }

  // 연속 구간이어야 한다. index % K로 쪼개면 모든 그룹이 모든 종류를 담아 다시 합산 평균이 된다.
  private List<List<Seed>> split(List<Seed> seeds) {
    int groupCount = Math.min(seeds.size(), maxGroups);
    if (groupCount <= 1) {
      return List.of(seeds);
    }
    List<List<Seed>> groups = new ArrayList<>(groupCount);
    int start = 0;
    for (int index = 0; index < groupCount; index++) {
      int size = seeds.size() / groupCount + (index < seeds.size() % groupCount ? 1 : 0);
      groups.add(seeds.subList(start, start + size));
      start += size;
    }
    return List.copyOf(groups);
  }

  private List<SimilarProductResponse> merge(
      List<CompletableFuture<List<SimilarProductResponse>>> responses) {
    List<List<SimilarProductResponse>> succeeded = new ArrayList<>(responses.size());
    RuntimeException firstFailure = null;
    for (CompletableFuture<List<SimilarProductResponse>> response : responses) {
      try {
        succeeded.add(response.join());
      } catch (CompletionException exception) {
        RuntimeException failure = unwrap(exception);
        log.warn("recommendation search group failed, continuing with the other groups", failure);
        if (firstFailure == null) {
          firstFailure = failure;
        }
      }
    }
    if (succeeded.isEmpty()) {
      throw firstFailure;
    }
    return interleave(succeeded);
  }

  private List<SimilarProductResponse> interleave(List<List<SimilarProductResponse>> results) {
    int longest = results.stream().mapToInt(List::size).max().orElse(0);
    Set<UUID> seen = new HashSet<>();
    List<SimilarProductResponse> merged = new ArrayList<>();
    for (int index = 0; index < longest; index++) {
      for (List<SimilarProductResponse> result : results) {
        if (index >= result.size()) {
          continue;
        }
        SimilarProductResponse candidate = result.get(index);
        if (candidate.id() == null || seen.add(candidate.id())) {
          merged.add(candidate);
        }
      }
    }
    // null id는 절단 전에 걸러야 한다. 안 그러면 뒤에 있던 유효한 후보가 잘려 나간다.
    // 오버페치한 행은 여기서 버린다. 상한을 풀면 LLM 프롬프트 입력 토큰이 그만큼 늘어난다.
    return merged.stream()
        .filter(candidate -> candidate.id() != null)
        .limit(recommendationSize)
        .toList();
  }

  private RuntimeException unwrap(CompletionException exception) {
    Throwable cause = exception.getCause();
    if (cause instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    if (cause instanceof Error error) {
      throw error;
    }
    return exception;
  }

  private List<SimilarProductResponse> post(List<Seed> seeds, int size) {
    String permitId = UUID.randomUUID().toString();
    if (!searchConcurrencyLimiter.tryAcquire(permitId)) {
      throw new SearchConcurrencyLimitedException(
          "search concurrency limit reached, shedding this search call");
    }
    try {
      return requireBody(
          restClient
              .post()
              .uri("/api/v1/searchs/recommand")
              .body(buildRequest(seeds, size))
              .retrieve()
              .body(new ParameterizedTypeReference<List<SimilarProductResponse>>() {}),
          "Search recommendation response body is empty");
    } finally {
      // release 실패를 여기서 삼키지 않으면 정상 응답을 이 finally의 예외가 덮어써 버린다.
      try {
        searchConcurrencyLimiter.release(permitId);
      } catch (RuntimeException exception) {
        log.warn("failed to release search concurrency permit {}", permitId, exception);
      }
    }
  }

  private SearchRecommendationRequest buildRequest(List<Seed> seeds, int size) {
    String ids = join(seeds, seed -> seed.productId().toString());
    String scores = join(seeds, seed -> Double.toString(seed.score()));
    String buys = join(seeds, seed -> seed.buy() ? "T" : "F");
    return new SearchRecommendationRequest(ids, scores, buys, size);
  }

  private String join(List<Seed> seeds, Function<Seed, String> mapper) {
    return seeds.stream().map(mapper).collect(Collectors.joining("|"));
  }

  private record SearchRecommendationRequest(String id, String score, String buy, Integer size) {}

  public record SimilarProductResponse(
      UUID id, String name, String description, String imgDescription) {}

  public static class SearchConcurrencyLimitedException extends RuntimeException {
    SearchConcurrencyLimitedException(String message) {
      super(message);
    }
  }
}

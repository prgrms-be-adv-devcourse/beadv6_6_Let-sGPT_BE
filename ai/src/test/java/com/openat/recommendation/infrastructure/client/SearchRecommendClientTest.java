package com.openat.recommendation.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.openat.recommendation.domain.model.Seed;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class SearchRecommendClientTest {

  private static final String BASE_URL = "http://search-service";
  private static final String RECOMMEND_URI = BASE_URL + "/api/v1/searchs/recommand";

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redisTemplate;

  private RestClient.Builder builder;
  private MockRestServiceServer server;
  private SearchRecommendClient client;

  @BeforeAll
  static void setUpRedis() {
    connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
  }

  @AfterAll
  static void closeConnectionFactory() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @BeforeEach
  void setUp() {
    builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    client = client(1);
  }

  private SearchRecommendClient client(int maxGroups) {
    return client(maxGroups, 1);
  }

  private SearchRecommendClient client(int maxGroups, int overfetch) {
    return new SearchRecommendClient(
        builder.build(),
        20,
        maxGroups,
        overfetch,
        100,
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        redisTemplate,
        Runnable::run);
  }

  @Test
  @DisplayName("유사 상품 조회는 시드 순서를 유지한 병렬 파이프 목록과 설정 크기를 JSON 본문으로 전달한다")
  void recommend_withSeeds_postsParallelPipeDelimitedJsonBodyInOrder() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID resultId = UUID.randomUUID();
    server
        .expect(requestTo(RECOMMEND_URI))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            content()
                .json(
                    """
                    {"id":"%s|%s","score":"0.9|0.6","buy":"F|T","size":20}
                    """
                        .formatted(first, second)))
        .andRespond(
            withSuccess(
                """
                        [{"id":"%s","name":"상품","description":"설명",
                          "imgDescription":"이미지 설명"}]
                        """
                    .formatted(resultId),
                MediaType.APPLICATION_JSON));

    var result =
        client.recommend(List.of(new Seed(first, 0.9, false), new Seed(second, 0.6, true)));

    assertThat(result)
        .singleElement()
        .satisfies(
            product -> {
              assertThat(product.id()).isEqualTo(resultId);
              assertThat(product.name()).isEqualTo("상품");
              assertThat(product.description()).isEqualTo("설명");
              assertThat(product.imgDescription()).isEqualTo("이미지 설명");
            });
    server.verify();
  }

  @Test
  @DisplayName("이미지 설명 필드가 없는 유사 상품 응답은 이미지 설명을 null로 역직렬화한다")
  void recommend_withoutImgDescription_deserializesImgDescriptionAsNull() {
    UUID resultId = UUID.randomUUID();
    server
        .expect(requestTo(RECOMMEND_URI))
        .andRespond(
            withSuccess(
                """
                        [{"id":"%s","name":"상품","description":"설명"}]
                        """
                    .formatted(resultId),
                MediaType.APPLICATION_JSON));

    var result = client.recommend(List.of(new Seed(UUID.randomUUID(), 0.5, false)));

    assertThat(result)
        .singleElement()
        .satisfies(product -> assertThat(product.imgDescription()).isNull());
    server.verify();
  }

  @Test
  @DisplayName("유사 상품 조회 응답이 빈 JSON 배열이면 빈 목록을 반환한다")
  void recommend_withEmptyJsonArray_returnsEmptyList() {
    server
        .expect(requestTo(RECOMMEND_URI))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    var result = client.recommend(List.of(new Seed(UUID.randomUUID(), 0.5, false)));

    assertThat(result).isEmpty();
    server.verify();
  }

  @Test
  @DisplayName("유사 상품 조회 응답 본문이 비어 있으면 예외를 던진다")
  void recommend_withEmptyResponseBody_throwsRestClientException() {
    server.expect(requestTo(RECOMMEND_URI)).andRespond(withSuccess());

    assertThatThrownBy(() -> client.recommend(List.of(new Seed(UUID.randomUUID(), 0.5, false))))
        .isInstanceOf(RestClientException.class)
        .hasMessage("Search recommendation response body is empty");
    server.verify();
  }

  @Test
  @DisplayName("시드 수가 그룹 상한 이하면 시드마다 한 번씩 호출하고, 오버페치 1이면 크기는 나눈 값 그대로다")
  void recommend_withSeedsUpToMaxGroups_callsSearchOncePerSeedWithDividedSize() {
    List<Seed> seeds = seeds(5);
    List<UUID> resultIds = seeds.stream().map(ignored -> UUID.randomUUID()).toList();
    for (int index = 0; index < seeds.size(); index++) {
      Seed seed = seeds.get(index);
      server
          .expect(requestTo(RECOMMEND_URI))
          .andExpect(
              content()
                  .json(
                      """
                      {"id":"%s","score":"0.5","buy":"F","size":4}
                      """
                          .formatted(seed.productId())))
          .andRespond(
              withSuccess(products(List.of(resultIds.get(index))), MediaType.APPLICATION_JSON));
    }
    client = client(5);

    var result = client.recommend(seeds);

    assertThat(result)
        .extracting(SearchRecommendClient.SimilarProductResponse::id)
        .containsExactlyElementsOf(resultIds);
    server.verify();
  }

  @Test
  @DisplayName("시드 수가 그룹 상한을 넘으면 연속 구간으로 균등하게 나누고 검색 크기도 나눠 준다")
  void recommend_withMoreSeedsThanMaxGroups_splitsIntoBalancedContiguousGroups() {
    List<Seed> seeds = seeds(5);
    server
        .expect(requestTo(RECOMMEND_URI))
        .andExpect(
            content()
                .json(
                    """
                    {"id":"%s|%s|%s","score":"0.5|0.5|0.5","buy":"F|F|F","size":10}
                    """
                        .formatted(
                            seeds.get(0).productId(),
                            seeds.get(1).productId(),
                            seeds.get(2).productId())))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(RECOMMEND_URI))
        .andExpect(
            content()
                .json(
                    """
                    {"id":"%s|%s","score":"0.5|0.5","buy":"F|F","size":10}
                    """
                        .formatted(seeds.get(3).productId(), seeds.get(4).productId())))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    client = client(2);

    client.recommend(seeds);

    server.verify();
  }

  @Test
  @DisplayName("그룹별 결과는 번갈아 끼워 합치고 같은 상품은 한 번만 남긴다")
  void recommend_withOverlappingGroupResults_interleavesAndDeduplicatesById() {
    UUID shared = UUID.randomUUID();
    UUID onlyFirst = UUID.randomUUID();
    UUID onlySecond = UUID.randomUUID();
    server
        .expect(requestTo(RECOMMEND_URI))
        .andRespond(withSuccess(products(List.of(shared, onlyFirst)), MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(RECOMMEND_URI))
        .andRespond(withSuccess(products(List.of(onlySecond, shared)), MediaType.APPLICATION_JSON));
    client = client(2);

    var result = client.recommend(seeds(2));

    assertThat(result)
        .extracting(SearchRecommendClient.SimilarProductResponse::id)
        .containsExactly(shared, onlySecond, onlyFirst);
    server.verify();
  }

  @Test
  @DisplayName("일부 그룹의 검색이 실패하면 성공한 그룹의 후보만으로 진행한다")
  void recommend_whenSomeGroupsFail_usesOnlySucceededGroups() {
    UUID resultId = UUID.randomUUID();
    server.expect(requestTo(RECOMMEND_URI)).andRespond(withServerError());
    server
        .expect(requestTo(RECOMMEND_URI))
        .andRespond(withSuccess(products(List.of(resultId)), MediaType.APPLICATION_JSON));
    client = client(2);

    var result = client.recommend(seeds(2));

    assertThat(result)
        .extracting(SearchRecommendClient.SimilarProductResponse::id)
        .containsExactly(resultId);
    server.verify();
  }

  @Test
  @DisplayName("모든 그룹의 검색이 실패하면 예외를 올려 폴백으로 넘긴다")
  void recommend_whenAllGroupsFail_throws() {
    server.expect(requestTo(RECOMMEND_URI)).andRespond(withServerError());
    server.expect(requestTo(RECOMMEND_URI)).andRespond(withServerError());
    client = client(2);

    assertThatThrownBy(() -> client.recommend(seeds(2))).isInstanceOf(RestClientException.class);
    server.verify();
  }

  @Test
  @DisplayName("그룹당 요청 크기는 오버페치 배수만큼 커지고 호출 횟수는 그대로다")
  void recommend_withOverfetch_multipliesGroupSizeWithoutMoreCalls() {
    List<Seed> seeds = seeds(5);
    for (Seed seed : seeds) {
      server
          .expect(requestTo(RECOMMEND_URI))
          .andExpect(
              content()
                  .json(
                      """
                      {"id":"%s","score":"0.5","buy":"F","size":12}
                      """
                          .formatted(seed.productId())))
          .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    }
    client = client(5, 3);

    client.recommend(seeds);

    server.verify();
  }

  @Test
  @DisplayName("오버페치한 원시 결과가 많아도 최종 후보는 설정 크기를 넘지 않는다")
  void recommend_withOverfetchedResults_truncatesToRecommendationSize() {
    List<UUID> firstIds = ids(15);
    List<UUID> secondIds = new ArrayList<>(ids(10));
    secondIds.addAll(firstIds.subList(0, 5));
    server
        .expect(requestTo(RECOMMEND_URI))
        .andRespond(withSuccess(products(firstIds), MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(RECOMMEND_URI))
        .andRespond(withSuccess(products(secondIds), MediaType.APPLICATION_JSON));
    client = client(2, 3);

    var result = client.recommend(seeds(2));

    assertThat(result).hasSize(20);
    assertThat(result)
        .extracting(SearchRecommendClient.SimilarProductResponse::id)
        .doesNotHaveDuplicates()
        .startsWith(firstIds.get(0), secondIds.get(0), firstIds.get(1), secondIds.get(1))
        .contains(secondIds.get(9));
    server.verify();
  }

  @Test
  @DisplayName("null id 후보가 앞쪽에 몰려도 절단 전에 걸러져 유효한 후보로 상한을 채운다")
  void recommend_withLeadingNullIdCandidates_fillsRecommendationSizeWithValidCandidates() {
    List<UUID> firstValidIds = ids(15);
    List<UUID> secondValidIds = ids(15);
    server
        .expect(requestTo(RECOMMEND_URI))
        .andRespond(
            withSuccess(productsWithLeadingNulls(12, firstValidIds), MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(RECOMMEND_URI))
        .andRespond(
            withSuccess(productsWithLeadingNulls(12, secondValidIds), MediaType.APPLICATION_JSON));
    client = client(2, 3);

    var result = client.recommend(seeds(2));

    assertThat(result).hasSize(20);
    assertThat(result)
        .extracting(SearchRecommendClient.SimilarProductResponse::id)
        .doesNotContainNull()
        .doesNotHaveDuplicates();
    server.verify();
  }

  @Test
  @DisplayName("그룹이 하나면 오버페치도 절단도 하지 않고 설정 크기로 한 번만 호출한다")
  void recommend_withSingleSeed_postsOneRequestWithFullSize() {
    Seed seed = new Seed(UUID.randomUUID(), 0.9, false);
    List<UUID> resultIds = ids(20);
    server
        .expect(requestTo(RECOMMEND_URI))
        .andExpect(
            content()
                .json(
                    """
                    {"id":"%s","score":"0.9","buy":"F","size":20}
                    """
                        .formatted(seed.productId())))
        .andRespond(withSuccess(products(resultIds), MediaType.APPLICATION_JSON));
    client = client(5, 3);

    var result = client.recommend(List.of(seed));

    assertThat(result)
        .extracting(SearchRecommendClient.SimilarProductResponse::id)
        .containsExactlyElementsOf(resultIds);
    server.verify();
  }

  private List<UUID> ids(int count) {
    return IntStream.range(0, count).mapToObj(ignored -> UUID.randomUUID()).toList();
  }

  private List<Seed> seeds(int count) {
    return IntStream.range(0, count)
        .mapToObj(ignored -> new Seed(UUID.randomUUID(), 0.5, false))
        .toList();
  }

  private String productsWithLeadingNulls(int nullCount, List<UUID> validIds) {
    List<String> items = new ArrayList<>();
    for (int i = 0; i < nullCount; i++) {
      items.add(
          """
          {"id":null,"name":"상품","description":"설명"}
          """);
    }
    for (UUID id : validIds) {
      items.add(
          """
          {"id":"%s","name":"상품","description":"설명"}
          """
              .formatted(id));
    }
    return "[" + String.join(",", items) + "]";
  }

  private String products(List<UUID> ids) {
    return ids.stream()
        .map(
            id ->
                """
                {"id":"%s","name":"상품","description":"설명"}
                """
                    .formatted(id))
        .reduce((left, right) -> left + "," + right)
        .map(joined -> "[" + joined + "]")
        .orElse("[]");
  }
}

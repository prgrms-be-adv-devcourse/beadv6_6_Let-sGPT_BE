package com.openat.recommendation.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.openat.recommendation.domain.model.Seed;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class SearchRecommendClientTest {

  private static final String BASE_URL = "http://search-service";
  private MockRestServiceServer server;
  private SearchRecommendClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new SearchRecommendClient(builder.build(), 20);
  }

  @Test
  @DisplayName("유사 상품 조회는 시드 순서를 유지한 병렬 파이프 목록과 설정 크기를 JSON 본문으로 전달한다")
  void recommend_withSeeds_postsParallelPipeDelimitedJsonBodyInOrder() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID resultId = UUID.randomUUID();
    server
        .expect(requestTo(BASE_URL + "/api/v1/searchs/recommand"))
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
        .expect(requestTo(BASE_URL + "/api/v1/searchs/recommand"))
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
        .expect(requestTo(BASE_URL + "/api/v1/searchs/recommand"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    var result = client.recommend(List.of(new Seed(UUID.randomUUID(), 0.5, false)));

    assertThat(result).isEmpty();
    server.verify();
  }

  @Test
  @DisplayName("유사 상품 조회 응답 본문이 비어 있으면 예외를 던진다")
  void recommend_withEmptyResponseBody_throwsRestClientException() {
    server
        .expect(requestTo(BASE_URL + "/api/v1/searchs/recommand"))
        .andRespond(withSuccess());

    assertThatThrownBy(
            () -> client.recommend(List.of(new Seed(UUID.randomUUID(), 0.5, false))))
        .isInstanceOf(RestClientException.class)
        .hasMessage("Search recommendation response body is empty");
    server.verify();
  }
}

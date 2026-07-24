package com.openat.recommendation.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class ProductDetailClientTest {

  private static final String BASE_URL = "http://product-service";
  private MockRestServiceServer server;
  private ProductDetailClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new ProductDetailClient(builder.build());
  }

  @Test
  @DisplayName("상품 상세 응답을 추천 상품 메타로 매핑한다")
  void getProduct_mapsResponseBody() {
    UUID productId = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    server
        .expect(requestTo(BASE_URL + "/api/v1/products/" + productId))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                {"id":"%s","sellerId":"%s","sellerName":"판매자","name":"상품",
                 "description":"설명","categoryId":"%s","categoryName":"카테고리",
                 "price":12000,"thumbnailKey":"thumb.png","imageKeys":["one.png"],
                 "createdAt":"2026-07-15T10:00:00Z"}
                """
                    .formatted(productId, sellerId, categoryId),
                MediaType.APPLICATION_JSON));

    var result = client.getProduct(productId);

    assertThat(result.id()).isEqualTo(productId);
    assertThat(result.sellerId()).isEqualTo(sellerId);
    assertThat(result.categoryId()).isEqualTo(categoryId);
    assertThat(result.name()).isEqualTo("상품");
    assertThat(result.price()).isEqualTo(12000L);
    assertThat(result.imageKeys()).containsExactly("one.png");
    assertThat(result.createdAt()).isEqualTo(Instant.parse("2026-07-15T10:00:00Z"));
    server.verify();
  }

  @Test
  @DisplayName("상품 상세 응답 본문이 비어 있으면 예외를 던진다")
  void getProduct_withEmptyResponseBody_throwsRestClientException() {
    UUID productId = UUID.randomUUID();
    server.expect(requestTo(BASE_URL + "/api/v1/products/" + productId)).andRespond(withSuccess());

    assertThatThrownBy(() -> client.getProduct(productId))
        .isInstanceOf(RestClientException.class)
        .hasMessage("Product detail response body is empty");
    server.verify();
  }

  @Test
  @DisplayName("상품 상세 API의 404 응답을 전파한다")
  void getProduct_whenApiReturnsNotFound_propagatesException() {
    UUID productId = UUID.randomUUID();
    server
        .expect(requestTo(BASE_URL + "/api/v1/products/" + productId))
        .andRespond(withResourceNotFound());

    assertThatThrownBy(() -> client.getProduct(productId))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
    server.verify();
  }
}

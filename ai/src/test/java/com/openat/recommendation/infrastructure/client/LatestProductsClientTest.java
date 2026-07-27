package com.openat.recommendation.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.openat.recommendation.infrastructure.client.LatestProductsClient.LatestProduct;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LatestProductsClientTest {

  private static final String BASE_URL = "http://product-service";
  private MockRestServiceServer server;
  private LatestProductsClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new LatestProductsClient(builder.build());
  }

  @Test
  @DisplayName("최신 상품 페이지 응답의 content를 매핑한다")
  void latest_mapsPageContent() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    server
        .expect(
            requestTo(
                allOf(
                    containsString(BASE_URL + "/api/v1/products"),
                    containsString("page=0"),
                    containsString("size=8"),
                    containsString("sort=createdAt"))))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                {"content":[
                  {"id":"%s","sellerName":"판매자1","name":"상품1","price":1000,"thumbnailKey":"a.png"},
                  {"id":"%s","sellerName":"판매자2","name":"상품2","price":2000,"thumbnailKey":"b.png"}
                ],"page":0,"size":8,"totalElements":2,"totalPages":1}
                """
                    .formatted(first, second),
                MediaType.APPLICATION_JSON));

    List<LatestProduct> result = client.latest(8);

    assertThat(result)
        .extracting(LatestProduct::id, LatestProduct::name, LatestProduct::price)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(first, "상품1", 1000L),
            org.assertj.core.groups.Tuple.tuple(second, "상품2", 2000L));
    server.verify();
  }

  @Test
  @DisplayName("content가 없으면 빈 목록을 돌려준다")
  void latest_whenContentMissing_returnsEmptyList() {
    server
        .expect(requestTo(containsString(BASE_URL + "/api/v1/products")))
        .andRespond(
            withSuccess(
                "{\"page\":0,\"size\":8,\"totalElements\":0,\"totalPages\":0}",
                MediaType.APPLICATION_JSON));

    assertThat(client.latest(8)).isEmpty();
    server.verify();
  }
}

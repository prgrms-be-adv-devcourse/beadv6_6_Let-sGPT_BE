package com.openat.recommendation.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class OpenDropRestClientTest {

  private static final String BASE_URL = "http://product-service";
  private MockRestServiceServer server;
  private OpenDropRestClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new OpenDropRestClient(builder.build(), 100);
  }

  @Test
  @DisplayName("OPEN 드롭을 마지막 페이지까지 조회해 화면 메타로 변환한다")
  void getAllOpenDrops_readsEveryPage() {
    UUID firstDropId = UUID.randomUUID();
    UUID secondDropId = UUID.randomUUID();
    UUID firstProductId = UUID.randomUUID();
    UUID secondProductId = UUID.randomUUID();
    expectPage(0, firstDropId, firstProductId, "첫 상품", 12000, "first.png", 2);
    expectPage(1, secondDropId, secondProductId, "둘째 상품", 23000, "second.png", 2);

    var result = client.getAllOpenDrops();

    assertThat(result)
        .extracting("dropId", "productId", "productName", "dropPrice", "thumbnailKey")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                firstDropId, firstProductId, "첫 상품", 12000L, "first.png"),
            org.assertj.core.groups.Tuple.tuple(
                secondDropId, secondProductId, "둘째 상품", 23000L, "second.png"));
    server.verify();
  }

  @Test
  @DisplayName("남은 수량이 0인 드롭은 제외한다")
  void getAllOpenDrops_excludesSoldOutDrops() {
    UUID openProductId = UUID.randomUUID();
    UUID soldOutProductId = UUID.randomUUID();
    server
        .expect(requestTo(BASE_URL + "/api/v1/drops?status=OPEN&page=0&size=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                {"content":[
                  {"id":"%s","productId":"%s","productName":"판매 중","sellerName":"판매자",
                   "categoryId":null,"categoryName":null,"thumbnailKey":"open.png","dropPrice":1000,
                   "totalQuantity":10,"remainingQuantity":1,"status":"OPEN",
                   "openAt":"2026-07-16T00:00:00Z","closeAt":null},
                  {"id":"%s","productId":"%s","productName":"품절","sellerName":"판매자",
                   "categoryId":null,"categoryName":null,"thumbnailKey":"sold-out.png","dropPrice":2000,
                   "totalQuantity":10,"remainingQuantity":0,"status":"SOLD_OUT",
                   "openAt":"2026-07-16T00:00:00Z","closeAt":null}
                ],"page":0,"size":100,"totalElements":2,"totalPages":1}
                """
                    .formatted(
                        UUID.randomUUID(),
                        openProductId,
                        UUID.randomUUID(),
                        soldOutProductId),
                MediaType.APPLICATION_JSON));

    var result = client.getAllOpenDrops();

    assertThat(result).extracting("productId").containsExactly(openProductId);
    server.verify();
  }

  @Test
  @DisplayName("오픈 드롭이 하나도 없으면 빈 목록을 반환한다")
  void getAllOpenDrops_whenNoOpenDrops_returnsEmptyList() {
    server
        .expect(requestTo(BASE_URL + "/api/v1/drops?status=OPEN&page=0&size=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                {"content":[],"page":0,"size":100,"totalElements":0,"totalPages":0}
                """,
                MediaType.APPLICATION_JSON));

    var result = client.getAllOpenDrops();

    assertThat(result).isEmpty();
    server.verify();
  }

  @Test
  @DisplayName("오픈 드롭 조회 응답 본문이 비어 있으면 예외를 던진다")
  void getAllOpenDrops_withEmptyResponseBody_throwsRestClientException() {
    server
        .expect(requestTo(BASE_URL + "/api/v1/drops?status=OPEN&page=0&size=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess());

    assertThatThrownBy(client::getAllOpenDrops)
        .isInstanceOf(RestClientException.class)
        .hasMessage("Product open drop response body is empty");
    server.verify();
  }

  @Test
  @DisplayName("오픈 드롭 조회 응답의 content가 null이면 예외를 던진다")
  void getAllOpenDrops_withNullContent_throwsRestClientException() {
    server
        .expect(requestTo(BASE_URL + "/api/v1/drops?status=OPEN&page=0&size=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                {"content":null,"page":0,"size":100,"totalElements":0,"totalPages":1}
                """,
                MediaType.APPLICATION_JSON));

    assertThatThrownBy(client::getAllOpenDrops)
        .isInstanceOf(RestClientException.class)
        .hasMessage("Product open drop response content is empty");
    server.verify();
  }

  @Test
  @DisplayName("오픈 드롭 조회 API가 서버 오류를 반환하면 예외를 전파한다")
  void getAllOpenDrops_whenApiReturnsServerError_propagatesRestClientException() {
    server
        .expect(requestTo(BASE_URL + "/api/v1/drops?status=OPEN&page=0&size=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withServerError());

    assertThatThrownBy(client::getAllOpenDrops).isInstanceOf(RestClientException.class);
    server.verify();
  }

  private void expectPage(
      int page,
      UUID dropId,
      UUID productId,
      String productName,
      long dropPrice,
      String thumbnailKey,
      int totalPages) {
    server
        .expect(requestTo(BASE_URL + "/api/v1/drops?status=OPEN&page=" + page + "&size=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                {"content":[{"id":"%s","productId":"%s","productName":"%s",
                "sellerName":"판매자","categoryId":null,"categoryName":null,
                "thumbnailKey":"%s","dropPrice":%d,"totalQuantity":10,
                "remainingQuantity":5,"status":"OPEN","openAt":"2026-07-16T00:00:00Z",
                "closeAt":null}],"page":%d,"size":100,"totalElements":2,"totalPages":%d}
                """
                    .formatted(
                        dropId,
                        productId,
                        productName,
                        thumbnailKey,
                        dropPrice,
                        page,
                        totalPages),
                MediaType.APPLICATION_JSON));
  }
}

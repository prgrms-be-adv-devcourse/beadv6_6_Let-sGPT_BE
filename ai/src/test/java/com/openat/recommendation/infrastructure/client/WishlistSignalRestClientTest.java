package com.openat.recommendation.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.openat.common.auth.UserHeaders;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class WishlistSignalRestClientTest {

  private static final String BASE_URL = "http://member-service";
  private MockRestServiceServer server;
  private WishlistSignalRestClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new WishlistSignalRestClient(builder.build(), 20);
  }

  @Test
  @DisplayName("찜 조회는 전달받은 회원 헤더와 첫 페이지 20개 제한을 member API에 전달한다")
  void getWishlistProductIds_withMemberId_forwardsHeaderAndPaging() {
    UUID memberId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    server
        .expect(requestTo(BASE_URL + "/api/v1/wishlist?page=0&size=20"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(UserHeaders.USER_ID, memberId.toString()))
        .andRespond(
            withSuccess(
                """
                        {"content":[{"productId":"%s"}],"page":0,"size":20,
                         "totalElements":1,"totalPages":1}
                        """
                    .formatted(productId),
                MediaType.APPLICATION_JSON));

    var result = client.getWishlistProductIds(memberId);

    assertThat(result).containsExactly(productId);
    server.verify();
  }

  @Test
  @DisplayName("찜 조회 응답 본문이 비어 있으면 예외를 던진다")
  void getWishlistProductIds_withEmptyResponseBody_throwsRestClientException() {
    UUID memberId = UUID.randomUUID();
    server
        .expect(requestTo(BASE_URL + "/api/v1/wishlist?page=0&size=20"))
        .andExpect(header(UserHeaders.USER_ID, memberId.toString()))
        .andRespond(withSuccess());

    assertThatThrownBy(() -> client.getWishlistProductIds(memberId))
        .isInstanceOf(RestClientException.class)
        .hasMessage("Member wishlist response body is empty");
    server.verify();
  }
}

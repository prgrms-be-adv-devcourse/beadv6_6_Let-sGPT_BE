package com.openat.chat.infrastructure.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.openat.chat.application.port.CryptoPricePort.CryptoAsset;
import com.openat.chat.application.port.CryptoPricePort.CryptoPriceQuery;
import com.openat.chat.application.port.CryptoPricePort.QuoteCurrency;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("CoinGecko 암호화폐 가격 어댑터")
class CoinGeckoCryptoPriceAdapterTest {

  private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");

  @Test
  @DisplayName("고정 자산·통화 카탈로그로 원화 가격과 기준 시각을 반환한다")
  void getPrice_validResponse_returnsDirectKrwPrice() {
    RestClient.Builder builder =
        RestClient.builder().baseUrl(CryptoPriceInfrastructureConfig.BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            requestTo(
                Matchers.startsWith(CryptoPriceInfrastructureConfig.BASE_URL + "/simple/price")))
        .andExpect(queryParam("ids", "bitcoin"))
        .andExpect(queryParam("vs_currencies", "krw"))
        .andExpect(queryParam("include_last_updated_at", "true"))
        .andRespond(
            withSuccess(
                """
                {
                  "bitcoin": {
                    "krw": 98300000,
                    "last_updated_at": %d
                  }
                }
                """
                    .formatted(NOW.minusSeconds(30).getEpochSecond()),
                MediaType.APPLICATION_JSON));
    CoinGeckoCryptoPriceAdapter adapter =
        new CoinGeckoCryptoPriceAdapter(
            builder.build(), new CoinGeckoProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

    var result = adapter.getPrice(new CryptoPriceQuery(CryptoAsset.BITCOIN, QuoteCurrency.KRW));

    server.verify();
    assertThat(result.price()).isEqualByComparingTo(new BigDecimal("98300000"));
    assertThat(result.currency()).isEqualTo("KRW");
    assertThat(result.sourceUrl()).contains("coingecko.com/en/coins/bitcoin");
  }

  @Test
  @DisplayName("오래된 가격은 현재가로 확정하지 않는다")
  void getPrice_staleResponse_rejects() {
    RestClient.Builder builder =
        RestClient.builder().baseUrl(CryptoPriceInfrastructureConfig.BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo(Matchers.any(String.class)))
        .andRespond(
            withSuccess(
                """
                {
                  "bitcoin": {
                    "krw": 98300000,
                    "last_updated_at": %d
                  }
                }
                """
                    .formatted(NOW.minusSeconds(1_000).getEpochSecond()),
                MediaType.APPLICATION_JSON));
    CoinGeckoCryptoPriceAdapter adapter =
        new CoinGeckoCryptoPriceAdapter(
            builder.build(), new CoinGeckoProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(
            () -> adapter.getPrice(new CryptoPriceQuery(CryptoAsset.BITCOIN, QuoteCurrency.KRW)))
        .isInstanceOf(IllegalStateException.class);
  }
}

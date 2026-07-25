package com.openat.chat.infrastructure.market;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.openat.chat.application.dto.CryptoPriceResult;
import com.openat.chat.application.port.CryptoPricePort;
import com.openat.chat.application.port.CryptoPricePort.QuoteCurrency;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CoinGeckoCryptoPriceAdapter implements CryptoPricePort {

  private static final Duration MAX_STALENESS = Duration.ofMinutes(15);

  private final RestClient restClient;
  private final CoinGeckoProperties properties;
  private final Clock clock;

  public CoinGeckoCryptoPriceAdapter(
      @Qualifier("coinGeckoRestClient") RestClient restClient,
      CoinGeckoProperties properties,
      Clock clock) {
    this.restClient = restClient;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public boolean isAvailable() {
    return properties.isEnabled();
  }

  @Override
  public CryptoPriceResult getPrice(CryptoPriceQuery query) {
    if (!isAvailable()) {
      throw new IllegalStateException("암호화폐 가격 도구가 비활성화되어 있어요.");
    }
    Map<String, CoinPrice> response =
        restClient
            .get()
            .uri(
                builder ->
                    builder
                        .path("/simple/price")
                        .queryParam("ids", query.asset().providerId())
                        .queryParam("vs_currencies", query.currency().providerId())
                        .queryParam("include_last_updated_at", true)
                        .queryParam("precision", "full")
                        .build())
            .retrieve()
            .onStatus(
                HttpStatusCode::isError,
                (ignoredRequest, providerResponse) -> {
                  throw new IllegalStateException(
                      "CoinGecko 가격 요청이 실패했어요. status=" + providerResponse.getStatusCode().value());
                })
            .body(new ParameterizedTypeReference<>() {});
    CoinPrice value = response == null ? null : response.get(query.asset().providerId());
    BigDecimal price = value == null ? null : value.price(query.currency());
    Instant lastUpdatedAt =
        value == null || value.lastUpdatedAt() == null
            ? null
            : Instant.ofEpochSecond(value.lastUpdatedAt());
    validate(price, lastUpdatedAt);
    return new CryptoPriceResult(
        query.asset().name(),
        query.currency().name(),
        price,
        lastUpdatedAt,
        "https://www.coingecko.com/en/coins/" + query.asset().providerId());
  }

  private void validate(BigDecimal price, Instant lastUpdatedAt) {
    Instant now = clock.instant();
    if (price == null
        || price.signum() <= 0
        || price.precision() > 30
        || lastUpdatedAt == null
        || lastUpdatedAt.isAfter(now.plusSeconds(120))
        || Duration.between(lastUpdatedAt, now).compareTo(MAX_STALENESS) > 0) {
      throw new IllegalStateException("암호화폐 가격 응답 형식이나 기준 시각이 올바르지 않아요.");
    }
  }

  private record CoinPrice(
      BigDecimal krw, BigDecimal usd, @JsonProperty("last_updated_at") Long lastUpdatedAt) {

    BigDecimal price(QuoteCurrency currency) {
      return currency == QuoteCurrency.KRW ? krw : usd;
    }
  }
}

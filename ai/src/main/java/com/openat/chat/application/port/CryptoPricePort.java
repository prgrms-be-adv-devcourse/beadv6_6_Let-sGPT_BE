package com.openat.chat.application.port;

import com.openat.chat.application.dto.CryptoPriceResult;
import java.util.Objects;

public interface CryptoPricePort {

  boolean isAvailable();

  CryptoPriceResult getPrice(CryptoPriceQuery query);

  record CryptoPriceQuery(CryptoAsset asset, QuoteCurrency currency) {

    public CryptoPriceQuery {
      Objects.requireNonNull(asset, "asset");
      Objects.requireNonNull(currency, "currency");
    }
  }

  enum CryptoAsset {
    BITCOIN("bitcoin"),
    ETHEREUM("ethereum"),
    SOLANA("solana");

    private final String providerId;

    CryptoAsset(String providerId) {
      this.providerId = providerId;
    }

    public String providerId() {
      return providerId;
    }
  }

  enum QuoteCurrency {
    KRW,
    USD;

    public String providerId() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }
}

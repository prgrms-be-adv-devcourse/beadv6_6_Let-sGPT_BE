package com.openat.chat.infrastructure.inference.tool;

import com.openat.chat.application.dto.CryptoPriceResult;
import com.openat.chat.application.port.CryptoPricePort;
import com.openat.chat.application.port.CryptoPricePort.CryptoAsset;
import com.openat.chat.application.port.CryptoPricePort.CryptoPriceQuery;
import com.openat.chat.application.port.CryptoPricePort.QuoteCurrency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CryptoPriceTools {

  private static final Logger log = LoggerFactory.getLogger(CryptoPriceTools.class);

  private final CryptoPricePort pricePort;

  public CryptoPriceTools(CryptoPricePort pricePort) {
    this.pricePort = pricePort;
  }

  @Tool(
      name = "getCryptoPrice",
      description =
          "Bitcoin, Ethereum 또는 Solana의 현재 KRW·USD 가격을 CoinGecko에서 직접 조회한다. 암호화폐 현재 가격 질문에는 웹 검색이나 환산 대신 사용한다.")
  public AdminToolResult getCryptoPrice(
      @ToolParam(description = "BITCOIN, ETHEREUM 또는 SOLANA") CryptoAsset asset,
      @ToolParam(description = "원화는 KRW, 미국 달러는 USD") QuoteCurrency currency,
      ToolContext toolContext) {
    AdminToolExecutionContext context = AdminToolContexts.required(toolContext);
    context.started();
    try {
      if (!pricePort.isAvailable()) {
        return context.completed(
            AdminToolResult.failed("CRYPTO_PRICE_UNAVAILABLE", "암호화폐 가격 도구가 비활성화되어 있어요."));
      }
      CryptoPriceResult result = pricePort.getPrice(new CryptoPriceQuery(asset, currency));
      return context.completed(AdminToolResult.success(result));
    } catch (IllegalArgumentException exception) {
      return context.completed(
          AdminToolResult.failed("CRYPTO_PRICE_REQUEST_INVALID", exception.getMessage()));
    } catch (RuntimeException exception) {
      log.warn("암호화폐 가격 도구 실패 errorType={}", exception.getClass().getSimpleName());
      return context.completed(
          AdminToolResult.failed("CRYPTO_PRICE_PROVIDER_FAILED", "현재 암호화폐 가격을 가져오지 못했어요."));
    }
  }
}

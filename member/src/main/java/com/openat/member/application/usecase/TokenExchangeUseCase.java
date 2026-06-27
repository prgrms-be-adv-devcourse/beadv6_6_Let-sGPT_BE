package com.openat.member.application.usecase;

import com.openat.member.application.dto.TokenExchangeRequest;
import com.openat.member.application.dto.TokenExchangeResponse;

/**
 * RFC 8693 OAuth 2.0 Token Exchange.
 * 사용자 access JWT(subject_token)를 sellerInfoId 범위로 한정된 scoped 토큰으로 교환한다.
 */
public interface TokenExchangeUseCase {

    TokenExchangeResponse exchange(TokenExchangeRequest request);
}

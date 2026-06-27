package com.openat.member.presentation.controller;

import com.openat.member.application.dto.TokenExchangeRequest;
import com.openat.member.application.dto.TokenExchangeResponse;
import com.openat.member.application.usecase.TokenExchangeUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFC 8693 OAuth 2.0 Token Exchange 엔드포인트.
 *
 * <p>사용자 access JWT(subject_token)를 sellerInfoId 범위로 한정된 scoped 토큰으로 교환한다.
 * 게이트웨이에서 permitAll 처리되며, 이 엔드포인트는 body의 subject_token을 직접 검증한다.
 *
 * <p>요청 예시:
 * <pre>
 * POST /auth/token
 * Content-Type: application/x-www-form-urlencoded
 *
 * grant_type=urn:ietf:params:oauth:grant-type:token-exchange
 * &subject_token=eyJ...
 * &subject_token_type=urn:ietf:params:oauth:token-type:jwt
 * &requested_token_type=urn:ietf:params:oauth:token-type:jwt
 * &audience=openat-product
 * &scope=product:write
 * &resource=urn:openat:seller:{sellerInfoId}
 * </pre>
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class TokenExchangeController {

    private final TokenExchangeUseCase tokenExchangeUseCase;

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<TokenExchangeResponse> exchange(
            @RequestParam("grant_type") String grantType,
            @RequestParam("subject_token") String subjectToken,
            @RequestParam("subject_token_type") String subjectTokenType,
            @RequestParam("requested_token_type") String requestedTokenType,
            @RequestParam String audience,
            @RequestParam String scope,
            @RequestParam String resource
    ) {
        TokenExchangeRequest request = new TokenExchangeRequest(
                grantType, subjectToken, subjectTokenType, requestedTokenType,
                audience, scope, resource
        );
        return ResponseEntity.ok(tokenExchangeUseCase.exchange(request));
    }
}

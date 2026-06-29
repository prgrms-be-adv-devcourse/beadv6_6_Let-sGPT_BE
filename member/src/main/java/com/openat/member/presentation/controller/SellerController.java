package com.openat.member.presentation.controller;

import com.openat.common.auth.CurrentUser;
import com.openat.common.auth.UserContext;
import com.openat.common.web.Locations;
import com.openat.member.application.dto.CreateSellerInfoRequest;
import com.openat.member.application.dto.PatchSellerInfoRequest;
import com.openat.member.application.dto.SellerInfoResponse;
import com.openat.member.application.dto.SellerTokenRequest;
import com.openat.member.application.dto.SellerTokenResponse;
import com.openat.member.application.usecase.SellerUseCase;
import com.openat.member.application.usecase.TokenExchangeUseCase;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller")
@RequiredArgsConstructor
public class SellerController {

    private final SellerUseCase sellerUseCase;
    private final TokenExchangeUseCase tokenExchangeUseCase;

    // -----------------------------------------------------------------------
    // 본인(me) 판매자 정보 관리 — 인증된 회원 누구나 접근 가능
    // -----------------------------------------------------------------------

    /**
     * 본인 판매자 정보 목록 조회.
     *
     * @param isActive false(기본값)=활성만, true=전체(삭제 포함)
     */
    @GetMapping("/me")
    public ResponseEntity<List<SellerInfoResponse>> getMySellerInfo(
            @CurrentUser UserContext userContext,
            @RequestParam(defaultValue = "false") boolean isActive
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        return ResponseEntity.ok(sellerUseCase.getMySellerInfo(memberId, isActive));
    }

    /**
     * 판매자 정보 신규 등록. 성공 시 role이 ROLE_SELLER로 승격된다.
     * Location 헤더는 관리자 전체 조회 경로({@code GET /api/v1/seller/{userId}})를 가리킨다.
     */
    @PostMapping("/me")
    public ResponseEntity<SellerInfoResponse> create(
            @CurrentUser UserContext userContext,
            @Valid @RequestBody CreateSellerInfoRequest request
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        SellerInfoResponse response = sellerUseCase.create(memberId, request);
        return ResponseEntity.created(Locations.fromPath("/api/v1/seller", memberId)).body(response);
    }

    /** 특정 SellerInfo의 storeName 수정. 본인 소유가 아니거나 존재하지 않으면 404. */
    @PatchMapping("/me/{sellerId}")
    public ResponseEntity<SellerInfoResponse> patch(
            @CurrentUser UserContext userContext,
            @PathVariable UUID sellerId,
            @Valid @RequestBody PatchSellerInfoRequest request
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        return ResponseEntity.ok(sellerUseCase.patch(memberId, sellerId, request));
    }

    /** 판매자 정보 논리 삭제. 활성 건이 모두 사라지면 role이 ROLE_USER로 강등된다. */
    @DeleteMapping("/me/{sellerId}")
    public ResponseEntity<Void> delete(
            @CurrentUser UserContext userContext,
            @PathVariable UUID sellerId
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        sellerUseCase.delete(memberId, sellerId);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // 판매자 scoped 토큰
    // -----------------------------------------------------------------------

    /**
     * 판매자 스토어 범위 scoped 토큰 발급.
     *
     * <p>게이트웨이가 회원 JWT를 검증하고 X-User-Id(memberId)를 주입한다.
     * 요청한 sellerInfoId가 본인 소유인지 확인 후 scoped 토큰을 발급한다.
     *
     * <p>scoped 토큰은 수명이 짧으므로(기본 120초) 만료 시 이 엔드포인트를 다시 호출해 재발급한다.
     * refresh 토큰 없이 회원 JWT가 갱신 수단을 겸한다.
     */
    @PostMapping("/token")
    public ResponseEntity<SellerTokenResponse> issueSellerToken(
            @CurrentUser UserContext userContext,
            @RequestBody SellerTokenRequest request
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        return ResponseEntity.ok(tokenExchangeUseCase.issueSellerToken(memberId, request.sellerInfoId()));
    }

    // -----------------------------------------------------------------------
    // 관리자(admin) 전용
    // -----------------------------------------------------------------------

    /**
     * 특정 회원의 판매자 정보 전체 조회 (관리자 전용).
     * 게이트웨이에서 ROLE_ADMIN 인가를 강제하며, 삭제된 이력까지 모두 반환한다.
     */
    @GetMapping("/{userId}")
    public ResponseEntity<List<SellerInfoResponse>> getSellerInfosByUserId(@PathVariable UUID userId) {
        return ResponseEntity.ok(sellerUseCase.getSellerInfosByUserId(userId));
    }
}

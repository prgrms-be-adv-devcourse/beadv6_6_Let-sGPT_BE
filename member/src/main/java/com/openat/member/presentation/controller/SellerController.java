package com.openat.member.presentation.controller;

import com.openat.common.auth.CurrentUser;
import com.openat.common.auth.UserContext;
import com.openat.common.web.Locations;
import com.openat.member.application.dto.CreateSellerInfoRequest;
import com.openat.member.application.dto.PatchSellerInfoRequest;
import com.openat.member.application.dto.SellerInfoResponse;
import com.openat.member.application.dto.UpdateSellerInfoRequest;
import com.openat.member.application.usecase.SellerUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * gateway에서 "/member" 접두사가 StripPrefix로 제거된 뒤 그대로 매칭되는 경로.
 * 활성(논리적 삭제되지 않은) SellerInfo는 회원당 항상 0개 또는 1개이며, 그 자체는 별도 GET으로
 * 노출하지 않는다 — {@code MemberController#getMyInfo}(member의 /me 조회) 응답에 항상 같이 포함된다.
 *
 * <p>POST/PUT은 아직 ROLE_USER인 회원이 "최초 판매자 전환"을 할 때도 호출해야 하므로
 * apigateway에서 이 경로 전체는 hasRole("SELLER")가 아니라 authenticated()로만 보호한다
 * (활성 SellerInfo 존재 여부에 따른 실질적인 보호는 아래 서비스 로직에서 처리).
 *
 * <p>응답 컨벤션: 공통 봉투({@code ApiResponse}) 없이 {@code ResponseEntity<T>}로 리소스를 그대로
 * 반환한다. 생성은 201 + Location, 본문이 없는 응답(삭제)은 204 No Content.
 */
@RestController
@RequestMapping("/api/v1/seller")
@RequiredArgsConstructor
public class SellerController {

    private final SellerUseCase sellerUseCase;

    /** 활성 SellerInfo가 없을 때만(null) 호출 가능. 성공 시 role이 ROLE_SELLER로 바뀐다. */
    @PostMapping
    public ResponseEntity<SellerInfoResponse> create(
            @CurrentUser UserContext userContext,
            @Valid @RequestBody CreateSellerInfoRequest request
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        SellerInfoResponse response = sellerUseCase.create(memberId, request);
        return ResponseEntity.created(Locations.fromCurrentRequest(response.id())).body(response);
    }

    /**
     * businessNumber/storeName 동시 수정. 기존 활성 SellerInfo는 논리적 삭제되고 요청 값으로 새로 생성된다
     * (없었으면 그냥 생성). 성공 시 role이 ROLE_SELLER로 바뀐다.
     */
    @PutMapping
    public ResponseEntity<SellerInfoResponse> update(
            @CurrentUser UserContext userContext,
            @Valid @RequestBody UpdateSellerInfoRequest request
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        return ResponseEntity.ok(sellerUseCase.update(memberId, request));
    }

    /** storeName만 수정 가능. 활성 SellerInfo가 없으면 404. */
    @PatchMapping
    public ResponseEntity<SellerInfoResponse> patch(
            @CurrentUser UserContext userContext,
            @Valid @RequestBody PatchSellerInfoRequest request
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        return ResponseEntity.ok(sellerUseCase.patch(memberId, request));
    }

    /** 논리적 삭제. 더 이상 활성 SellerInfo가 없으면 role이 ROLE_USER로 내려간다. */
    @DeleteMapping
    public ResponseEntity<Void> delete(@CurrentUser UserContext userContext) {
        UUID memberId = UUID.fromString(userContext.userId());
        sellerUseCase.delete(memberId);
        return ResponseEntity.noContent().build();
    }
}

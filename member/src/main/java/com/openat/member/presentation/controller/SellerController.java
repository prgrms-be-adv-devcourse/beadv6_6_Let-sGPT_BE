package com.openat.member.presentation.controller;

import com.openat.common.auth.CurrentUser;
import com.openat.common.auth.UserContext;
import com.openat.common.web.Locations;
import com.openat.member.application.dto.CreateSellerInfoRequest;
import com.openat.member.application.dto.PatchSellerInfoRequest;
import com.openat.member.application.dto.SellerInfoResponse;
import com.openat.member.application.usecase.SellerUseCase;
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

    @GetMapping
    public ResponseEntity<List<SellerInfoResponse>> getMySellerInfo(
            @CurrentUser UserContext userContext,
            @RequestParam(defaultValue = "false") boolean isActive
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        return ResponseEntity.ok(sellerUseCase.getMySellerInfo(memberId, isActive));
    }

    /** 판매자 정보 신규 등록. 성공 시 role이 ROLE_SELLER로 바뀐다. */
    @PostMapping
    public ResponseEntity<SellerInfoResponse> create(
            @CurrentUser UserContext userContext,
            @Valid @RequestBody CreateSellerInfoRequest request
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        SellerInfoResponse response = sellerUseCase.create(memberId, request);
        return ResponseEntity.created(Locations.fromCurrentRequest(response.id())).body(response);
    }

    /** 특정 SellerInfo의 storeName 수정. 본인 소유가 아니거나 존재하지 않으면 404. */
    @PatchMapping("/{sellerId}")
    public ResponseEntity<SellerInfoResponse> patch(
            @CurrentUser UserContext userContext,
            @PathVariable UUID sellerId,
            @Valid @RequestBody PatchSellerInfoRequest request
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        return ResponseEntity.ok(sellerUseCase.patch(memberId, sellerId, request));
    }

    @DeleteMapping("/{sellerId}")
    public ResponseEntity<Void> delete(
            @CurrentUser UserContext userContext,
            @PathVariable UUID sellerId
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        sellerUseCase.delete(memberId, sellerId);
        return ResponseEntity.noContent().build();
    }
}

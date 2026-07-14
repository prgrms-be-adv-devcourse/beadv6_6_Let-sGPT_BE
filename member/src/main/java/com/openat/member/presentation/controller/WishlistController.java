package com.openat.member.presentation.controller;

import com.openat.common.auth.CurrentUser;
import com.openat.common.auth.UserContext;
import com.openat.common.response.PageResponse;
import com.openat.common.web.Locations;
import com.openat.member.application.dto.AddWishlistRequest;
import com.openat.member.application.dto.WishlistItemResponse;
import com.openat.member.application.usecase.WishlistUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wishlist")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistUseCase wishlistUseCase;

    /** 찜 추가. 이미 찜한 상품이면 멱등하게 200을 반환한다(중복 추가되지 않음). */
    @PostMapping
    public ResponseEntity<Void> add(
            @CurrentUser UserContext userContext,
            @Valid @RequestBody AddWishlistRequest request
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        boolean added = wishlistUseCase.add(memberId, request.productId());
        if (added) {
            return ResponseEntity.created(Locations.fromPath("/api/v1/wishlist", request.productId())).build();
        }
        return ResponseEntity.ok().build();
    }

    /** 찜 해제. 찜하지 않은 상품이어도 멱등하게 204를 반환한다. */
    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> remove(
            @CurrentUser UserContext userContext,
            @PathVariable UUID productId
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        wishlistUseCase.remove(memberId, productId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 본인 찜 목록 조회 — 항상 최신순(createdAt desc) 고정 정렬.
     * 정렬 기준은 서버가 강제하므로 클라이언트가 sort 파라미터로 바꿀 수 없도록 Pageable을
     * 직접 바인딩하지 않고 page/size만 받는다.
     */
    @GetMapping
    public ResponseEntity<PageResponse<WishlistItemResponse>> getMyWishlist(
            @CurrentUser UserContext userContext,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        Page<WishlistItemResponse> result =
                wishlistUseCase.getMyWishlist(memberId, PageRequest.of(page, size, sort));
        return ResponseEntity.ok(PageResponse.of(result));
    }
}

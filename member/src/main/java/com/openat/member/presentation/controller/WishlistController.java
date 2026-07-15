package com.openat.member.presentation.controller;

import com.openat.common.auth.CurrentUser;
import com.openat.common.auth.UserContext;
import com.openat.common.response.PageResponse;
import com.openat.member.application.dto.SetWishlistRequest;
import com.openat.member.application.dto.WishlistItemResponse;
import com.openat.member.application.usecase.WishlistUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wishlist")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistUseCase wishlistUseCase;

    /**
     * 찜 상태 설정 — {@code wished=true}면 추가, {@code false}면 해제. 둘 다 멱등하고, 어느
     * 방향이든 항상 200을 반환한다(응답 상태코드로 add/remove를 구분하지 않음 — FE가 결과 처리를
     * 분기 없이 하나로 둘 수 있도록).
     */
    @PutMapping("/{productId}")
    public ResponseEntity<Void> setWished(
            @CurrentUser UserContext userContext,
            @PathVariable UUID productId,
            @Valid @RequestBody SetWishlistRequest request
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        if (request.wished()) {
            wishlistUseCase.add(memberId, productId);
        } else {
            wishlistUseCase.remove(memberId, productId);
        }
        return ResponseEntity.ok().build();
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

package com.openat.common.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 오프셋 페이징 공통 응답.
 *
 * <p>합의 포맷의 {@code content / totalPages / totalElements}에 더해, 클라이언트가 현재
 * 위치를 알 수 있도록 {@code page / size}를 표준으로 포함한다.
 *
 * <pre>
 * Page&lt;OrderResponse&gt; page = orderService.findMyOrders(memberId, pageable);
 * return ResponseEntity.ok(PageResponse.of(page));
 * </pre>
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}

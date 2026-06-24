package com.openat.common.web;

import java.net.URI;
import java.util.UUID;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** 생성 응답 Location 헤더용 URI 구성 헬퍼. */
public final class Locations {

    private Locations() {}

    /** 현재 요청 URL 하위 {@code /{id}}를 생성 리소스 URI로 만든다. */
    public static URI fromCurrentRequest(UUID id) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(id)
                .toUri();
    }

    /**
     * 현재 요청과 무관하게 지정된 {@code basePath}/{id} URI를 만든다.
     *
     * <p>생성 엔드포인트(POST)의 경로와 리소스 조회 경로가 다를 때 사용한다.
     * 예: {@code POST /api/v1/seller/me} 에서 {@code GET /api/v1/seller/{id}} 를 Location으로 반환.
     * <pre>
     *   Locations.fromPath("/api/v1/seller", response.id())
     *   // → http://host:port/api/v1/seller/{id}
     * </pre>
     */
    public static URI fromPath(String basePath, UUID id) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(basePath + "/{id}")
                .buildAndExpand(id)
                .toUri();
    }
}

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
}

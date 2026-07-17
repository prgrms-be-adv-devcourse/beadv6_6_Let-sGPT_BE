package com.openat.member.infrastructure.kafka.event;

import java.util.UUID;

/**
 * 판매자 스토어명 등록/변경 이벤트.
 *
 * <p>product의 {@code com.openat.seller.infrastructure.kafka.event.SellerStoreChangedEvent}와
 * 필드 이름·타입이 동일해야 한다 — 두 모듈이 이 이벤트를 JSON으로 주고받는 계약이기 때문이다.
 * (product가 소비해 로컬 SellerStore 프로젝션 테이블(sellerInfoId -> storeName)을 채우고,
 * 상품 등록/수정 Kafka 이벤트에 sellerName을 실을 때 그 프로젝션을 조회한다.)
 *
 * <p>등록은 {@code seller-registered} 토픽, storeName 수정은 {@code seller-updated} 토픽으로
 * 발행한다 — 두 이벤트 모두 페이로드 모양은 동일(upsert 의미)하므로 레코드는 하나로 공유한다.
 */
public record SellerStoreChangedEvent(
        UUID sellerInfoId,
        String storeName
) {
}

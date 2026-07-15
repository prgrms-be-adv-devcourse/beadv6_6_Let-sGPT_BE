package com.openat.member.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * 회원의 찜(위시리스트) 항목. 회원 1명당 상품 1개는 최대 한 행만 존재한다
 * ({@code member_id, product_id} UNIQUE) — 존재/부재 토글이므로 물리 삭제(hard delete)로 관리하고,
 * soft-delete(deletedAt)는 사용하지 않는다(재찜 시 UNIQUE 제약과 충돌하기 때문).
 *
 * <p>Member을 {@code @ManyToOne}으로 조인하지 않고 {@code memberId}를 원시 UUID로 저장한다.
 * 찜 추가/삭제마다 Member 엔티티를 로딩할 필요가 없고, order 모듈이 memberId를 raw UUID로
 * 저장하는 것과 동일한 컨벤션이다.
 */
@Entity
@Table(name = "wishlist_item",
        uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "product_id"}),
        indexes = @Index(name = "idx_wishlist_item_member_created", columnList = "member_id, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WishlistItem extends BaseTimeEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Builder
    private WishlistItem(UUID memberId, UUID productId) {
        this.memberId = memberId;
        this.productId = productId;
    }
}

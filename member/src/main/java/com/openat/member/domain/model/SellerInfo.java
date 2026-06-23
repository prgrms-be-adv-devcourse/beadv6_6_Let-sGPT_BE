package com.openat.member.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "seller_info")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerInfo extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String businessNumber;

    @Column(nullable = false)
    private String storeName;

    // 다른 모듈/도메인에 영향이 큰 양방향 연관관계라, Member 쪽에는 역방향(@OneToMany)을 두지 않았다.
    // 자세한 이유는 답변 메시지 참고.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Builder
    private SellerInfo(String businessNumber, String storeName, Member member) {
        this.id = UUID.randomUUID();
        this.businessNumber = businessNumber;
        this.storeName = storeName;
        this.member = member;
    }

    /** PATCH 전용 — storeName만 변경 가능(businessNumber는 PUT으로만 갈아끼움). */
    public void changeStoreName(String storeName) {
        this.storeName = storeName;
    }
}

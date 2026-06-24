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
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "seller_info")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerInfo extends BaseTimeEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private String businessNumber;

    @Column(nullable = false)
    private String storeName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Builder
    private SellerInfo(String businessNumber, String storeName, Member member) {
        this.businessNumber = businessNumber;
        this.storeName = storeName;
        this.member = member;
    }

    public void changeStoreName(String storeName) {
        this.storeName = storeName;
    }
}

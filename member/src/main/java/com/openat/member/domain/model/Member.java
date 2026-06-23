package com.openat.member.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlatformType platformType;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, unique = true)
    private String nickname;

    @Builder
    private Member(Role role, PlatformType platformType, String email, String password, String nickname) {
        this.id = UUID.randomUUID();
        this.role = role;
        this.platformType = platformType;
        this.email = email;
        this.password = password;
        this.nickname = nickname;
    }

    /** 인코딩된 비밀번호를 전달받아 교체한다 (평문 인코딩은 application 계층의 책임). */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    public void withdraw() {
        markDeleted();
    }

    /** 활성 SellerInfo를 (처음 또는 다시) 갖게 됐을 때 권한을 SELLER로 올린다. */
    public void promoteToSeller() {
        this.role = Role.ROLE_SELLER;
    }

    /** 활성 SellerInfo가 하나도 남지 않게 됐을 때 권한을 USER로 내린다. */
    public void demoteToUser() {
        this.role = Role.ROLE_USER;
    }
}

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

    /**
     * 회원 탈퇴(논리적 삭제). {@link BaseTimeEntity#markDeleted()}로 deletedAt만 채우고
     * 실제 row는 지우지 않는다 — 이후 findById/findByEmail(활성 회원 전용 조회)에서 안 보이게 된다.
     */
    public void withdraw() {
        markDeleted();
    }
}

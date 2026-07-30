package com.openat.member.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    // 익명화 시 이메일/닉네임 앞에 붙이는 접두사. memberId를 함께 섞어 유일성을 보장한다 —
    // 접두사만 붙이면 "탈퇴→익명화→같은 이메일로 재가입→또 탈퇴→또 익명화"가 반복될 때
    // 두 번째 익명화가 첫 번째와 동일한 값이 되어 unique 제약을 위반할 수 있다.
    private static final String ANONYMIZED_PREFIX = "deleted_";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlatformType platformType;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, unique = true)
    private String nickname;

    // 탈퇴 유예기간(30일) 만료 후 스케줄러가 email/nickname을 치환한 시각. null이면 아직
    // 유예기간 중(또는 탈퇴한 적 없음)이라는 뜻 — 로그인 시 원래 이메일로 다시 조회될 수 있고,
    // 복구(restore)도 이 시점 전까지만 가능하다(치환 후엔 원래 이메일로 조회 자체가 안 되므로
    // 별도 만료 체크 없이도 자연히 복구 불가 상태가 된다).
    @Column
    private LocalDateTime anonymizedAt;

    @Builder
    private Member(PlatformType platformType, String email, String password, String nickname) {
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

    /**
     * 탈퇴 유예기간(30일) 만료 후 스케줄러가 호출한다. email/nickname을 재사용 가능하도록
     * 치환해 회원가입 시 existsByEmail/existsByNickname 중복 체크에 더 이상 걸리지 않게 한다.
     * deletedAt은 그대로 둔다(탈퇴 상태 자체는 유지 — 물리 삭제가 아니라 식별정보만 치환).
     */
    public void anonymize() {
        this.email = ANONYMIZED_PREFIX + id + "_" + email;
        this.nickname = ANONYMIZED_PREFIX + id;
        this.anonymizedAt = LocalDateTime.now();
    }
}

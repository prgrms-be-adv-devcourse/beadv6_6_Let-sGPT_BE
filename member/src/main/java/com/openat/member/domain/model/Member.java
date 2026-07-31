package com.openat.member.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "member", indexes = {
        // MemberAnonymizeScheduler의 findWithdrawnBefore(deletedAt <= cutoff AND anonymizedAt
        // IS NULL, ORDER BY deletedAt) 조회를 지원한다.
        @Index(name = "idx_member_deleted_at_anonymized_at", columnList = "deletedAt, anonymizedAt")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    /**
     * 익명화 시 이메일/닉네임 앞에 붙이는 접두사. 실제 치환은 이제 엔티티가 아니라
     * {@code MemberJpaRepository.anonymizeIfEligible}의 원자적 UPDATE가 SQL로 직접 수행한다
     * (이유는 아래 클래스 주석 참고) — 이 상수는 그 SQL 리터럴과 값이 일치해야 하며, 테스트에서
     * 기대값을 조립할 때 재사용한다.
     */
    public static final String ANONYMIZED_PREFIX = "deleted_";

    /**
     * 탈퇴 후 복구 가능한 유예기간. {@code MemberService.restore()}와
     * {@code MemberAnonymizeScheduler}(익명화 대상 판별)가 이 값을 공유한다 — 두 곳에 각자
     * 30일을 하드코딩하면 값이 어긋날 위험이 있다.
     */
    public static final Duration WITHDRAWAL_GRACE_PERIOD = Duration.ofDays(30);

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlatformType platformType;

    // 익명화된 이메일(ANONYMIZED_PREFIX + memberId(36자) + "_" + 원본 이메일)을 담을 수 있도록
    // 기본 255보다 넉넉하게 잡는다(8 + 36 + 1 + RFC 최대 254 = 299, 여유를 둬 320).
    @Column(nullable = false, unique = true, length = 320)
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
}

package com.openat.member.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

    // 익명화 시 이메일/닉네임 앞에 붙이는 접두사. memberId를 함께 섞어 유일성을 보장한다 —
    // 접두사만 붙이면 "탈퇴→익명화→같은 이메일로 재가입→또 탈퇴→또 익명화"가 반복될 때
    // 두 번째 익명화가 첫 번째와 동일한 값이 되어 unique 제약을 위반할 수 있다.
    private static final String ANONYMIZED_PREFIX = "deleted_";

    /**
     * 탈퇴 후 복구 가능한 유예기간. {@link #isRestorable()}(로그인 시 복구 가능 여부)와
     * {@link com.openat.member.infrastructure.scheduler.MemberAnonymizeScheduler}(익명화 대상
     * 판별)가 이 값을 공유한다 — 두 곳에 각자 30일을 하드코딩하면 값이 어긋날 위험이 있다.
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

    /**
     * 낙관적 락. restore()(로그인 시 복구)와 anonymize()(익명화 스케줄러)가 같은 회원 행을
     * 조회 후 각자 변경할 수 있는데, 잠금·조건부 UPDATE 없이 둘 다 "조회한 시점의 스냅샷"을
     * 그대로 저장하면 나중에 커밋되는 쪽이 먼저 커밋된 변경을 덮어써 버린다(예: 익명화가 먼저
     * 커밋된 뒤 그 사실을 모르는 복구 트랜잭션이 커밋되면, Hibernate는 기본적으로 매핑된 모든
     * 컬럼을 갱신하므로 복구 스냅샷에 남아있던 옛 email/nickname으로 되돌려 익명화 결과를
     * 잃어버릴 수 있다 — 반대 순서도 마찬가지). @Version을 두면 두 트랜잭션 중 나중에 커밋되는
     * 쪽의 UPDATE가 WHERE version=? 불일치로 0행 갱신 → Hibernate가
     * ObjectOptimisticLockingFailureException을 던져 커밋을 막는다. 즉 둘 중 하나만 반영되고,
     * 반영 안 된 쪽은 데이터를 조용히 망가뜨리는 대신 안전하게 실패한다(호출자가 재시도하면
     * 그 시점의 최신 상태를 다시 판단).
     */
    @Version
    private Long version;

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
     * 탈퇴 상태이면서 아직 유예기간(30일) 안인지 — 로그인 시 복구 가능 여부 판정에 쓴다.
     * 익명화 스케줄러의 실행 시점(매일 새벽 1회)과 무관하게 항상 deletedAt을 기준으로
     * 직접 판단한다 — 스케줄러가 지연되거나 배치 적체로 아직 이 회원까지 처리하지 못한
     * 경우에도, 유예기간이 실제로 지났다면 복구를 허용하지 않기 위함이다("익명화가
     * 아직 안 됐으니 원래 이메일로 조회된다"는 사실에만 기대면 안 된다).
     */
    public boolean isRestorable() {
        return isDeleted() && getDeletedAt().isAfter(LocalDateTime.now().minus(WITHDRAWAL_GRACE_PERIOD));
    }

    /**
     * 익명화 스케줄러가 배치 조회 이후 항목별로 재확인할 때 쓴다. 배치 조회 시점과 실제 처리
     * 시점 사이에 상태가 바뀔 수 있어(예: 유예기간 중 복구 후 재탈퇴로 deletedAt이 갱신됨)
     * isDeleted() 하나만으로는 부족하고, 원래 조회 조건(deletedAt &lt;= cutoff, 아직 미익명화)을
     * 전부 다시 검증해야 한다.
     */
    public boolean isEligibleForAnonymization(LocalDateTime cutoff) {
        return isDeleted() && anonymizedAt == null && !getDeletedAt().isAfter(cutoff);
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

package com.openat.member.domain.repository;

import com.openat.member.domain.model.Member;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemberRepository {

    Member save(Member member);

    Optional<Member> findById(UUID id);

    /** 탈퇴(논리적 삭제) 여부와 무관하게 id로 조회. 익명화 스케줄러가 배치 항목을 재조회할 때 사용. */
    Optional<Member> findByIdIncludingDeleted(UUID id);

    Optional<Member> findByEmail(String email);

    /** 탈퇴(논리적 삭제) 여부와 무관하게 전체에서 조회. 로그인 시 탈퇴 계정 판별용. */
    Optional<Member> findByEmailIncludingDeleted(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    /**
     * 탈퇴 유예기간이 지났지만(deletedAt &lt;= cutoff) 아직 익명화되지 않은(anonymizedAt IS NULL)
     * 회원을 오래된 순으로 최대 limit건 조회한다. 익명화 스케줄러 전용.
     */
    List<Member> findWithdrawnBefore(LocalDateTime cutoff, int limit);

    /**
     * 아직 유예기간 안(deletedAt이 cutoff보다 늦음)이면 deletedAt을 원자적으로 해제한다.
     * 조회 후 별도로 저장하는 대신 조건과 갱신을 한 SQL 문으로 묶어, 이 회원 행을 동시에
     * 건드릴 수 있는 익명화 스케줄러와의 lost-update를 막는다(둘 중 DB에 먼저 반영되는 쪽만
     * 성공하고, 나머지는 조용히 0건으로 끝난다 — 스냅샷을 그대로 덮어써 상대 변경을 지우는
     * 일이 없다).
     *
     * @return 실제로 갱신된 행 수(0 또는 1). 0이면 이미 활성 상태이거나, 유예기간이 지났거나,
     *     그 사이 다른 트랜잭션이 먼저 익명화했다는 뜻 — 호출자가 그 경우를 "복구 불가"로 처리한다.
     */
    int restoreIfWithinGracePeriod(UUID id, LocalDateTime cutoff);

    /**
     * 조회 조건(deletedAt &lt;= cutoff, 아직 미익명화)을 UPDATE 시점에 원자적으로 재검증하며
     * email/nickname을 치환한다. restoreIfWithinGracePeriod와 마찬가지로 조회와 갱신을 한 SQL
     * 문으로 묶어 복구와의 lost-update를 막는다.
     *
     * @return 실제로 갱신됐으면 1, 조건에 안 맞아(이미 복구됨·이미 익명화됨) 스킵됐으면 0.
     */
    int anonymizeIfEligible(UUID id, LocalDateTime cutoff, LocalDateTime now);
}

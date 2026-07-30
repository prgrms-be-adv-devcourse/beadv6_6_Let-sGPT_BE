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
}

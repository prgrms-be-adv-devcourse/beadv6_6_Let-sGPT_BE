package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.Member;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MemberJpaRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByIdAndDeletedAtIsNull(UUID id);

    Optional<Member> findByEmailAndDeletedAtIsNull(String email);

    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    // 익명화 스케줄러용 — Pageable로 상한을 강제해 적체 시에도 한 tick이 무한정 길어지지 않게 한다.
    List<Member> findByDeletedAtLessThanEqualAndAnonymizedAtIsNullOrderByDeletedAtAsc(
            LocalDateTime cutoff, Pageable pageable);

    // 조회 없이 조건과 갱신을 한 문장으로 묶은 원자적 복구. WHERE 절이 UPDATE 시점의 DB 현재
    // 상태를 직접 재검증하므로, 이 순간 다른 트랜잭션(익명화)이 이미 처리해버렸다면(deletedAt이
    // null이 됐거나 cutoff보다 이전으로 남아있지 않다면) 0건으로 끝나고 조용히 스킵된다.
    // anonymizedAt is null도 명시적으로 같이 검사한다 — deletedAt/cutoff 비교만으로는 두
    // 트랜잭션이 서로 다른 시점에 계산한 cutoff를 쓸 때(예: 오래 걸리는 배치가 한참 전에 계산한
    // cutoff로 뒤늦게 처리) 이미 익명화된 행의 deletedAt을 복구가 그대로 지워버릴 여지가 아주
    // 좁게 남는데, anonymizedAt 체크를 추가하면 "이미 익명화됐으면 복구 자체를 무조건 거부"가
    // 되어 그 여지 자체가 없어진다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Member m set m.deletedAt = null "
            + "where m.id = :id and m.deletedAt is not null and m.deletedAt > :cutoff "
            + "and m.anonymizedAt is null")
    int restoreIfWithinGracePeriod(@Param("id") UUID id, @Param("cutoff") LocalDateTime cutoff);

    // email/nickname 치환까지 SQL 안에서 직접 수행한다(엔티티를 조회해 메모리에서 바꾸고
    // 저장하는 방식이 아님) — 그래야 "조건 확인"과 "갱신"이 한 원자적 문장이 되어 복구와의
    // lost-update를 막을 수 있다. id를 문자열로 이어붙여야 해서(JPQL은 이런 문자열 조합을
    // 안정적으로 지원하지 않음) native query로 작성했다 — 이 프로젝트는 Postgres 전용이라
    // 이식성 문제는 없다. 리터럴 'deleted_'는 Member.ANONYMIZED_PREFIX와 값이 같아야 한다.
    // 테이블명은 member.member로 스키마를 명시한다 — application.yml의 hibernate.default_schema
    // 는 Hibernate가 생성하는 SQL(엔티티 매핑·JPQL)에만 적용되고, 이렇게 직접 쓴 native SQL은
    // 그 설정을 타지 않아 스키마 없이 "member"만 쓰면 (search_path에 없는 한) 테이블을 못 찾는다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE member.member "
            + "SET email = 'deleted_' || id::text || '_' || email, "
            + "    nickname = 'deleted_' || id::text, "
            + "    anonymized_at = :now "
            + "WHERE id = :id AND deleted_at IS NOT NULL AND deleted_at <= :cutoff AND anonymized_at IS NULL",
            nativeQuery = true)
    int anonymizeIfEligible(@Param("id") UUID id, @Param("cutoff") LocalDateTime cutoff,
            @Param("now") LocalDateTime now);
}

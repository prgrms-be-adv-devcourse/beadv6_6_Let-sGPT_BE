package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.Member;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberJpaRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByIdAndDeletedAtIsNull(UUID id);

    Optional<Member> findByEmailAndDeletedAtIsNull(String email);

    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    // 익명화 스케줄러용 — Pageable로 상한을 강제해 적체 시에도 한 tick이 무한정 길어지지 않게 한다.
    List<Member> findByDeletedAtLessThanEqualAndAnonymizedAtIsNullOrderByDeletedAtAsc(
            LocalDateTime cutoff, Pageable pageable);
}

package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.Member;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberJpaRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByIdAndDeletedAtIsNull(UUID id);

    Optional<Member> findByEmailAndDeletedAtIsNull(String email);

    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);
}

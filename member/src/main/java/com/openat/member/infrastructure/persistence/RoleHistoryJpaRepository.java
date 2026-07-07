package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.Role;
import com.openat.member.domain.model.RoleHistory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleHistoryJpaRepository extends JpaRepository<RoleHistory, Long> {

    /**
     * deleted_at IS NULL 중 created_at 내림차순 첫 번째 → 현재 유효 역할.
     * Spring Data JPA의 findTop 키워드로 LIMIT 1 자동 적용.
     */
    Optional<RoleHistory> findTopByMember_IdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID memberId);

    /**
     * 특정 member의 특정 role이 활성 상태인 이력 조회.
     * 역할 회수(revoke) 대상 확인 및 중복 부여 방지용.
     */
    Optional<RoleHistory> findByMember_IdAndRoleEntity_RoleAndDeletedAtIsNull(UUID memberId, Role role);
}

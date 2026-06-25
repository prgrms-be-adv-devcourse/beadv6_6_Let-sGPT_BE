package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.Role;
import com.openat.member.domain.model.RoleHistory;
import com.openat.member.domain.repository.RoleHistoryRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RoleHistoryRepositoryAdaptor implements RoleHistoryRepository {

    private final RoleHistoryJpaRepository roleHistoryJpaRepository;

    @Override
    public RoleHistory save(RoleHistory roleHistory) {
        return roleHistoryJpaRepository.save(roleHistory);
    }

    @Override
    public Optional<RoleHistory> findCurrentByMemberId(UUID memberId) {
        return roleHistoryJpaRepository
                .findTopByMember_IdAndDeletedAtIsNullOrderByCreatedAtDesc(memberId);
    }

    @Override
    public Optional<RoleHistory> findActiveByMemberIdAndRole(UUID memberId, Role role) {
        return roleHistoryJpaRepository
                .findByMember_IdAndRoleEntity_RoleAndDeletedAtIsNull(memberId, role);
    }
}

package com.openat.member.domain.repository;

import com.openat.member.domain.model.Role;
import com.openat.member.domain.model.RoleHistory;
import java.util.Optional;
import java.util.UUID;

public interface RoleHistoryRepository {

    RoleHistory save(RoleHistory roleHistory);

    Optional<RoleHistory> findCurrentByMemberId(UUID memberId);

    Optional<RoleHistory> findActiveByMemberIdAndRole(UUID memberId, Role role);
}

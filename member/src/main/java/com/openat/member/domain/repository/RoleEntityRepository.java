package com.openat.member.domain.repository;

import com.openat.member.domain.model.Role;
import com.openat.member.domain.model.RoleEntity;
import java.util.Optional;

public interface RoleEntityRepository {

    Optional<RoleEntity> findByRole(Role role);
}

package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.Role;
import com.openat.member.domain.model.RoleEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleEntityJpaRepository extends JpaRepository<RoleEntity, Long> {

    Optional<RoleEntity> findByRole(Role role);
}

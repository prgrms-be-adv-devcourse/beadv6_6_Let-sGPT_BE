package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.Role;
import com.openat.member.domain.model.RoleEntity;
import com.openat.member.domain.repository.RoleEntityRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RoleEntityRepositoryAdaptor implements RoleEntityRepository {

    private final RoleEntityJpaRepository roleEntityJpaRepository;

    @Override
    public Optional<RoleEntity> findByRole(Role role) {
        return roleEntityJpaRepository.findByRole(role);
    }
}

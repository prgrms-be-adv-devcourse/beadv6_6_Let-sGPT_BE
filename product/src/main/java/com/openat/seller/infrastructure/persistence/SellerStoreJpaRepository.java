package com.openat.seller.infrastructure.persistence;

import com.openat.seller.domain.model.SellerStore;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerStoreJpaRepository extends JpaRepository<SellerStore, UUID> {}

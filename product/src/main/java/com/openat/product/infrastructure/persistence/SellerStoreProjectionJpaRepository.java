package com.openat.product.infrastructure.persistence;

import com.openat.product.domain.model.SellerStoreProjection;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerStoreProjectionJpaRepository
    extends JpaRepository<SellerStoreProjection, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select projection from SellerStoreProjection projection"
          + " where projection.sellerInfoId = :sellerInfoId")
  Optional<SellerStoreProjection> findByIdForUpdate(
      @Param("sellerInfoId") UUID sellerInfoId);
}

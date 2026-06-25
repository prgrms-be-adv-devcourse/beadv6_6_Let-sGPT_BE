package com.openat.payment.infrastructure.persistence;

import com.openat.payment.infrastructure.persistence.entity.WalletJpaEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletJpaRepository extends JpaRepository<WalletJpaEntity, UUID> {

    Optional<WalletJpaEntity> findByMemberId(UUID memberId);

    // 잔액 차감(#8) — affected rows=0이면 잔액부족, SELECT-then-UPDATE 없이 단일 조건부 UPDATE로 원자처리.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE WalletJpaEntity w SET w.balance = w.balance - :amount "
            + "WHERE w.id = :id AND w.balance >= :amount")
    int tryDeduct(@Param("id") UUID id, @Param("amount") Long amount);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE WalletJpaEntity w SET w.balance = w.balance + :amount WHERE w.id = :id")
    int charge(@Param("id") UUID id, @Param("amount") Long amount);
}

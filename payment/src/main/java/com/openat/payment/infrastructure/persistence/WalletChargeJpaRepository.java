package com.openat.payment.infrastructure.persistence;

import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.infrastructure.persistence.entity.WalletChargeJpaEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletChargeJpaRepository extends JpaRepository<WalletChargeJpaEntity, UUID> {

    Optional<WalletChargeJpaEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<WalletChargeJpaEntity> findByPgPaymentKeyHash(String pgPaymentKeyHash);

    // confirm 요청으로 전달받은 값 반영 — pgPaymentKey는 암호화 컬럼이라 평문 해시(pgPaymentKeyHash)를 같이 저장해 그걸로 조회.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE WalletChargeJpaEntity c SET c.pgPaymentKey = :pgPaymentKey, c.pgPaymentKeyHash = :pgPaymentKeyHash "
            + "WHERE c.id = :id")
    void updatePgPaymentKey(@Param("id") UUID id, @Param("pgPaymentKey") String pgPaymentKey,
            @Param("pgPaymentKeyHash") String pgPaymentKeyHash);

    // confirm/웹훅 처리(#10과 동일 원칙) — WHERE status='PENDING' 조건부 UPDATE.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE WalletChargeJpaEntity c SET c.status = :newStatus, c.pgTxId = :pgTxId "
            + "WHERE c.id = :id AND c.status = 'PENDING'")
    int tryTransitionFromPending(@Param("id") UUID id, @Param("newStatus") WalletCharge.Status newStatus,
            @Param("pgTxId") String pgTxId);

    // TTL 스캐너 — PENDING 상태이고 threshold 이전에 생성된 row 조회.
    @Query("SELECT c FROM WalletChargeJpaEntity c WHERE c.status = 'PENDING' AND c.createdAt < :threshold")
    List<WalletChargeJpaEntity> findStalePending(@Param("threshold") LocalDateTime threshold);
}

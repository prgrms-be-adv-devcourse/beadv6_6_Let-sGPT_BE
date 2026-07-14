package com.openat.payment.infrastructure.persistence;

import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.PgReconStatus;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.infrastructure.persistence.entity.PaymentJpaEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class PaymentRepositoryAdaptor implements PaymentRepository {

    private final PaymentJpaRepository paymentJpaRepository;

    public PaymentRepositoryAdaptor(PaymentJpaRepository paymentJpaRepository) {
        this.paymentJpaRepository = paymentJpaRepository;
    }

    @Override
    public Payment save(Payment payment) {
        PaymentJpaEntity saved = paymentJpaRepository.save(PaymentJpaEntity.fromDomain(payment));
        return saved.toDomain();
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return paymentJpaRepository.findById(id).map(PaymentJpaEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        return paymentJpaRepository.findByIdempotencyKey(idempotencyKey).map(PaymentJpaEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByOrderIdAndStatus(UUID orderId, Payment.Status status) {
        return paymentJpaRepository.findFirstByOrderIdAndStatusOrderByCreatedAtDesc(orderId, status).map(PaymentJpaEntity::toDomain);
    }

    @Override
    public int tryFillSellerAndProduct(UUID orderId, UUID sellerId, UUID productId) {
        return paymentJpaRepository.tryFillSellerAndProduct(orderId, sellerId, productId);
    }

    @Override
    public Optional<Payment> findByPgPaymentKey(String pgPaymentKey) {
        // pgPaymentKey 컬럼은 암호화(비결정적 IV)되어 등호조회 불가 — 평문 해시(결정적)로 조회.
        return paymentJpaRepository.findByPgPaymentKeyHash(RequestHasher.hash(pgPaymentKey))
                .map(PaymentJpaEntity::toDomain);
    }

    @Override
    public int tryTransitionFromPending(UUID id, Payment.Status newStatus, String pgTxId,
            LocalDateTime approvedAt) {
        return paymentJpaRepository.tryTransitionFromPending(id, newStatus, pgTxId, approvedAt);
    }

    @Override
    public Optional<Payment> tryReserveForConfirm(Payment pending) {
        try {
            // saveAndFlush 필수 — order_id 유니크 충돌을 이 지점에서 즉시 감지(지연 flush면 catch가 못 잡음).
            return Optional.of(paymentJpaRepository.saveAndFlush(PaymentJpaEntity.fromDomain(pending)).toDomain());
        } catch (DataIntegrityViolationException e) {
            return Optional.empty(); // order_id 충돌 — 기존 행 존재, 호출측이 findByOrderId로 재조회
        }
    }

    @Override
    public Optional<Payment> findByOrderId(UUID orderId) {
        return paymentJpaRepository.findByOrderId(orderId).map(PaymentJpaEntity::toDomain);
    }

    @Override
    public List<Payment> findStalePending(LocalDateTime threshold) {
        return paymentJpaRepository.findByStatusAndCreatedAtBefore(Payment.Status.PAYMENT_PENDING, threshold)
                .stream().map(PaymentJpaEntity::toDomain).toList();
    }

    @Override
    public int tryIncreaseRefundedAmount(UUID paymentId, Long amount) {
        return paymentJpaRepository.tryIncreaseRefundedAmount(paymentId, amount);
    }

    @Override
    public int tryDecreaseRefundedAmount(UUID paymentId, Long amount) {
        return paymentJpaRepository.tryDecreaseRefundedAmount(paymentId, amount);
    }

    @Override
    public List<Payment> findForPgReconciliation(LocalDateTime from, LocalDateTime to) {
        return paymentJpaRepository.findByStatusAndPgReconStatusNotAndApprovedAtBetween(
                        Payment.Status.APPROVED, PgReconStatus.MATCHED, from, to)
                .stream().map(PaymentJpaEntity::toDomain).toList();
    }

    @Override
    public int markPgReconResult(UUID paymentId, PgReconStatus pgReconStatus, LocalDateTime reconciledAt) {
        return paymentJpaRepository.markPgReconResult(paymentId, pgReconStatus, reconciledAt);
    }

    @Override
    public List<Payment> findMatchedApprovedBetween(LocalDateTime from, LocalDateTime to) {
        return paymentJpaRepository.findByStatusAndApprovedAtGreaterThanEqualAndApprovedAtLessThanAndPgReconStatus(
                        Payment.Status.APPROVED, from, to, PgReconStatus.MATCHED)
                .stream().map(PaymentJpaEntity::toDomain).toList();
    }

    @Override
    public List<Payment> findAllByIds(List<UUID> ids) {
        return paymentJpaRepository.findByIdIn(ids).stream().map(PaymentJpaEntity::toDomain).toList();
    }
}

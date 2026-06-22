package com.openat.payment.infrastructure.persistence;

import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.infrastructure.persistence.entity.PaymentJpaEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
        return paymentJpaRepository.findByOrderIdAndStatus(orderId, status).map(PaymentJpaEntity::toDomain);
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
    public void updatePgPaymentKey(UUID id, String pgPaymentKey) {
        paymentJpaRepository.updatePgPaymentKey(id, pgPaymentKey, RequestHasher.hash(pgPaymentKey));
    }

    @Override
    public List<Payment> findStalePending(LocalDateTime threshold) {
        return paymentJpaRepository.findByStatusAndCreatedAtBefore(Payment.Status.PAYMENT_PENDING, threshold)
                .stream().map(PaymentJpaEntity::toDomain).toList();
    }
}

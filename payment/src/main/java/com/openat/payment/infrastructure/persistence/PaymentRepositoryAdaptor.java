package com.openat.payment.infrastructure.persistence;

import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.PgReconStatus;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.ScanCursor;
import com.openat.payment.infrastructure.persistence.entity.PaymentJpaEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    public List<Payment> findStalePending(LocalDateTime threshold, ScanCursor cursor, int limit) {
        // 오래된 순 + 상한 — 정체가 쌓여도 한 사이클이 유한 시간에 끝나고, 가장 오래 굳은 건부터 회수된다.
        // 첫 페이지(cursor null)는 커서 조건 없는 쿼리, 후속 페이지는 (createdAt, id) 복합 커서 쿼리로 분리한다 —
        // 단일 쿼리 + null 커서 바인드는 Postgres 42P18(파라미터 타입추론 불가)로 매 사이클 실패한다.
        // 복합 커서·복합 정렬로 동일 시각 행이 페이지 경계에 몰려도 건너뛰지 않고, 종결불가 행이 선두를
        // 점유해도 그 뒤 행에 도달할 수 있게 한다.
        PageRequest page = PageRequest.of(0, limit,
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
        List<PaymentJpaEntity> rows = cursor == null
                ? paymentJpaRepository.findStalePendingFirst(threshold, page)
                : paymentJpaRepository.findStalePendingAfter(threshold, cursor.createdAt(), cursor.id(), page);
        return rows.stream().map(PaymentJpaEntity::toDomain).toList();
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

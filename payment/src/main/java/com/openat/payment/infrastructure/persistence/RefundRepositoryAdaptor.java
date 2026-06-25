package com.openat.payment.infrastructure.persistence;

import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.repository.RefundRepository;
import com.openat.payment.infrastructure.persistence.entity.RefundJpaEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
public class RefundRepositoryAdaptor implements RefundRepository {

    private final RefundJpaRepository refundJpaRepository;

    public RefundRepositoryAdaptor(RefundJpaRepository refundJpaRepository) {
        this.refundJpaRepository = refundJpaRepository;
    }

    @Override
    public Refund save(Refund refund) {
        RefundJpaEntity saved = refundJpaRepository.save(RefundJpaEntity.fromDomain(refund));
        // RefundService.requestRefund()는 save() 직후 같은 트랜잭션에서 tryTransitionFromPending()(벌크 UPDATE)을
        // 바로 호출한다 — merge() 기반 INSERT는 기본적으로 flush가 지연되는데, @Modifying(flushAutomatically=true)만으로는
        // 이 INSERT가 그 UPDATE 전에 반영되지 않는 게 실제로 확인돼서(0 rows affected), 여기서 명시적으로 flush한다.
        refundJpaRepository.flush();
        return saved.toDomain();
    }

    @Override
    public Optional<Refund> findById(UUID id) {
        return refundJpaRepository.findById(id).map(RefundJpaEntity::toDomain);
    }

    @Override
    public Optional<Refund> findByIdempotencyKey(String idempotencyKey) {
        return refundJpaRepository.findByIdempotencyKey(idempotencyKey).map(RefundJpaEntity::toDomain);
    }

    @Override
    public List<Refund> findByPaymentIdAndStatus(UUID paymentId, Refund.Status status) {
        return refundJpaRepository.findByPaymentIdAndStatus(paymentId, status)
                .stream().map(RefundJpaEntity::toDomain).toList();
    }

    @Override
    public int tryTransitionFromPending(UUID id, Refund.Status newStatus, String pgRefundKey,
            LocalDateTime completedAt) {
        return refundJpaRepository.tryTransitionFromPending(id, newStatus, pgRefundKey, completedAt);
    }

    @Override
    public List<Refund> findByMemberId(UUID memberId, int page, int size) {
        return refundJpaRepository.findByMemberId(memberId, PageRequest.of(page, size))
                .stream().map(RefundJpaEntity::toDomain).toList();
    }

    @Override
    public long countByMemberId(UUID memberId) {
        return refundJpaRepository.countByMemberId(memberId);
    }
}

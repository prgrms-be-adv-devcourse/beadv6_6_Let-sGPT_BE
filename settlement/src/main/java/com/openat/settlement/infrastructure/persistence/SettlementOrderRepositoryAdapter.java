package com.openat.settlement.infrastructure.persistence;

import com.openat.settlement.domain.model.SettlementOrder;
import com.openat.settlement.domain.model.SettlementOrderAmount;
import com.openat.settlement.domain.model.SettlementOrderStatus;
import com.openat.settlement.domain.repository.SettlementOrderRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Repository;

/** domain.repository.SettlementOrderRepository 계약을 JPA로 구현하는 Adapter입니다. */
@Repository
@RequiredArgsConstructor
public class SettlementOrderRepositoryAdapter implements SettlementOrderRepository {

  private final SettlementOrderJpaRepository jpaRepository;

  @Override
  public Optional<SettlementOrder> findByOrderId(UUID orderId) {
    return jpaRepository.findByOrderId(orderId);
  }

  @Override
  public List<UUID> findReadySellerIds(String settlementMonth) {
    return jpaRepository.findDistinctSellerIdsBySettlementMonthAndSettlementStatus(
        settlementMonth, SettlementOrderStatus.READY);
  }

  @Override
  public List<SettlementOrder> findReadyOrders(UUID sellerId, String settlementMonth) {
    return jpaRepository.findBySellerIdAndSettlementMonthAndSettlementStatus(
        sellerId, settlementMonth, SettlementOrderStatus.READY);
  }

  @Override
  public Slice<SettlementOrderAmount> findReadyOrderAmounts(
      UUID sellerId, String settlementMonth, Pageable pageable) {
    return jpaRepository.findAmountsBySellerIdAndSettlementMonthAndSettlementStatus(
        sellerId, settlementMonth, SettlementOrderStatus.READY, pageable);
  }

  @Override
  public int completeReadyOrders(
      UUID sellerId, String settlementMonth, UUID sellerSettlementId, int feeRatePercent) {
    return jpaRepository.completeBySellerIdAndSettlementMonthAndSettlementStatus(
        sellerId,
        settlementMonth,
        sellerSettlementId,
        feeRatePercent,
        SettlementOrderStatus.READY,
        SettlementOrderStatus.COMPLETED);
  }

  @Override
  public Page<SettlementOrder> findAllByConditions(
      String settlementMonth,
      SettlementOrderStatus status,
      UUID sellerId,
      UUID orderId,
      Pageable pageable) {
    return jpaRepository.findAllByConditions(settlementMonth, status, sellerId, orderId, pageable);
  }

  @Override
  public SettlementOrder save(SettlementOrder order) {
    return jpaRepository.save(order);
  }
}

package com.openat.settlement.domain.repository;

import com.openat.settlement.domain.model.SettlementOrder;
import com.openat.settlement.domain.model.SettlementOrderAmount;
import com.openat.settlement.domain.model.SettlementOrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

/**
 * 정산 주문 저장소 계약입니다.
 *
 * <p>이 인터페이스는 JPA를 모르는 순수 Repository 계약입니다. 실제 DB 접근은 infrastructure.persistence의
 * RepositoryAdapter가 구현합니다.
 */
public interface SettlementOrderRepository {

  Optional<SettlementOrder> findByOrderId(UUID orderId);

  List<UUID> findReadySellerIds(String settlementMonth);

  List<SettlementOrder> findReadyOrders(UUID sellerId, String settlementMonth);

  Slice<SettlementOrderAmount> findReadyOrderAmounts(
      UUID sellerId, String settlementMonth, Pageable pageable);

  int completeReadyOrders(
      UUID sellerId, String settlementMonth, UUID sellerSettlementId, int feeRatePercent);

  Page<SettlementOrder> findAllByConditions(
      String settlementMonth,
      SettlementOrderStatus status,
      UUID sellerId,
      UUID orderId,
      Pageable pageable);

  SettlementOrder save(SettlementOrder order);
}

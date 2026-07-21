package com.openat.settlement.infrastructure.persistence;

import com.openat.settlement.domain.model.SettlementOrder;
import com.openat.settlement.domain.model.SettlementOrderAmount;
import com.openat.settlement.domain.model.SettlementOrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA 전용 Repository입니다.
 *
 * <p>이 파일은 infrastructure.persistence에 둡니다. domain.repository에는 JPA 의존성을 두지 않습니다.
 */
public interface SettlementOrderJpaRepository extends JpaRepository<SettlementOrder, UUID> {

  Optional<SettlementOrder> findByOrderId(UUID orderId);

  @Query(
      """
            select distinct o.sellerId
            from SettlementOrder o
            where o.settlementMonth = :settlementMonth
              and o.settlementStatus = :settlementStatus
            """)
  List<UUID> findDistinctSellerIdsBySettlementMonthAndSettlementStatus(
      String settlementMonth, SettlementOrderStatus settlementStatus);

  List<SettlementOrder> findBySellerIdAndSettlementMonthAndSettlementStatus(
      UUID sellerId, String settlementMonth, SettlementOrderStatus settlementStatus);

  @Query(
      """
            select new com.openat.settlement.domain.model.SettlementOrderAmount(
                o.paidAmount,
                o.feeAmount,
                o.refundAmount
            )
            from SettlementOrder o
            where o.sellerId = :sellerId
              and o.settlementMonth = :settlementMonth
              and o.settlementStatus = :settlementStatus
            order by o.paidAt asc, o.id asc
            """)
  Slice<SettlementOrderAmount> findAmountsBySellerIdAndSettlementMonthAndSettlementStatus(
      UUID sellerId,
      String settlementMonth,
      SettlementOrderStatus settlementStatus,
      Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
            update SettlementOrder o
            set o.feeAmount = (o.paidAmount * :feeRatePercent) / 100,
                o.netSettlementAmount = o.paidAmount
                    - ((o.paidAmount * :feeRatePercent) / 100)
                    - o.refundAmount,
                o.sellerSettlementId = :sellerSettlementId,
                o.settlementStatus = :completedStatus
            where o.sellerId = :sellerId
              and o.settlementMonth = :settlementMonth
              and o.settlementStatus = :readyStatus
            """)
  int completeBySellerIdAndSettlementMonthAndSettlementStatus(
      UUID sellerId,
      String settlementMonth,
      UUID sellerSettlementId,
      int feeRatePercent,
      SettlementOrderStatus readyStatus,
      SettlementOrderStatus completedStatus);

  @Query(
      """
            select o
            from SettlementOrder o
            where (:settlementMonth is null or o.settlementMonth = :settlementMonth)
              and (:settlementStatus is null or o.settlementStatus = :settlementStatus)
              and (:sellerId is null or o.sellerId = :sellerId)
              and (:orderId is null or o.orderId = :orderId)
            order by o.paidAt desc
            """)
  Page<SettlementOrder> findAllByConditions(
      String settlementMonth,
      SettlementOrderStatus settlementStatus,
      UUID sellerId,
      UUID orderId,
      Pageable pageable);
}

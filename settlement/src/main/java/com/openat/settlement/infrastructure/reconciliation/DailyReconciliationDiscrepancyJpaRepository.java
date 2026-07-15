package com.openat.settlement.infrastructure.reconciliation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyReconciliationDiscrepancyJpaRepository extends JpaRepository<DailyReconciliationDiscrepancyJpaEntity, UUID> {

    List<DailyReconciliationDiscrepancyJpaEntity> findByBusinessDate(LocalDate businessDate);

    // 같은 businessDate 재실행 시 이전 불일치를 지우고 다시 채운다(재-pull 멱등성, WS-0.5).
    void deleteByBusinessDate(LocalDate businessDate);
}

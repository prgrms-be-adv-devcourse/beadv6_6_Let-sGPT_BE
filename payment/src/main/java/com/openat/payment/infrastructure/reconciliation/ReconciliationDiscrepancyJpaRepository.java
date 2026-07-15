package com.openat.payment.infrastructure.reconciliation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationDiscrepancyJpaRepository extends JpaRepository<ReconciliationDiscrepancyJpaEntity, UUID> {

    List<ReconciliationDiscrepancyJpaEntity> findByBusinessDate(LocalDate businessDate);
}

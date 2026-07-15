package com.openat.settlement.infrastructure.reconciliation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyReconciliationResultJpaRepository
    extends JpaRepository<DailyReconciliationResultJpaEntity, UUID> {

  Optional<DailyReconciliationResultJpaEntity> findByBusinessDate(LocalDate businessDate);

  List<DailyReconciliationResultJpaEntity> findAllByBusinessDateBetweenOrderByBusinessDateAsc(
      LocalDate from, LocalDate to);
}

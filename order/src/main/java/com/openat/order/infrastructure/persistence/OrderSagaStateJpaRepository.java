package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.OrderSagaState;
import com.openat.order.domain.model.OrderSagaStep;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderSagaStateJpaRepository extends JpaRepository<OrderSagaState, UUID> {

  Optional<OrderSagaState> findByOrderId(UUID orderId);

  @Modifying(flushAutomatically = true)
  @Query(
      """
      UPDATE OrderSagaState s
      SET s.currentStep = :compensatingStep,
          s.compensatingSince = COALESCE(s.compensatingSince, :now)
      WHERE s.orderId = :orderId
        AND s.currentStep <> :completedStep
      """)
  int enterCompensatingUnlessCompleted(
      @Param("orderId") UUID orderId,
      @Param("now") Instant now,
      @Param("compensatingStep") OrderSagaStep compensatingStep,
      @Param("completedStep") OrderSagaStep completedStep);

  List<OrderSagaState> findByCurrentStepAndCompensatingSinceBefore(
      OrderSagaStep currentStep, Instant cutoff);

  List<OrderSagaState> findByCurrentStep(OrderSagaStep currentStep);
}

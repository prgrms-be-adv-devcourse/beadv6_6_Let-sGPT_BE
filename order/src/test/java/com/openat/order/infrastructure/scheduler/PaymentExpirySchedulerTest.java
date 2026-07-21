package com.openat.order.infrastructure.scheduler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.application.service.PaymentExpiryService;
import com.openat.order.domain.repository.OrderRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class PaymentExpirySchedulerTest {

  @Mock OrderRepository orderRepository;
  @Mock PaymentExpiryService paymentExpiryService;
  @InjectMocks PaymentExpiryScheduler scheduler;

  @Test
  void should_skip_optimistic_lock_conflict_and_continue_batch() {
    UUID conflicted = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    when(orderRepository.findExpiredPaymentPendingIds(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(conflicted, next));
    org.mockito.Mockito.doThrow(new OptimisticLockingFailureException("conflict"))
        .when(paymentExpiryService)
        .process(conflicted);

    scheduler.expirePendingOrders();

    verify(paymentExpiryService).process(conflicted);
    verify(paymentExpiryService).process(next);
  }
}

package com.openat.order.infrastructure.scheduler;

import com.openat.order.application.service.PaymentExpiryService;
import com.openat.order.domain.repository.OrderRepository;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentExpiryScheduler {

  private static final int BATCH_SIZE = 100;

  private final OrderRepository orderRepository;
  private final PaymentExpiryService paymentExpiryService;

  @Scheduled(fixedDelay = 30_000)
  public void expirePendingOrders() {
    orderRepository
        .findExpiredPaymentPendingIds(Instant.now(), PageRequest.of(0, BATCH_SIZE))
        .forEach(this::processSafely);
  }

  private void processSafely(UUID orderId) {
    try {
      paymentExpiryService.process(orderId);
    } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
      log.info("Payment expiry transition lost optimistic lock; skipping. orderId={}", orderId);
    } catch (RuntimeException exception) {
      log.error(
          "Payment expiry processing failed; continuing batch. orderId={}", orderId, exception);
    }
  }
}

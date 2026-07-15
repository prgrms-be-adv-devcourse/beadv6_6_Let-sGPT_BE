package com.openat.payment.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openat.common.auth.UserContext;
import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.payment.application.dto.PaymentResult;
import com.openat.payment.application.usecase.PaymentUseCase;
import com.openat.payment.presentation.dto.PaymentRequest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

// 순수 Mockito 단위테스트 — 7-13 plan D1(WALLET 전용 축소)·§7 item8 검증.
class PaymentControllerTest {

  private final PaymentUseCase paymentUseCase = mock(PaymentUseCase.class);
  private final PaymentController controller = new PaymentController(paymentUseCase);

  private final UserContext userContext = new UserContext(UUID.randomUUID().toString(), Set.of());

  @Test
  void create에_PG를_보내면_400() {
    PaymentRequest request = new PaymentRequest(UUID.randomUUID(), 10_000L, "PG");

    assertThatThrownBy(() -> controller.create(userContext, "idem-1", request))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.INVALID_INPUT);

    verifyNoInteractions(paymentUseCase);
  }

  @Test
  void create에_WALLET을_보내면_payWithWallet으로_위임한다() {
    PaymentRequest request = new PaymentRequest(UUID.randomUUID(), 10_000L, "WALLET");
    UUID paymentId = UUID.randomUUID();
    when(paymentUseCase.payWithWallet(any()))
        .thenReturn(PaymentResult.of(paymentId, "APPROVED", 10_000L));

    ResponseEntity<?> response = controller.create(userContext, "idem-1", request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
  }
}

package com.openat.payment.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.auth.UserContext;
import com.openat.payment.application.dto.RefundResult;
import com.openat.payment.application.usecase.RefundUseCase;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

// 순수 Mockito 단위테스트 — 단건 조회가 인증된 회원 ID를 유스케이스로 넘기는지만 검증(IDOR 회귀 방지).
// 소유자 검증 자체의 분기는 RefundServiceTest.
class RefundControllerTest {

  private final RefundUseCase refundUseCase = mock(RefundUseCase.class);
  private final RefundController controller = new RefundController(refundUseCase);

  private final UserContext userContext = new UserContext(UUID.randomUUID().toString(), Set.of());

  @Test
  void get은_인증된_회원_ID를_함께_넘겨_조회한다() {
    UUID refundId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    when(refundUseCase.getRefund(any(), any()))
        .thenReturn(new RefundResult(refundId, paymentId, 3_000L, "COMPLETE"));

    ResponseEntity<?> response = controller.get(userContext, refundId);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    verify(refundUseCase).getRefund(refundId, UUID.fromString(userContext.userId()));
  }
}

package com.openat.payment.application.dto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.exception.PaymentErrorCode;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

// 순수 단위테스트(7-12 plan WS-F §4.3) — 음수/0/null 금액 커맨드는 생성 시점에 INVALID_AMOUNT로 막힌다.
// tryDeduct의 balance>=:amount 조건절이 음수 amount에 항상 참이 되어 잔액이 증가할 수 있었던 결함(§4.3)의 회귀방지.
// PayWithPgCommand는 7-13 plan D1로 제거(PG는 confirm 단일 진입점) — PgConfirmCommand는 D4로 idempotencyKey 필드 제거.
class CommandAmountValidationTest {

  private final UUID id = UUID.randomUUID();

  @Test
  void 커맨드_6종_모두_금액이_0이거나_음수이거나_null이면_INVALID_AMOUNT_예외가_발생한다() {
    for (Long amount : new Long[] {0L, -1L, null}) {
      assertInvalidAmount(() -> new PayWithWalletCommand(id, id, amount, "idem"));
      assertInvalidAmount(() -> new PgConfirmCommand(id, id, amount, "pk"));
      assertInvalidAmount(() -> new RefundCommand(id, id, amount, "reason", "idem"));
      assertInvalidAmount(() -> new ChargeWalletCommand(id, amount, "idem"));
      assertInvalidAmount(() -> new ChargePgCommand(id, amount, "idem"));
      assertInvalidAmount(() -> new ChargeConfirmCommand(id, id, amount, "pk", "idem"));
    }
  }

  @Test
  void 양수_금액은_정상_생성된다() {
    new PayWithWalletCommand(id, id, 1L, "idem");
    new PgConfirmCommand(id, id, 1L, "pk");
    new RefundCommand(id, id, 1L, "reason", "idem");
    new ChargeWalletCommand(id, 1L, "idem");
    new ChargePgCommand(id, 1L, "idem");
    new ChargeConfirmCommand(id, id, 1L, "pk", "idem");
  }

  private void assertInvalidAmount(ThrowingCallable callable) {
    assertThatThrownBy(callable)
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.INVALID_AMOUNT);
  }
}

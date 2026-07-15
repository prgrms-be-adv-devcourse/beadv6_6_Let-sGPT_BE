package com.openat.payment.application.usecase;

import com.openat.payment.application.dto.PayWithWalletCommand;
import com.openat.payment.application.dto.PaymentResult;
import com.openat.payment.application.dto.PgConfirmCommand;
import java.util.UUID;

public interface PaymentUseCase {

    PaymentResult payWithWallet(PayWithWalletCommand command);

    // A16 — PG 결제 승인의 메인 경로(브라우저가 토스 SDK로 받은 paymentKey를 전달해서 호출).
    // 7-13 plan D1 — confirm이 PG의 유일한 진입점(get-or-create 예약). POST /payments는 WALLET 전용으로 축소.
    PaymentResult confirmPg(PgConfirmCommand command);

    PaymentResult getPayment(UUID paymentId);

    // order_completed 이벤트로 sellerId/productId 사후채움(#14) + 정산용 settlement-source 이벤트 발행(B2).
    void backfillSellerAndProduct(UUID orderId, UUID sellerId, UUID productId);
}

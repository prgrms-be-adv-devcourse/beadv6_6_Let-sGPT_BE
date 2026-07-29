package com.openat.payment.application.usecase;

import com.openat.payment.application.dto.RefundCommand;
import com.openat.payment.application.dto.RefundHistoryResult;
import com.openat.payment.application.dto.RefundResult;
import java.util.UUID;

public interface RefundUseCase {

    RefundResult requestRefund(RefundCommand command);

    // memberId는 소유자 검증용 — Refund에 memberId가 없어 Payment를 조인해 대조한다(403).
    RefundResult getRefund(UUID refundId, UUID memberId);

    RefundHistoryResult getRefundHistories(UUID memberId, int page, int size);
}

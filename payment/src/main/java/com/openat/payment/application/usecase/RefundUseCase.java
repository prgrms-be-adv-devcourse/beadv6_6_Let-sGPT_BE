package com.openat.payment.application.usecase;

import com.openat.payment.application.dto.RefundCommand;
import com.openat.payment.application.dto.RefundHistoryResult;
import com.openat.payment.application.dto.RefundResult;
import java.util.UUID;

public interface RefundUseCase {

    RefundResult requestRefund(RefundCommand command);

    RefundResult getRefund(UUID refundId);

    RefundHistoryResult getRefundHistories(UUID memberId, int page, int size);
}

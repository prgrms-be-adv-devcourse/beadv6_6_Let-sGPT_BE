package com.openat.payment.application.service;

import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import com.openat.payment.presentation.dto.DailyPaymentSettlementResponse;
import com.openat.payment.presentation.dto.DailyPaymentSettlementResponse.PaymentItem;
import com.openat.payment.presentation.dto.DailyPaymentSettlementResponse.RefundItem;
import com.openat.payment.presentation.dto.DailyPaymentSettlementResponse.Summary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

// 정산 대사 일별 API(reconciliation.md, WS-2) — 전일 결제·환불을 한 API로 반환한다. PG 대사 MATCHED 행만
// 노출(WS-0) — settlement가 이 값을 신뢰하고 자신의 대사 재료로 쓰기 때문에, PG 검증 전 데이터가 새지 않게 한다.
@Service
public class DailyPaymentSettlementService {

    private static final DateTimeFormatter SETTLEMENT_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
    // 저장된 LocalDateTime은 KST 벽시계 값이라는 전제(project 전역 컨벤션) — +09:00 오프셋을 그대로 부여한다.
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    public DailyPaymentSettlementService(PaymentRepository paymentRepository, RefundRepository refundRepository) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
    }

    public DailyPaymentSettlementResponse getDaily(LocalDate businessDate) {
        LocalDateTime from = businessDate.atStartOfDay();
        LocalDateTime to = businessDate.plusDays(1).atStartOfDay();

        List<Payment> payments = paymentRepository.findMatchedApprovedBetween(from, to);
        List<Refund> refunds = refundRepository.findMatchedCompletedBetween(from, to);

        // 환불 목록 조립용 Payment 배치 조회(N+1 회피) — 환불된 결제가 payments 윈도우 밖(전날 승인 등)일 수 있어 별도 조회.
        List<UUID> refundPaymentIds = refunds.stream().map(Refund::getPaymentId).distinct().toList();
        Map<UUID, Payment> paymentsById = refundPaymentIds.isEmpty()
                ? Map.of()
                : paymentRepository.findAllByIds(refundPaymentIds).stream()
                        .collect(Collectors.toMap(Payment::getId, Function.identity()));

        List<PaymentItem> paymentItems = payments.stream().map(this::toPaymentItem).toList();
        List<RefundItem> refundItems = refunds.stream()
                .map(refund -> toRefundItem(refund, paymentsById.get(refund.getPaymentId())))
                .filter(java.util.Objects::nonNull)
                .toList();

        long totalPaymentAmount = paymentItems.stream().mapToLong(PaymentItem::paidAmount).sum();
        long totalRefundAmount = refundItems.stream().mapToLong(RefundItem::refundAmount).sum();

        Summary summary = new Summary(paymentItems.size(), totalPaymentAmount, refundItems.size(),
                totalRefundAmount, totalPaymentAmount - totalRefundAmount);

        return new DailyPaymentSettlementResponse(businessDate.toString(), paymentItems, refundItems, summary);
    }

    private PaymentItem toPaymentItem(Payment payment) {
        return new PaymentItem(payment.getId(), payment.getOrderId(), payment.getSellerId(), payment.getMemberId(),
                payment.getProductId(), payment.getAmount(), payment.getAmount(), 0L,
                payment.getStatus().name(), toOffsetDateTime(payment.getApprovedAt()),
                toSettlementMonth(payment.getApprovedAt()));
    }

    private RefundItem toRefundItem(Refund refund, Payment payment) {
        if (payment == null) {
            // 이론상 FK로 항상 존재해야 하지만(#A6), 대사 신뢰성을 위해 조립 실패 행은 조용히 누락시키지 않고 스킵.
            return null;
        }
        return new RefundItem(refund.getId(), refund.getPaymentId(), payment.getOrderId(), payment.getSellerId(),
                payment.getMemberId(), refund.getAmount(), refund.getReason(), refund.getStatus().name(),
                toOffsetDateTime(refund.getCompletedAt()), toSettlementMonth(refund.getCompletedAt()));
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime localDateTime) {
        return localDateTime == null ? null : localDateTime.atOffset(KST);
    }

    private String toSettlementMonth(LocalDateTime localDateTime) {
        return localDateTime == null ? null : localDateTime.format(SETTLEMENT_MONTH_FORMATTER);
    }
}

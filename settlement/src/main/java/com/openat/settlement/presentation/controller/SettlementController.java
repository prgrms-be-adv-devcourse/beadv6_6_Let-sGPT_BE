package com.openat.settlement.presentation.controller;

import com.openat.common.exception.BusinessException;
import com.openat.common.response.PageResponse;
import com.openat.settlement.application.dto.FindSettlementBatchResultsQuery;
import com.openat.settlement.application.dto.FindSellerSettlementsQuery;
import com.openat.settlement.application.dto.FindSettlementOrdersQuery;
import com.openat.settlement.application.dto.RetryFailedSellerSettlementsCommand;
import com.openat.settlement.application.dto.RetryFailedSellerSettlementsResult;
import com.openat.settlement.application.dto.SellerSettlementSummary;
import com.openat.settlement.application.dto.SettlementBatchResultSummary;
import com.openat.settlement.application.dto.SettlementOrderSummary;
import com.openat.settlement.application.usecase.FailedSellerSettlementRetryUseCase;
import com.openat.settlement.application.usecase.SettlementQueryUseCase;
import com.openat.settlement.domain.exception.SettlementErrorCode;
import com.openat.settlement.domain.model.SellerSettlementStatus;
import com.openat.settlement.domain.model.SettlementBatchStatus;
import com.openat.settlement.domain.model.SettlementOrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("${api.init}/settlements")
public class SettlementController {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final FailedSellerSettlementRetryUseCase failedSellerSettlementRetryUseCase;
    private final SettlementQueryUseCase settlementQueryUseCase;

    @Operation(
            summary = "전체 정산 주문 목록 조회",
            description = "정산월, 정산 상태, 판매자 ID, 주문 ID 조건으로 정산 주문 목록을 페이징 조회합니다. 조건을 입력하지 않으면 전체 정산 주문을 조회합니다. page는 0부터 시작하며 size는 최대 200건까지 허용합니다."
    )
    @GetMapping("/orders")
    public ResponseEntity<PageResponse<SettlementOrderSummary>> findSettlementOrders(
            @Parameter(description = "정산월입니다. yyyyMM 형식으로 입력합니다. 예: 202506")
            @RequestParam(required = false) String settlementMonth,
            @Parameter(description = "정산 주문 상태입니다. READY 또는 COMPLETED 값을 입력합니다.")
            @RequestParam(required = false) SettlementOrderStatus status,
            @Parameter(description = "판매자 ID입니다. 특정 판매자의 정산 주문만 조회할 때 사용합니다.")
            @RequestParam(required = false) UUID sellerId,
            @Parameter(description = "주문 ID입니다. 특정 주문의 정산 주문만 조회할 때 사용합니다.")
            @RequestParam(required = false) UUID orderId,
            @Parameter(description = "조회할 페이지 번호입니다. 0부터 시작합니다. 기본값은 0입니다.")
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int page,
            @Parameter(description = "한 페이지에 조회할 데이터 개수입니다. 기본값은 20, 최대값은 200입니다.")
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size
    ) {
        return ResponseEntity.ok(PageResponse.of(
                settlementQueryUseCase.findSettlementOrders(
                        new FindSettlementOrdersQuery(
                                settlementMonth,
                                status,
                                sellerId,
                                orderId
                        ),
                        createPageable(page, size)
                )
        ));
    }

    @Operation(
            summary = "전체 판매자 정산 결과 조회",
            description = "정산월, 판매자 ID, 판매자 정산 상태 조건으로 판매자별 월 정산 결과 목록을 페이징 조회합니다. 조건을 입력하지 않으면 전체 판매자 정산 결과를 조회합니다. page는 0부터 시작하며 size는 최대 200건까지 허용합니다."
    )
    @GetMapping("/sellers")
    public ResponseEntity<PageResponse<SellerSettlementSummary>> findSellerSettlements(
            @Parameter(description = "정산월입니다. yyyyMM 형식으로 입력합니다. 예: 202506")
            @RequestParam(required = false) String settlementMonth,
            @Parameter(description = "판매자 ID입니다. 특정 판매자의 월 정산 결과만 조회할 때 사용합니다.")
            @RequestParam(required = false) UUID sellerId,
            @Parameter(description = "판매자 정산 상태입니다. READY, COMPLETED, FAILED 중 하나를 입력합니다.")
            @RequestParam(required = false) SellerSettlementStatus status,
            @Parameter(description = "조회할 페이지 번호입니다. 0부터 시작합니다. 기본값은 0입니다.")
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int page,
            @Parameter(description = "한 페이지에 조회할 데이터 개수입니다. 기본값은 20, 최대값은 200입니다.")
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size
    ) {
        return ResponseEntity.ok(PageResponse.of(
                settlementQueryUseCase.findSellerSettlements(
                        new FindSellerSettlementsQuery(
                                settlementMonth,
                                sellerId,
                                status
                        ),
                        createPageable(page, size)
                )
        ));
    }

    @Operation(
            summary = "월 자동 배치 결과 조회",
            description = "정산월과 배치 상태 조건으로 월 자동 정산 배치 실행 결과를 페이징 조회합니다. 조건을 입력하지 않으면 전체 배치 실행 결과를 조회합니다. page는 0부터 시작하며 size는 최대 200건까지 허용합니다."
    )
    @GetMapping("/batch-results")
    public ResponseEntity<PageResponse<SettlementBatchResultSummary>> findSettlementBatchResults(
            @Parameter(description = "정산월입니다. yyyyMM 형식으로 입력합니다. 예: 202506")
            @RequestParam(required = false) String settlementMonth,
            @Parameter(description = "배치 상태입니다. READY, RUNNING, COMPLETED, FAILED 중 하나를 입력합니다.")
            @RequestParam(required = false) SettlementBatchStatus status,
            @Parameter(description = "조회할 페이지 번호입니다. 0부터 시작합니다. 기본값은 0입니다.")
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int page,
            @Parameter(description = "한 페이지에 조회할 데이터 개수입니다. 기본값은 20, 최대값은 200입니다.")
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size
    ) {
        return ResponseEntity.ok(PageResponse.of(
                settlementQueryUseCase.findSettlementBatchResults(
                        new FindSettlementBatchResultsQuery(
                                settlementMonth,
                                status
                        ),
                        createPageable(page, size)
                )
        ));
    }

    @Operation(
            summary = "실패 판매자 정산 재처리",
            description = "지정한 정산월에서 FAILED 상태인 판매자 정산 결과만 조회해 SETTLEMENT_RETRY 배치를 생성하고 재정산합니다."
    )
    @PostMapping("/retry-failed")
    public ResponseEntity<RetryFailedSellerSettlementsResponse> retryFailedSellerSettlements(
            @Parameter(description = "재처리할 정산월입니다. yyyyMM 형식으로 입력합니다. 예: 202506", required = true)
            @RequestParam String settlementMonth
    ) {
        RetryFailedSellerSettlementsResult result =
                failedSellerSettlementRetryUseCase.retryFailedSellerSettlements(
                        new RetryFailedSellerSettlementsCommand(settlementMonth)
                );

        return ResponseEntity.ok(RetryFailedSellerSettlementsResponse.from(result));
    }

    public record RetryFailedSellerSettlementsResponse(
            UUID batchId,
            String settlementMonth,
            int retriedSellerCount,
            String status,
            String failReason
    ) {

        private static RetryFailedSellerSettlementsResponse from(
                RetryFailedSellerSettlementsResult result
        ) {
            return new RetryFailedSellerSettlementsResponse(
                    result.batchId(),
                    result.settlementMonth(),
                    result.retriedSellerCount(),
                    result.status().name(),
                    result.failReason()
            );
        }
    }

    private Pageable createPageable(int page, int size) {
        if (page < 0) {
            throw new BusinessException(
                    SettlementErrorCode.INVALID_PAGE_REQUEST,
                    "page는 0 이상이어야 합니다."
            );
        }

        if (size < 1) {
            throw new BusinessException(
                    SettlementErrorCode.INVALID_PAGE_REQUEST,
                    "size는 1 이상이어야 합니다."
            );
        }

        return PageRequest.of(page, Math.min(size, MAX_SIZE));
    }
}

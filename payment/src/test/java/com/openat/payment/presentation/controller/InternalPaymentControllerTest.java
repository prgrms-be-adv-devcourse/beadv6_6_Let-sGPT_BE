package com.openat.payment.presentation.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.payment.application.dto.InternalPaymentStatusResult;
import com.openat.payment.application.service.InternalPaymentQueryService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// standalone MockMvc — 조회 200/404 HTTP 매핑과 응답 바디를 검증한다(GlobalExceptionHandler 어드바이스 포함).
class InternalPaymentControllerTest {

  private final InternalPaymentQueryService queryService = mock(InternalPaymentQueryService.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new InternalPaymentController(queryService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void 결제가_있으면_200과_결제_상태를_반환한다() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    when(queryService.getByOrderId(orderId))
        .thenReturn(new InternalPaymentStatusResult(paymentId, "APPROVED", 10_000L));

    mockMvc
        .perform(get("/internal/v1/payments").param("orderId", orderId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.amount").value(10_000));
  }

  @Test
  void 결제가_없으면_404를_반환한다() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(queryService.getByOrderId(orderId))
        .thenThrow(new BusinessException(CommonErrorCode.NOT_FOUND));

    mockMvc
        .perform(get("/internal/v1/payments").param("orderId", orderId.toString()))
        .andExpect(status().isNotFound());
  }
}

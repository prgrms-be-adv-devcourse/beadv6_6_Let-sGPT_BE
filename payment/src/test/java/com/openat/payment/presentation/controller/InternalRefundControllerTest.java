package com.openat.payment.presentation.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.payment.application.dto.InternalRefundResult;
import com.openat.payment.application.service.InternalRefundService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// standalone MockMvc — 환불 응답값의 HTTP 매핑을 검증한다. NO_PAYMENT/REFUND_ACCEPTED은 200, PAYMENT_PENDING은 409.
class InternalRefundControllerTest {

  private final InternalRefundService refundService = mock(InternalRefundService.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new InternalRefundController(refundService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private String body(UUID orderId) {
    return "{\"orderId\":\"" + orderId + "\"}";
  }

  @Test
  void REFUND_ACCEPTED이면_200과_result를_반환하고_헤더_멱등키로_위임한다() throws Exception {
    UUID orderId = UUID.randomUUID();
    String key = "refund-order-" + orderId;
    when(refundService.refundByOrder(orderId, key)).thenReturn(InternalRefundResult.REFUND_ACCEPTED);

    mockMvc
        .perform(
            post("/internal/v1/refunds")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(orderId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("REFUND_ACCEPTED"));

    verify(refundService).refundByOrder(eq(orderId), eq(key));
  }

  @Test
  void NO_PAYMENT이면_200과_result를_반환한다() throws Exception {
    UUID orderId = UUID.randomUUID();
    String key = "refund-order-" + orderId;
    when(refundService.refundByOrder(orderId, key)).thenReturn(InternalRefundResult.NO_PAYMENT);

    mockMvc
        .perform(
            post("/internal/v1/refunds")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(orderId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("NO_PAYMENT"));
  }

  @Test
  void PAYMENT_PENDING이면_409와_result를_반환한다() throws Exception {
    UUID orderId = UUID.randomUUID();
    String key = "refund-order-" + orderId;
    when(refundService.refundByOrder(orderId, key))
        .thenReturn(InternalRefundResult.PAYMENT_PENDING);

    mockMvc
        .perform(
            post("/internal/v1/refunds")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(orderId)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.result").value("PAYMENT_PENDING"));
  }
}

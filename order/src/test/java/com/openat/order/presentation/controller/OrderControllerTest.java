package com.openat.order.presentation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openat.common.auth.CurrentUserArgumentResolver;
import com.openat.common.auth.UserContextFilter;
import com.openat.common.auth.UserHeaders;
import com.openat.common.exception.BusinessException;
import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.order.application.port.DropNotFoundException;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.application.usecase.OrderUseCase;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.OrderFailCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("주문 컨트롤러 예외 매핑")
class OrderControllerTest {

  private static final UUID MEMBER_ID = UUID.randomUUID();

  @Mock private OrderUseCase orderUseCase;

  @Test
  @DisplayName("주문 조회 경로 변수가 UUID가 아니면 400 INVALID_INPUT을 반환한다")
  void getOrder_whenOrderIdIsMalformed_returnsBadRequest() throws Exception {
    mvc()
        .perform(
            get("/api/v1/orders/{orderId}", "not-a-uuid").header(UserHeaders.USER_ID, MEMBER_ID))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
  }

  @Test
  @DisplayName("주문 목록 status 파라미터가 알 수 없는 값이면 400 INVALID_INPUT을 반환한다")
  void getMyOrders_whenStatusIsUnknown_returnsBadRequest() throws Exception {
    mvc()
        .perform(
            get("/api/v1/orders").param("status", "BOGUS").header(UserHeaders.USER_ID, MEMBER_ID))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
  }

  @Test
  @DisplayName("내부 API 경로 변수가 UUID가 아니면 400 INVALID_INPUT을 반환한다")
  void retryRefund_whenOrderIdIsMalformed_returnsBadRequest() throws Exception {
    mvc()
        .perform(post("/internal/v1/orders/{orderId}/refund-retries", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
  }

  @Test
  @DisplayName("존재하지 않는 드롭으로 주문하면 404 DROP_NOT_FOUND를 반환한다")
  void createOrder_whenDropDoesNotExist_returnsNotFound() throws Exception {
    when(orderUseCase.createOrder(eq(MEMBER_ID), any()))
        .thenThrow(
            new DropNotFoundException(
                OrderFailCode.PRODUCT_INTEGRATION_FAILED, "존재하지 않는 드롭입니다.", null));

    mvc()
        .perform(createOrderRequest())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("DROP_NOT_FOUND"));
  }

  @Test
  @DisplayName("상품 연동 실패로 주문 기준정보를 못 읽으면 502 ORDER_EXTERNAL_API_ERROR를 반환한다")
  void createOrder_whenProductIntegrationFails_returnsBadGateway() throws Exception {
    when(orderUseCase.createOrder(eq(MEMBER_ID), any()))
        .thenThrow(
            new ProductPortException(OrderFailCode.PRODUCT_INTEGRATION_FAILED, "product timeout"));

    mvc()
        .perform(createOrderRequest())
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("ORDER_EXTERNAL_API_ERROR"));
  }

  @Test
  @DisplayName("비즈니스 예외는 전역 핸들러가 그대로 처리한다")
  void getOrder_whenOrderIsMissing_returnsNotFoundFromGlobalHandler() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderUseCase.getMyOrder(MEMBER_ID, orderId))
        .thenThrow(new BusinessException(OrderErrorCode.NOT_FOUND));

    mvc()
        .perform(get("/api/v1/orders/{orderId}", orderId).header(UserHeaders.USER_ID, MEMBER_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));

    verify(orderUseCase).getMyOrder(MEMBER_ID, orderId);
  }

  private MockHttpServletRequestBuilder createOrderRequest() {
    return post("/api/v1/orders")
        .header(UserHeaders.USER_ID, MEMBER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"dropId":"%s","quantity":1,"idempotencyKey":"order-test-0001"}
            """
                .formatted(UUID.randomUUID()));
  }

  // 전역 catch-all(Exception→500)을 먼저 등록해, @Order로만 이 컨트롤러 전용 어드바이스가 이기는지 확인한다.
  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(
            new OrderController(orderUseCase, new SimpleMeterRegistry()))
        .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
        .setControllerAdvice(new GlobalExceptionHandler(), new OrderExceptionHandler())
        .addFilters(new UserContextFilter())
        .build();
  }
}

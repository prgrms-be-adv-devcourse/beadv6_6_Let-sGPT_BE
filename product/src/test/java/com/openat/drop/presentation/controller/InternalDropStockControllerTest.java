package com.openat.drop.presentation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.common.exception.BusinessException;
import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.config.WebConfig;
import com.openat.drop.application.usecase.DropStockUseCase;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.presentation.dto.StockChangeRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalDropStockController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, GlobalExceptionHandler.class})
@DisplayName("내부 드롭 재고 컨트롤러")
class InternalDropStockControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DropStockUseCase dropStockUseCase;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("차감 성공이면 200과 잔여 수량을 반환한다 (/internal 경로, /api/v1 미적용)")
  void deduct_success_returns200WithRemaining() throws Exception {
    // given
    UUID dropId = UUID.randomUUID();
    given(dropStockUseCase.deduct(any())).willReturn(42L);

    // when & then
    mockMvc
        .perform(
            post("/internal/drops/{dropId}/stock-deductions", dropId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.remainingQuantity").value(42));
  }

  @Test
  @DisplayName("재고가 없으면 409 SOLD_OUT을 반환한다")
  void deduct_soldOut_returns409() throws Exception {
    // given
    UUID dropId = UUID.randomUUID();
    willThrow(new BusinessException(DropErrorCode.SOLD_OUT)).given(dropStockUseCase).deduct(any());

    // when & then
    mockMvc
        .perform(
            post("/internal/drops/{dropId}/stock-deductions", dropId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("DROP_SOLD_OUT"));
  }

  @Test
  @DisplayName("orderId가 없으면 400 INVALID_INPUT을 반환한다")
  void deduct_missingOrderId_returns400() throws Exception {
    // given
    UUID dropId = UUID.randomUUID();
    StockChangeRequest invalid = new StockChangeRequest(null, UUID.randomUUID(), 1);

    // when & then
    mockMvc
        .perform(
            post("/internal/drops/{dropId}/stock-deductions", dropId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalid)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
  }

  @Test
  @DisplayName("롤백 성공이면 200과 잔여 수량을 반환한다")
  void rollback_success_returns200() throws Exception {
    // given
    UUID dropId = UUID.randomUUID();
    given(dropStockUseCase.rollback(any())).willReturn(Optional.of(8L));

    // when & then
    mockMvc
        .perform(
            post("/internal/drops/{dropId}/stock-rollbacks", dropId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.remainingQuantity").value(8));
  }

  @Test
  @DisplayName("라이브 잔여가 없는 롤백(no-op)이면 204 No Content를 반환한다")
  void rollback_noLiveRemaining_returns204() throws Exception {
    // given
    UUID dropId = UUID.randomUUID();
    given(dropStockUseCase.rollback(any())).willReturn(Optional.empty());

    // when & then
    mockMvc
        .perform(
            post("/internal/drops/{dropId}/stock-rollbacks", dropId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request())))
        .andExpect(status().isNoContent());
  }

  private StockChangeRequest request() {
    return new StockChangeRequest(UUID.randomUUID(), UUID.randomUUID(), 1);
  }
}

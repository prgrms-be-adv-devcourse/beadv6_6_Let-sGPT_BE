package com.openat.drop.presentation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openat.common.exception.BusinessException;
import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.config.WebConfig;
import com.openat.drop.application.dto.DropSnapshotInfo;
import com.openat.drop.application.usecase.DropQueryUseCase;
import com.openat.drop.domain.error.DropErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalDropQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, GlobalExceptionHandler.class})
@DisplayName("내부 드롭 조회 컨트롤러")
class InternalDropQueryControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DropQueryUseCase dropQueryUseCase;

  @Test
  @DisplayName("드롭 스냅샷 조회는 200과 productId·sellerId·unitPrice를 반환한다 (/internal 경로)")
  void getDropSnapshot_success_returns200() throws Exception {
    // given
    UUID dropId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();
    given(dropQueryUseCase.getDropSnapshot(dropId))
        .willReturn(new DropSnapshotInfo(productId, sellerId, 219_000L));

    // when & then
    mockMvc
        .perform(get("/internal/drops/{dropId}/order-snapshot", dropId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.productId").value(productId.toString()))
        .andExpect(jsonPath("$.sellerId").value(sellerId.toString()))
        .andExpect(jsonPath("$.unitPrice").value(219_000));
  }

  @Test
  @DisplayName("없는 드롭이면 404 DROP_NOT_FOUND를 반환한다")
  void getDropSnapshot_notFound_returns404() throws Exception {
    // given
    UUID missingId = UUID.randomUUID();
    given(dropQueryUseCase.getDropSnapshot(any()))
        .willThrow(new BusinessException(DropErrorCode.NOT_FOUND));

    // when & then
    mockMvc
        .perform(get("/internal/drops/{dropId}/order-snapshot", missingId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("DROP_NOT_FOUND"));
  }
}

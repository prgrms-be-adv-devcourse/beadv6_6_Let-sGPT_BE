package com.openat.product.presentation.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.config.WebConfig;
import com.openat.product.application.dto.ProductChangeInfo;
import com.openat.product.application.dto.ProductChangeOperation;
import com.openat.product.application.usecase.ProductQueryUseCase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalProductQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, GlobalExceptionHandler.class})
@DisplayName("내부 상품 변경 피드 컨트롤러")
class InternalProductQueryControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProductQueryUseCase productQueryUseCase;

  @Test
  @DisplayName("변경 피드 조회는 200과 operation 태그가 붙은 변경 목록을 반환한다 (/internal 경로)")
  void searchChanges_success_returns200WithTaggedFeed() throws Exception {
    // given
    Instant changedAfter = Instant.parse("2026-06-01T00:00:00Z");
    UUID insertId = UUID.randomUUID();
    UUID deleteId = UUID.randomUUID();
    ProductChangeInfo inserted =
        new ProductChangeInfo(
            ProductChangeOperation.INSERT,
            insertId,
            "새 상품",
            "설명",
            UUID.randomUUID(),
            "의류",
            "오픈앳 스튜디오",
            11_000L,
            "a.jpg",
            Instant.parse("2026-06-02T00:00:00Z"),
            Instant.parse("2026-06-02T00:00:00Z"),
            null);
    ProductChangeInfo deleted =
        new ProductChangeInfo(
            ProductChangeOperation.DELETE,
            deleteId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Instant.parse("2026-06-04T00:00:00Z"));
    given(productQueryUseCase.searchChanges(changedAfter)).willReturn(List.of(inserted, deleted));

    // when & then
    mockMvc
        .perform(get("/internal/products/changes").param("changedAfter", "2026-06-01T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].operation").value("INSERT"))
        .andExpect(jsonPath("$[0].id").value(insertId.toString()))
        .andExpect(jsonPath("$[0].thumbnailKey").value("a.jpg"))
        .andExpect(jsonPath("$[0].imgDescription").doesNotExist())
        .andExpect(jsonPath("$[1].operation").value("DELETE"))
        .andExpect(jsonPath("$[1].id").value(deleteId.toString()))
        .andExpect(jsonPath("$[1].deletedAt").value("2026-06-04T00:00:00Z"))
        .andExpect(jsonPath("$[1].name").isEmpty());
  }
}

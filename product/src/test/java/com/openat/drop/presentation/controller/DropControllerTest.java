package com.openat.drop.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openat.common.exception.BusinessException;
import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.config.WebConfig;
import com.openat.drop.application.dto.DropInfo;
import com.openat.drop.application.usecase.DropCommandUseCase;
import com.openat.drop.application.usecase.DropQueryUseCase;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.DropSearchCondition;
import com.openat.drop.presentation.dto.DropCreateRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DropController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, GlobalExceptionHandler.class})
@DisplayName("드롭 컨트롤러")
class DropControllerTest {

  private static final String SELLER_ID_HEADER = "X-Seller-Id";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DropCommandUseCase dropCommandUseCase;
  @MockitoBean private DropQueryUseCase dropQueryUseCase;
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Nested
  @DisplayName("드롭 등록")
  class Create {

    @Test
    @DisplayName("정상 요청이면 201과 Location 헤더를 반환한다")
    void create_validRequest_returns201WithLocation() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID createdId = UUID.randomUUID();
      given(dropCommandUseCase.create(any())).willReturn(createdId);

      // when & then
      mockMvc
          .perform(
              post("/api/v1/drops")
                  .header(SELLER_ID_HEADER, sellerId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(validRequest())))
          .andExpect(status().isCreated())
          .andExpect(header().string("Location", endsWith("/api/v1/drops/" + createdId)));
    }

    @Test
    @DisplayName("오픈 시각이 과거면 400 INVALID_INPUT을 반환한다")
    void create_pastOpenAt_returns400() throws Exception {
      // given
      DropCreateRequest request =
          new DropCreateRequest(
              UUID.randomUUID(), 10_000L, 100, 2, Instant.now().minusSeconds(3600), null);

      // when & then
      mockMvc
          .perform(
              post("/api/v1/drops")
                  .header(SELLER_ID_HEADER, UUID.randomUUID())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
      then(dropCommandUseCase).should(never()).create(any());
    }

    @Test
    @DisplayName("종료 시각이 오픈 시각보다 이르면 400 INVALID_INPUT을 반환한다")
    void create_closeAtBeforeOpenAt_returns400() throws Exception {
      // given
      Instant openAt = Instant.now().plusSeconds(3600);
      DropCreateRequest request =
          new DropCreateRequest(
              UUID.randomUUID(), 10_000L, 100, 2, openAt, openAt.minusSeconds(600));

      // when & then
      mockMvc
          .perform(
              post("/api/v1/drops")
                  .header(SELLER_ID_HEADER, UUID.randomUUID())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
      then(dropCommandUseCase).should(never()).create(any());
    }

    @Test
    @DisplayName("총 수량이 0 이하면 400 INVALID_INPUT을 반환한다")
    void create_nonPositiveQuantity_returns400() throws Exception {
      // given
      DropCreateRequest request =
          new DropCreateRequest(
              UUID.randomUUID(), 10_000L, 0, 2, Instant.now().plusSeconds(3600), null);

      // when & then
      mockMvc
          .perform(
              post("/api/v1/drops")
                  .header(SELLER_ID_HEADER, UUID.randomUUID())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
      then(dropCommandUseCase).should(never()).create(any());
    }
  }

  @Nested
  @DisplayName("드롭 단건 조회")
  class GetSingle {

    @Test
    @DisplayName("존재하는 드롭이면 200과 파생 상태·잔여를 반환한다")
    void getDrop_existing_returns200() throws Exception {
      // given
      DropInfo info = sampleInfo();
      given(dropQueryUseCase.getDrop(info.id())).willReturn(info);

      // when & then
      mockMvc
          .perform(get("/api/v1/drops/{dropId}", info.id()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(info.id().toString()))
          .andExpect(jsonPath("$.status").value("OPEN"))
          .andExpect(jsonPath("$.sellerName").value("노드 아틀리에"))
          .andExpect(jsonPath("$.remainingQuantity").value(37))
          .andExpect(jsonPath("$.limitPerUser").value(2));
    }

    @Test
    @DisplayName("없는 드롭이면 404 DROP_NOT_FOUND를 반환한다")
    void getDrop_notFound_returns404() throws Exception {
      // given
      UUID missingId = UUID.randomUUID();
      given(dropQueryUseCase.getDrop(missingId))
          .willThrow(new BusinessException(DropErrorCode.NOT_FOUND));

      // when & then
      mockMvc
          .perform(get("/api/v1/drops/{dropId}", missingId))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.error").value("DROP_NOT_FOUND"));
    }
  }

  @Nested
  @DisplayName("드롭 목록 조회")
  class GetList {

    @Test
    @DisplayName("목록을 페이징해 200으로 반환한다")
    void searchDrops_returns200() throws Exception {
      // given
      DropInfo info = sampleInfo();
      given(dropQueryUseCase.searchDrops(any(DropSearchCondition.class), any(Pageable.class)))
          .willReturn(new PageImpl<>(List.of(info), PageRequest.of(0, 10), 1));

      // when & then
      mockMvc
          .perform(get("/api/v1/drops").param("status", "OPEN"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].id").value(info.id().toString()))
          .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("본인 드롭 조회는 헤더의 sellerId를 검색 조건에 바인딩한다")
    void searchMyDrops_bindsSellerId() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      given(dropQueryUseCase.searchDrops(any(DropSearchCondition.class), any(Pageable.class)))
          .willReturn(new PageImpl<>(List.<DropInfo>of(), PageRequest.of(0, 10), 0));

      // when
      mockMvc
          .perform(get("/api/v1/drops/me").header(SELLER_ID_HEADER, sellerId))
          .andExpect(status().isOk());

      // then
      ArgumentCaptor<DropSearchCondition> conditionCaptor =
          ArgumentCaptor.forClass(DropSearchCondition.class);
      then(dropQueryUseCase).should().searchDrops(conditionCaptor.capture(), any(Pageable.class));
      assertThat(conditionCaptor.getValue().sellerId()).isEqualTo(sellerId);
    }
  }

  @Nested
  @DisplayName("드롭 삭제")
  class Delete {

    @Test
    @DisplayName("삭제 요청이면 204를 반환하고 유스케이스에 위임한다")
    void delete_returns204() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID dropId = UUID.randomUUID();

      // when & then
      mockMvc
          .perform(delete("/api/v1/drops/{dropId}", dropId).header(SELLER_ID_HEADER, sellerId))
          .andExpect(status().isNoContent());
      then(dropCommandUseCase).should().delete(dropId, sellerId);
    }
  }

  private DropCreateRequest validRequest() {
    return new DropCreateRequest(
        UUID.randomUUID(), 219_000L, 100, 2, Instant.now().plusSeconds(3600), null);
  }

  private DropInfo sampleInfo() {
    return new DropInfo(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "한정판 러너 SS26",
        "노드 아틀리에",
        null,
        null,
        null,
        219_000L,
        100,
        37,
        DropStatus.OPEN,
        Instant.parse("2026-07-01T00:00:00Z"),
        null,
        2);
  }
}

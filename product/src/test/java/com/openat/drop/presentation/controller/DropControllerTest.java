package com.openat.drop.presentation.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.config.WebConfig;
import com.openat.drop.application.usecase.DropCommandUseCase;
import com.openat.drop.presentation.dto.DropCreateRequest;
import java.time.Instant;
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

@WebMvcTest(DropController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, GlobalExceptionHandler.class})
@DisplayName("드롭 컨트롤러")
class DropControllerTest {

  private static final String USER_ID_HEADER = "X-User-Id";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DropCommandUseCase dropCommandUseCase;
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

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
                .header(USER_ID_HEADER, sellerId)
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
                .header(USER_ID_HEADER, UUID.randomUUID())
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
        new DropCreateRequest(UUID.randomUUID(), 10_000L, 100, 2, openAt, openAt.minusSeconds(600));

    // when & then
    mockMvc
        .perform(
            post("/api/v1/drops")
                .header(USER_ID_HEADER, UUID.randomUUID())
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
                .header(USER_ID_HEADER, UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
    then(dropCommandUseCase).should(never()).create(any());
  }

  @Test
  @DisplayName("삭제 요청이면 204를 반환하고 유스케이스에 위임한다")
  void delete_returns204() throws Exception {
    // given
    UUID sellerId = UUID.randomUUID();
    UUID dropId = UUID.randomUUID();

    // when & then
    mockMvc
        .perform(delete("/api/v1/drops/{dropId}", dropId).header(USER_ID_HEADER, sellerId))
        .andExpect(status().isNoContent());
    then(dropCommandUseCase).should().delete(dropId, sellerId);
  }

  private DropCreateRequest validRequest() {
    return new DropCreateRequest(
        UUID.randomUUID(), 219_000L, 100, 2, Instant.now().plusSeconds(3600), null);
  }
}

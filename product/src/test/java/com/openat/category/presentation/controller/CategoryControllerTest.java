package com.openat.category.presentation.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.category.application.dto.CategoryCreateCommand;
import com.openat.category.application.usecase.CategoryCommandUseCase;
import com.openat.category.domain.error.CategoryErrorCode;
import com.openat.category.presentation.dto.CategoryCreateRequest;
import com.openat.category.presentation.dto.CategoryUpdateRequest;
import com.openat.common.exception.BusinessException;
import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.config.WebConfig;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CategoryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, GlobalExceptionHandler.class})
@DisplayName("카테고리 컨트롤러")
class CategoryControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private CategoryCommandUseCase categoryCommandUseCase;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Nested
  @DisplayName("카테고리 등록")
  class Create {

    @Test
    @DisplayName("정상 요청이면 201과 Location 헤더를 반환한다")
    void create_validRequest_returns201WithLocation() throws Exception {
      // given
      UUID createdId = UUID.randomUUID();
      CategoryCreateRequest request = new CategoryCreateRequest("의류");
      given(categoryCommandUseCase.create(any(CategoryCreateCommand.class))).willReturn(createdId);

      // when & then
      mockMvc
          .perform(
              post("/api/v1/categories")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isCreated())
          .andExpect(header().string("Location", endsWith("/api/v1/categories/" + createdId)));
    }

    @Test
    @DisplayName("이름이 비어 있으면 400 INVALID_INPUT을 반환한다")
    void create_blankName_returns400() throws Exception {
      // given
      CategoryCreateRequest request = new CategoryCreateRequest("  ");

      // when & then
      mockMvc
          .perform(
              post("/api/v1/categories")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
      then(categoryCommandUseCase).should(never()).create(any());
    }

    @Test
    @DisplayName("중복된 이름이면 409 CATEGORY_DUPLICATE_NAME을 반환한다")
    void create_duplicateName_returns409() throws Exception {
      // given
      CategoryCreateRequest request = new CategoryCreateRequest("의류");
      given(categoryCommandUseCase.create(any(CategoryCreateCommand.class)))
          .willThrow(new BusinessException(CategoryErrorCode.DUPLICATE_NAME));

      // when & then
      mockMvc
          .perform(
              post("/api/v1/categories")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error").value("CATEGORY_DUPLICATE_NAME"));
    }
  }

  @Nested
  @DisplayName("카테고리 수정")
  class Update {

    @Test
    @DisplayName("정상 요청이면 204를 반환한다")
    void update_validRequest_returns204() throws Exception {
      // given
      UUID categoryId = UUID.randomUUID();
      CategoryUpdateRequest request = new CategoryUpdateRequest("가방");

      // when & then
      mockMvc
          .perform(
              patch("/api/v1/categories/{id}", categoryId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isNoContent());
    }
  }

  @Nested
  @DisplayName("카테고리 삭제")
  class Delete {

    @Test
    @DisplayName("정상 요청이면 204를 반환한다")
    void delete_existing_returns204() throws Exception {
      // given
      UUID categoryId = UUID.randomUUID();

      // when & then
      mockMvc
          .perform(delete("/api/v1/categories/{id}", categoryId))
          .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("없는 카테고리면 404 CATEGORY_NOT_FOUND를 반환한다")
    void delete_notFound_returns404() throws Exception {
      // given
      UUID missingId = UUID.randomUUID();
      willThrow(new BusinessException(CategoryErrorCode.NOT_FOUND))
          .given(categoryCommandUseCase)
          .delete(missingId);

      // when & then
      mockMvc
          .perform(delete("/api/v1/categories/{id}", missingId))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.error").value("CATEGORY_NOT_FOUND"));
    }
  }
}

package com.openat.product.presentation.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.common.exception.BusinessException;
import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.config.WebConfig;
import com.openat.product.application.dto.ImagePresignInfo;
import com.openat.product.application.usecase.ImageStorageUseCase;
import com.openat.product.domain.error.ProductErrorCode;
import com.openat.product.presentation.dto.ImagePresignRequest;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductImageController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, GlobalExceptionHandler.class})
@DisplayName("상품 이미지 컨트롤러")
class ProductImageControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ImageStorageUseCase imageStorageUseCase;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("이미지 업로드 서명을 요청하면 staging 키와 PUT URL을 200으로 반환한다")
  void presign_validRequest_returns200WithUploadContract() throws Exception {
    // given
    Instant expiresAt = Instant.parse("2026-07-20T12:10:00Z");
    ImagePresignRequest request = new ImagePresignRequest("image/png");
    ImagePresignInfo upload =
        new ImagePresignInfo(
            "staging/550e8400-e29b-41d4-a716-446655440000.png",
            "https://example.com/upload",
            expiresAt);
    given(imageStorageUseCase.presignUpload(request.contentType())).willReturn(upload);

    // when & then
    mockMvc
        .perform(
            post("/api/v1/products/images/presign")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.stagingKey").value("staging/550e8400-e29b-41d4-a716-446655440000.png"))
        .andExpect(jsonPath("$.uploadUrl").value("https://example.com/upload"))
        .andExpect(jsonPath("$.expiresAt").value(expiresAt.toString()));
  }

  @Test
  @DisplayName("콘텐츠 타입이 비어 있으면 400 INVALID_INPUT을 반환하고 서명하지 않는다")
  void presign_blankContentType_returns400() throws Exception {
    // given
    ImagePresignRequest request = new ImagePresignRequest("  ");

    // when & then
    mockMvc
        .perform(
            post("/api/v1/products/images/presign")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
    then(imageStorageUseCase).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("키로 이미지를 조회하면 presigned GET URL로 302 리다이렉트한다")
  void getImage_returns302WithPresignedLocation() throws Exception {
    // given
    String key = "550e8400-e29b-41d4-a716-446655440000.png";
    String downloadUrl = "https://example.com/download";
    given(imageStorageUseCase.presignDownload(key)).willReturn(URI.create(downloadUrl));

    // when & then
    mockMvc
        .perform(get("/api/v1/products/images/{key}", key))
        .andExpect(status().isFound())
        .andExpect(header().string(HttpHeaders.LOCATION, downloadUrl));
  }

  @Test
  @DisplayName("final 키 형식이 아니면 400 PRODUCT_IMAGE_INVALID를 반환한다")
  void getImage_invalidKey_returns400() throws Exception {
    // given
    String key = "not-a-uuid.png";
    given(imageStorageUseCase.presignDownload(key))
        .willThrow(new BusinessException(ProductErrorCode.IMAGE_INVALID));

    // when & then
    mockMvc
        .perform(get("/api/v1/products/images/{key}", key))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("PRODUCT_IMAGE_INVALID"));
  }
}

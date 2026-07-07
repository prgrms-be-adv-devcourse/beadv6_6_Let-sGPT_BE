package com.openat.product.presentation.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.config.WebConfig;
import com.openat.product.application.usecase.ImageStorageUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductImageController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, GlobalExceptionHandler.class})
@DisplayName("상품 이미지 컨트롤러")
class ProductImageControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ImageStorageUseCase imageStorageUseCase;

  @Test
  @DisplayName("이미지를 업로드하면 201과 Location 헤더, 본문으로 { key, url } 을 반환한다")
  void upload_returns201WithKeyAndUrl() throws Exception {
    // given
    given(imageStorageUseCase.store(any(), eq("photo.png"))).willReturn("abc.png");
    MockMultipartFile file =
        new MockMultipartFile("file", "photo.png", "image/png", "bytes".getBytes());

    // when & then
    mockMvc
        .perform(multipart("/api/v1/products/images").file(file))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", endsWith("/api/v1/products/images/abc.png")))
        .andExpect(jsonPath("$.key").value("abc.png"))
        .andExpect(jsonPath("$.url").value("/api/v1/products/images/abc.png"));
  }

  @Test
  @DisplayName("키로 이미지를 조회하면 200과 추론된 콘텐츠 타입으로 바이트를 반환한다")
  void getImage_returns200WithBytes() throws Exception {
    // given
    given(imageStorageUseCase.load("abc.png")).willReturn("bytes".getBytes());

    // when & then
    mockMvc
        .perform(get("/api/v1/products/images/{key}", "abc.png"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.IMAGE_PNG));
  }
}

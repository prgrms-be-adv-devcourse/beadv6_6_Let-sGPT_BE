package com.openat.productimport.presentation.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.common.auth.UserHeaders;
import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.config.WebConfig;
import com.openat.productimport.application.service.ProductImportJobService;
import com.openat.productimport.domain.model.ProductImportItem;
import com.openat.productimport.domain.model.ProductImportItemStatus;
import com.openat.productimport.domain.model.ProductImportJob;
import com.openat.productimport.domain.model.ProductImportSourceType;
import com.openat.productimport.presentation.dto.ProductImportStartRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductImportJobController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, GlobalExceptionHandler.class})
class ProductImportJobControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProductImportJobService jobService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void startsAsynchronousImportAndReturnsStatusLocation() throws Exception {
    UUID sellerId = UUID.randomUUID();
    String packageLocation = "C:\\data\\limited-products-1000";
    ProductImportJob job =
        ProductImportJob.create(sellerId, ProductImportSourceType.LOCAL, packageLocation, true);
    given(jobService.start(sellerId, ProductImportSourceType.LOCAL, packageLocation, true))
        .willReturn(job);
    ProductImportStartRequest request =
        new ProductImportStartRequest(ProductImportSourceType.LOCAL, packageLocation, true);

    mockMvc
        .perform(
            post("/api/v1/products/import-jobs")
                .header(UserHeaders.SELLER_ID, sellerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isAccepted())
        .andExpect(
            header().string("Location", endsWith("/api/v1/products/import-jobs/" + job.getId())))
        .andExpect(jsonPath("$.jobId").value(job.getId().toString()))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.dryRun").value(true));
  }

  @Test
  void rejectsStartWithoutSellerHeader() throws Exception {
    ProductImportStartRequest request =
        new ProductImportStartRequest(ProductImportSourceType.LOCAL, "C:\\data", true);

    mockMvc
        .perform(
            post("/api/v1/products/import-jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    then(jobService).should(never()).start(any(), any(), any(), eq(true));
  }

  @Test
  void getsJobAndPagedRowResults() throws Exception {
    UUID sellerId = UUID.randomUUID();
    ProductImportJob job =
        ProductImportJob.create(
            sellerId, ProductImportSourceType.S3, "s3://demo/import-1000", false);
    ProductImportItem item =
        ProductImportItem.create(
            job.getId(),
            2,
            "demo-0001",
            ProductImportItemStatus.IMPORTED,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "등록에 성공했습니다.");
    given(jobService.get(sellerId, job.getId())).willReturn(job);
    given(jobService.getItems(eq(sellerId), eq(job.getId()), any(Pageable.class)))
        .willReturn(new PageImpl<>(java.util.List.of(item)));

    mockMvc
        .perform(
            get("/api/v1/products/import-jobs/{jobId}", job.getId())
                .header(UserHeaders.SELLER_ID, sellerId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceType").value("S3"));

    mockMvc
        .perform(
            get("/api/v1/products/import-jobs/{jobId}/items", job.getId())
                .header(UserHeaders.SELLER_ID, sellerId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].externalId").value("demo-0001"))
        .andExpect(jsonPath("$.content[0].status").value("IMPORTED"));
  }
}

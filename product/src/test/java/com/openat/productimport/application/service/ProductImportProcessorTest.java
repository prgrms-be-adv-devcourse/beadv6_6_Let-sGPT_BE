package com.openat.productimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.openat.productimport.application.dto.ParsedProductImportRow;
import com.openat.productimport.application.dto.ProductImportRow;
import com.openat.productimport.application.dto.ProductImportRowResult;
import com.openat.productimport.domain.model.ProductImportItem;
import com.openat.productimport.domain.model.ProductImportItemStatus;
import com.openat.productimport.domain.model.ProductImportJob;
import com.openat.productimport.domain.model.ProductImportJobStatus;
import com.openat.productimport.domain.model.ProductImportSourceType;
import com.openat.productimport.infrastructure.config.ProductImportProperties;
import com.openat.productimport.infrastructure.csv.ProductImportManifestReader;
import com.openat.productimport.infrastructure.persistence.ProductImportItemRepository;
import com.openat.productimport.infrastructure.persistence.ProductImportJobRepository;
import com.openat.productimport.infrastructure.source.ProductImportSource;
import com.openat.productimport.infrastructure.source.ProductImportSourceResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductImportProcessorTest {

  @Mock private ProductImportJobRepository jobRepository;
  @Mock private ProductImportItemRepository itemRepository;
  @Mock private ProductImportSourceResolver sourceResolver;
  @Mock private ProductImportManifestReader manifestReader;
  @Mock private ProductImportRowService rowService;
  @Mock private ProductImportSource source;

  private ProductImportProcessor processor;

  @BeforeEach
  void setUp() {
    ProductImportProperties properties =
        new ProductImportProperties(
            1000, 5 * 1024 * 1024, 10 * 1024 * 1024, 1, List.of(), List.of(), "ap-northeast-2");
    processor =
        new ProductImportProcessor(
            jobRepository, itemRepository, sourceResolver, manifestReader, rowService, properties);
  }

  @Test
  void continuesAfterRowFailureAndCompletesWithErrors() {
    UUID sellerId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    ProductImportJob job =
        ProductImportJob.create(sellerId, ProductImportSourceType.LOCAL, "package", false);
    ProductImportRow row = row();
    ParsedProductImportRow valid = ParsedProductImportRow.valid(row);
    ParsedProductImportRow invalid =
        ParsedProductImportRow.invalid(3, "demo-0002", "price는 양수여야 합니다.");
    given(jobRepository.findById(job.getId())).willReturn(Optional.of(job));
    given(sourceResolver.resolve(ProductImportSourceType.LOCAL)).willReturn(source);
    given(source.read("package", "products.csv", 5L * 1024 * 1024)).willReturn(new byte[0]);
    given(manifestReader.read(any())).willReturn(List.of(valid, invalid));
    given(rowService.process(sellerId, row, source, "package", false))
        .willReturn(ProductImportRowResult.imported(productId, null));

    processor.process(job.getId());

    assertThat(job.getStatus()).isEqualTo(ProductImportJobStatus.COMPLETED_WITH_ERRORS);
    assertThat(job.getTotalRows()).isEqualTo(2);
    assertThat(job.getSuccessCount()).isEqualTo(1);
    assertThat(job.getFailureCount()).isEqualTo(1);
    ArgumentCaptor<ProductImportItem> itemCaptor = ArgumentCaptor.forClass(ProductImportItem.class);
    then(itemRepository).should(org.mockito.Mockito.times(2)).save(itemCaptor.capture());
    assertThat(itemCaptor.getAllValues())
        .extracting(ProductImportItem::getStatus)
        .containsExactly(ProductImportItemStatus.IMPORTED, ProductImportItemStatus.FAILED);
    then(source).should().read(eq("package"), eq("products.csv"), eq(5L * 1024 * 1024));
  }

  private static ProductImportRow row() {
    return new ProductImportRow(
        2,
        "demo-0001",
        "한정판 시계",
        "설명",
        null,
        null,
        189000,
        "images/0001.jpg",
        List.of(),
        null,
        null,
        null,
        null,
        null);
  }
}

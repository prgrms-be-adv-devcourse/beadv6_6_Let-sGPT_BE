package com.openat.productimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.openat.category.application.usecase.CategoryQueryUseCase;
import com.openat.product.application.usecase.ImageStorageUseCase;
import com.openat.productimport.application.dto.ProductImportRow;
import com.openat.productimport.application.dto.ProductImportRowResult;
import com.openat.productimport.domain.model.ProductImportItemStatus;
import com.openat.productimport.infrastructure.config.ProductImportProperties;
import com.openat.productimport.infrastructure.persistence.ProductImportReceiptRepository;
import com.openat.productimport.infrastructure.source.ProductImportSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductImportRowServiceTest {

  @Mock private ProductImportReceiptRepository receiptRepository;
  @Mock private CategoryQueryUseCase categoryQueryUseCase;
  @Mock private ImageStorageUseCase imageStorageUseCase;
  @Mock private ProductImportRegistrationService registrationService;
  @Mock private ProductImportSource source;

  private ProductImportRowService service;

  @BeforeEach
  void setUp() {
    ProductImportProperties properties =
        new ProductImportProperties(
            1000, 5 * 1024 * 1024, 10 * 1024 * 1024, 1, List.of(), List.of(), "ap-northeast-2");
    service =
        new ProductImportRowService(
            receiptRepository,
            categoryQueryUseCase,
            imageStorageUseCase,
            registrationService,
            properties);
  }

  @Test
  void dryRunValidatesImageWithoutWritingAnything() {
    UUID sellerId = UUID.randomUUID();
    ProductImportRow row = row();
    given(receiptRepository.findBySellerIdAndExternalId(sellerId, row.externalId()))
        .willReturn(Optional.empty());
    given(source.read("package", row.thumbnailFile(), 10L * 1024 * 1024)).willReturn(jpeg());

    ProductImportRowResult result = service.process(sellerId, row, source, "package", true);

    assertThat(result.status()).isEqualTo(ProductImportItemStatus.VALIDATED);
    then(imageStorageUseCase).shouldHaveNoInteractions();
    then(registrationService).shouldHaveNoInteractions();
  }

  @Test
  void realRunStoresImageAndDelegatesTransactionalRegistration() {
    UUID sellerId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID dropId = UUID.randomUUID();
    ProductImportRow row = row();
    given(receiptRepository.findBySellerIdAndExternalId(sellerId, row.externalId()))
        .willReturn(Optional.empty());
    given(source.read("package", row.thumbnailFile(), 10L * 1024 * 1024)).willReturn(jpeg());
    given(imageStorageUseCase.store(jpeg(), "0001.jpg")).willReturn("stored-thumbnail.jpg");
    given(registrationService.register(sellerId, row, "stored-thumbnail.jpg", List.of()))
        .willReturn(ProductImportRowResult.imported(productId, dropId));

    ProductImportRowResult result = service.process(sellerId, row, source, "package", false);

    assertThat(result.productId()).isEqualTo(productId);
    assertThat(result.dropId()).isEqualTo(dropId);
  }

  @Test
  void rejectsImageWhoseContentDoesNotMatchExtension() {
    UUID sellerId = UUID.randomUUID();
    ProductImportRow row = row();
    given(receiptRepository.findBySellerIdAndExternalId(sellerId, row.externalId()))
        .willReturn(Optional.empty());
    given(source.read("package", row.thumbnailFile(), 10L * 1024 * 1024))
        .willReturn(new byte[] {1, 2, 3});

    assertThatThrownBy(() -> service.process(sellerId, row, source, "package", true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JPG, PNG, WebP");
    then(imageStorageUseCase)
        .should(never())
        .store(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  private static ProductImportRow row() {
    return new ProductImportRow(
        2,
        "demo-0001",
        "서울 한정판 시계",
        "설명",
        null,
        null,
        189000,
        "images/0001.jpg",
        List.of(),
        179000L,
        50,
        2,
        Instant.parse("2030-01-01T00:00:00Z"),
        Instant.parse("2030-01-08T00:00:00Z"));
  }

  private static byte[] jpeg() {
    return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01};
  }
}

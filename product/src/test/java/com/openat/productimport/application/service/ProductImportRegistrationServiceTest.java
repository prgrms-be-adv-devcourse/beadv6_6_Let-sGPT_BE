package com.openat.productimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.openat.drop.application.dto.DropCreateCommand;
import com.openat.drop.application.usecase.DropCommandUseCase;
import com.openat.product.application.dto.ProductCreateCommand;
import com.openat.product.application.usecase.ProductCommandUseCase;
import com.openat.productimport.application.dto.ProductImportRow;
import com.openat.productimport.domain.model.ProductImportItemStatus;
import com.openat.productimport.domain.model.ProductImportReceipt;
import com.openat.productimport.infrastructure.persistence.ProductImportReceiptRepository;
import java.time.Instant;
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
class ProductImportRegistrationServiceTest {

  @Mock private ProductCommandUseCase productCommandUseCase;
  @Mock private DropCommandUseCase dropCommandUseCase;
  @Mock private ProductImportReceiptRepository receiptRepository;

  private ProductImportRegistrationService service;

  @BeforeEach
  void setUp() {
    service =
        new ProductImportRegistrationService(
            productCommandUseCase, dropCommandUseCase, receiptRepository);
  }

  @Test
  void registersProductAndDropThenStoresIdempotencyReceipt() {
    UUID sellerId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID dropId = UUID.randomUUID();
    ProductImportRow row = row();
    given(receiptRepository.findBySellerIdAndExternalId(sellerId, row.externalId()))
        .willReturn(Optional.empty());
    given(productCommandUseCase.create(any(ProductCreateCommand.class))).willReturn(productId);
    given(dropCommandUseCase.create(any(DropCreateCommand.class))).willReturn(dropId);

    var result = service.register(sellerId, row, "thumb-key", List.of("gallery-key"));

    assertThat(result.status()).isEqualTo(ProductImportItemStatus.IMPORTED);
    assertThat(result.productId()).isEqualTo(productId);
    assertThat(result.dropId()).isEqualTo(dropId);
    ArgumentCaptor<ProductImportReceipt> receiptCaptor =
        ArgumentCaptor.forClass(ProductImportReceipt.class);
    then(receiptRepository).should().saveAndFlush(receiptCaptor.capture());
    assertThat(receiptCaptor.getValue().getExternalId()).isEqualTo("demo-0001");
    assertThat(receiptCaptor.getValue().getProductId()).isEqualTo(productId);
    assertThat(receiptCaptor.getValue().getDropId()).isEqualTo(dropId);
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
        List.of("images/0001-side.jpg"),
        179000L,
        50,
        2,
        Instant.parse("2030-01-01T00:00:00Z"),
        Instant.parse("2030-01-08T00:00:00Z"));
  }
}

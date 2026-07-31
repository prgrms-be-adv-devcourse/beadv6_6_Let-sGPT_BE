package com.openat.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.openat.category.domain.error.CategoryErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.product.application.dto.ProductCreateCommand;
import com.openat.product.application.dto.ProductUpdateCommand;
import com.openat.product.application.usecase.ImageStorageUseCase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("상품 명령 서비스")
class ProductCommandServiceTest {

  @InjectMocks private ProductCommandService productCommandService;
  @Mock private ImageStorageUseCase imageStorageUseCase;
  @Mock private ProductCommandTransaction commandTransaction;

  @Test
  @DisplayName("등록 입력을 먼저 검증하고 이미지를 승격한 뒤 쓰기 트랜잭션에 전달한다")
  void create_withImages_promotesBeforeWriteTransaction() {
    UUID sellerId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    String stagingThumbnail = "staging/thumb.png";
    String stagingImage = "staging/detail.png";
    ProductCreateCommand command =
        new ProductCreateCommand(
            sellerId,
            "상품",
            "설명",
            categoryId,
            10_000L,
            stagingThumbnail,
            List.of(stagingImage));
    given(imageStorageUseCase.promote(stagingThumbnail)).willReturn("final/thumb.png");
    given(imageStorageUseCase.promote(stagingImage)).willReturn("final/detail.png");
    given(
            commandTransaction.create(
                command, "final/thumb.png", List.of("final/detail.png")))
        .willReturn(productId);

    UUID result = productCommandService.create(command);

    assertThat(result).isEqualTo(productId);
    InOrder order = inOrder(commandTransaction, imageStorageUseCase);
    order.verify(commandTransaction).validateCreate(categoryId);
    order.verify(imageStorageUseCase).promote(stagingThumbnail);
    order.verify(imageStorageUseCase).promote(stagingImage);
    order.verify(commandTransaction)
        .create(command, "final/thumb.png", List.of("final/detail.png"));
  }

  @Test
  @DisplayName("등록 사전 검증에 실패하면 이미지를 승격하거나 쓰기를 시작하지 않는다")
  void create_invalidCategory_stopsBeforeImagePromotion() {
    UUID categoryId = UUID.randomUUID();
    ProductCreateCommand command =
        new ProductCreateCommand(
            UUID.randomUUID(), "상품", null, categoryId, null, "staging/thumb.png", null);
    BusinessException failure = new BusinessException(CategoryErrorCode.NOT_FOUND);
    willThrow(failure).given(commandTransaction).validateCreate(categoryId);

    assertThatThrownBy(() -> productCommandService.create(command)).isSameAs(failure);

    then(imageStorageUseCase).shouldHaveNoInteractions();
    then(commandTransaction).should(never()).create(any(), anyString(), anyList());
  }

  @Test
  @DisplayName("수정 입력을 먼저 검증하고 이미지를 승격한 뒤 쓰기 트랜잭션에 전달한다")
  void update_withImages_promotesBeforeWriteTransaction() {
    UUID productId = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();
    ProductUpdateCommand command =
        new ProductUpdateCommand(
            productId,
            sellerId,
            "수정 상품",
            "수정 설명",
            null,
            20_000L,
            "staging/thumb.png",
            List.of("staging/detail.png"));
    given(imageStorageUseCase.promote("staging/thumb.png")).willReturn("final/thumb.png");
    given(imageStorageUseCase.promote("staging/detail.png")).willReturn("final/detail.png");

    productCommandService.update(command);

    InOrder order = inOrder(commandTransaction, imageStorageUseCase);
    order.verify(commandTransaction).validateUpdate(productId, sellerId, null);
    order.verify(imageStorageUseCase).promote("staging/thumb.png");
    order.verify(imageStorageUseCase).promote("staging/detail.png");
    order.verify(commandTransaction)
        .update(command, "final/thumb.png", List.of("final/detail.png"));
  }

  @Test
  @DisplayName("수정 사전 검증에 실패하면 이미지를 승격하거나 쓰기를 시작하지 않는다")
  void update_invalidOwner_stopsBeforeImagePromotion() {
    UUID productId = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();
    ProductUpdateCommand command =
        new ProductUpdateCommand(
            productId, sellerId, "수정", null, null, null, "staging/thumb.png", null);
    BusinessException failure =
        new BusinessException(com.openat.product.domain.error.ProductErrorCode.NOT_OWNER);
    willThrow(failure)
        .given(commandTransaction)
        .validateUpdate(productId, sellerId, null);

    assertThatThrownBy(() -> productCommandService.update(command)).isSameAs(failure);

    then(imageStorageUseCase).shouldHaveNoInteractions();
    then(commandTransaction).should(never()).update(any(), anyString(), anyList());
  }

  @Test
  @DisplayName("삭제는 이미지 외부 호출 없이 쓰기 트랜잭션에 위임한다")
  void delete_delegatesToWriteTransaction() {
    UUID productId = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();

    productCommandService.delete(productId, sellerId);

    then(commandTransaction).should().delete(productId, sellerId);
    then(imageStorageUseCase).shouldHaveNoInteractions();
  }
}

package com.openat.drop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.openat.common.exception.BusinessException;
import com.openat.drop.application.dto.DropCreateCommand;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.event.DropDeletedEvent;
import com.openat.drop.domain.event.DropRegisteredEvent;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.product.application.usecase.ProductQueryUseCase;
import com.openat.product.domain.error.ProductErrorCode;
import com.openat.product.domain.event.ProductDeletedEvent;
import com.openat.product.domain.model.Product;
import com.openat.product.fixture.ProductFixture;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("드롭 명령 서비스")
class DropCommandServiceTest {

  @InjectMocks private DropCommandService dropCommandService;
  @Mock private DropRepository dropRepository;
  @Mock private ProductQueryUseCase productQueryUseCase;
  @Mock private ApplicationEventPublisher eventPublisher;

  @Test
  @DisplayName("소유 상품으로 등록하면 조회한 상품과 REGISTERED 상태로 드롭을 저장한다")
  void create_ownedProduct_savesDrop() {
    // given
    UUID sellerId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID savedId = UUID.randomUUID();
    Product product = ProductFixture.persisted(productId, sellerId);
    given(productQueryUseCase.getOwnedProduct(productId, sellerId)).willReturn(product);
    given(dropRepository.save(any(Drop.class)))
        .willAnswer(
            invocation -> {
              Drop drop = invocation.getArgument(0);
              ReflectionTestUtils.setField(drop, "id", savedId);
              return drop;
            });

    // when
    UUID result = dropCommandService.create(command(sellerId, productId));

    // then
    assertThat(result).isEqualTo(savedId);
    ArgumentCaptor<Drop> dropCaptor = ArgumentCaptor.forClass(Drop.class);
    then(dropRepository).should().save(dropCaptor.capture());
    assertThat(dropCaptor.getValue().getProduct()).isEqualTo(product);
    assertThat(dropCaptor.getValue().getStatus()).isEqualTo(DropStatus.REGISTERED);
    then(eventPublisher).should().publishEvent(any(DropRegisteredEvent.class));
  }

  @Test
  @DisplayName("소유하지 않은 상품이면 예외가 전파되고 저장하지 않는다")
  void create_notOwned_throwsAndDoesNotSave() {
    // given
    UUID sellerId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    given(productQueryUseCase.getOwnedProduct(productId, sellerId))
        .willThrow(new BusinessException(ProductErrorCode.NOT_OWNER));

    // when & then
    assertThatThrownBy(() -> dropCommandService.create(command(sellerId, productId)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.NOT_OWNER);
    then(dropRepository).should(never()).save(any());
    then(eventPublisher).should(never()).publishEvent(any());
  }

  @Test
  @DisplayName("오픈 전 드롭을 삭제하면 soft delete하고 삭제 이벤트를 발행한다")
  void delete_beforeOpen_softDeletesAndPublishesEvent() {
    // given
    UUID sellerId = UUID.randomUUID();
    UUID dropId = UUID.randomUUID();
    Drop drop = ownedDrop(sellerId, Instant.now().plusSeconds(3600));
    given(dropRepository.findById(dropId)).willReturn(Optional.of(drop));

    // when
    dropCommandService.delete(dropId, sellerId);

    // then
    then(dropRepository).should().delete(drop);
    then(eventPublisher).should().publishEvent(any(DropDeletedEvent.class));
    assertThat(drop.getStatus()).isEqualTo(DropStatus.REGISTERED);
  }

  @Test
  @DisplayName("오픈 후 드롭을 삭제하면 CLOSE로 종료하고 종료 이벤트를 발행한다(soft delete 아님)")
  void delete_afterOpen_closesAndPublishesClosedEvent() {
    // given
    UUID sellerId = UUID.randomUUID();
    UUID dropId = UUID.randomUUID();
    Drop drop = ownedDrop(sellerId, Instant.now().minusSeconds(3600));
    given(dropRepository.findById(dropId)).willReturn(Optional.of(drop));

    // when
    dropCommandService.delete(dropId, sellerId);

    // then
    assertThat(drop.getStatus()).isEqualTo(DropStatus.CLOSE);
    then(eventPublisher).should().publishEvent(any(DropClosedEvent.class));
    then(dropRepository).should(never()).delete(any());
  }

  @Test
  @DisplayName("이미 종료된 드롭을 삭제하면 아무것도 하지 않는다(멱등)")
  void delete_alreadyClosed_isNoOp() {
    // given
    UUID sellerId = UUID.randomUUID();
    UUID dropId = UUID.randomUUID();
    Drop drop = ownedDrop(sellerId, Instant.now().minusSeconds(3600));
    drop.close();
    given(dropRepository.findById(dropId)).willReturn(Optional.of(drop));

    // when
    dropCommandService.delete(dropId, sellerId);

    // then
    then(dropRepository).should(never()).delete(any());
    then(eventPublisher).should(never()).publishEvent(any());
  }

  @Test
  @DisplayName("소유하지 않은 드롭이면 NOT_OWNER 예외를 던지고 삭제하지 않는다")
  void delete_notOwner_throws() {
    // given
    UUID sellerId = UUID.randomUUID();
    UUID dropId = UUID.randomUUID();
    Drop drop = ownedDrop(UUID.randomUUID(), Instant.now().plusSeconds(3600));
    given(dropRepository.findById(dropId)).willReturn(Optional.of(drop));

    // when & then
    assertThatThrownBy(() -> dropCommandService.delete(dropId, sellerId))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.NOT_OWNER);
    then(dropRepository).should(never()).delete(any());
  }

  @Test
  @DisplayName("존재하지 않는 드롭이면 NOT_FOUND 예외를 던진다")
  void delete_notFound_throws() {
    // given
    UUID sellerId = UUID.randomUUID();
    UUID dropId = UUID.randomUUID();
    given(dropRepository.findById(dropId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> dropCommandService.delete(dropId, sellerId))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.NOT_FOUND);
  }

  @Test
  @DisplayName("상품 삭제 이벤트 시 오픈 드롭이 없으면 그 상품의 드롭을 모두 soft delete하고 삭제 이벤트를 발행한다")
  void onProductDeleted_noLiveDrop_softDeletesAllAndPublishes() {
    // given
    UUID productId = UUID.randomUUID();
    Drop preOpen = ownedDrop(UUID.randomUUID(), Instant.now().plusSeconds(3600));
    Drop closed = ownedDrop(UUID.randomUUID(), Instant.now().plusSeconds(3600));
    closed.close();
    given(dropRepository.findAllByProductId(productId)).willReturn(List.of(preOpen, closed));

    // when
    Instant deletedAt = Instant.parse("2026-07-15T00:00:00Z");
    dropCommandService.onProductDeleted(new ProductDeletedEvent(productId, deletedAt));

    // then
    then(dropRepository).should().delete(preOpen);
    then(dropRepository).should().delete(closed);
    then(eventPublisher).should(times(2)).publishEvent(any(DropDeletedEvent.class));
  }

  @Test
  @DisplayName("상품 삭제 시 진행 중(오픈) 드롭이 있으면 OPEN_EXISTS 예외를 던지고 아무 드롭도 삭제하지 않는다")
  void onProductDeleted_hasLiveDrop_throwsAndDeletesNothing() {
    // given
    UUID productId = UUID.randomUUID();
    Drop live = ownedDrop(UUID.randomUUID(), Instant.now().minusSeconds(3600));
    given(dropRepository.findAllByProductId(productId)).willReturn(List.of(live));

    // when & then
    assertThatThrownBy(
            () ->
                dropCommandService.onProductDeleted(
                    new ProductDeletedEvent(productId, Instant.parse("2026-07-15T00:00:00Z"))))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.OPEN_EXISTS);
    then(dropRepository).should(never()).delete(any());
  }

  private Drop ownedDrop(UUID sellerId, Instant openAt) {
    return Drop.schedule()
        .product(ProductFixture.persisted(UUID.randomUUID(), sellerId))
        .dropPrice(10_000L)
        .totalQuantity(100)
        .openAt(openAt)
        .build();
  }

  private DropCreateCommand command(UUID sellerId, UUID productId) {
    return new DropCreateCommand(
        sellerId, productId, 10_000L, 100, 2, Instant.parse("2026-07-01T00:00:00Z"), null);
  }
}

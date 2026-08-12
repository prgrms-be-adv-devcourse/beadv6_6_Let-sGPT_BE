package com.openat.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.openat.product.domain.event.SellerStoreProjectionChangedEvent;
import com.openat.product.domain.model.SellerStoreProjection;
import com.openat.product.domain.repository.SellerStoreProjectionRepository;
import com.openat.support.lock.SearchProjectionReferenceLock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("판매자 상점 투영 명령 서비스")
class SellerStoreProjectionCommandServiceTest {

  @InjectMocks private SellerStoreProjectionCommandService service;
  @Mock private SellerStoreProjectionRepository repository;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private SearchProjectionReferenceLock searchProjectionReferenceLock;

  @Test
  @DisplayName("없는 투영은 참조 잠금 안에서 저장하고 변경 이벤트를 발행한다")
  void upsert_absent_savesAndPublishes() {
    UUID sellerInfoId = UUID.randomUUID();
    given(repository.findByIdForUpdate(sellerInfoId)).willReturn(Optional.empty());

    service.upsert(sellerInfoId, "스프링 스튜디오");

    InOrder lockOrder = inOrder(searchProjectionReferenceLock, repository);
    lockOrder.verify(searchProjectionReferenceLock).lockSellerForReferenceWrite(sellerInfoId);
    lockOrder.verify(repository).findByIdForUpdate(sellerInfoId);
    ArgumentCaptor<SellerStoreProjection> projectionCaptor =
        ArgumentCaptor.forClass(SellerStoreProjection.class);
    then(repository).should().save(projectionCaptor.capture());
    assertThat(projectionCaptor.getValue().getStoreName()).isEqualTo("스프링 스튜디오");
    then(eventPublisher)
        .should()
        .publishEvent(new SellerStoreProjectionChangedEvent(sellerInfoId));
  }

  @Test
  @DisplayName("상점명이 바뀌면 기존 투영을 변경하고 변경 이벤트를 발행한다")
  void upsert_changedName_appliesAndPublishes() {
    UUID sellerInfoId = UUID.randomUUID();
    SellerStoreProjection projection = projection(sellerInfoId, "기존 이름");
    given(repository.findByIdForUpdate(sellerInfoId)).willReturn(Optional.of(projection));

    service.upsert(sellerInfoId, "새 이름");

    assertThat(projection.getStoreName()).isEqualTo("새 이름");
    then(repository).should(never()).save(any());
    then(eventPublisher)
        .should()
        .publishEvent(new SellerStoreProjectionChangedEvent(sellerInfoId));
  }

  @Test
  @DisplayName("상점명이 같으면 검색 스냅샷 갱신 이벤트를 발행하지 않는다")
  void upsert_sameName_ignores() {
    UUID sellerInfoId = UUID.randomUUID();
    SellerStoreProjection projection = projection(sellerInfoId, "같은 이름");
    given(repository.findByIdForUpdate(sellerInfoId)).willReturn(Optional.of(projection));

    service.upsert(sellerInfoId, "같은 이름");

    then(repository).should(never()).save(any());
    then(eventPublisher).shouldHaveNoInteractions();
  }

  private SellerStoreProjection projection(UUID sellerInfoId, String storeName) {
    return SellerStoreProjection.project()
        .sellerInfoId(sellerInfoId)
        .storeName(storeName)
        .build();
  }
}

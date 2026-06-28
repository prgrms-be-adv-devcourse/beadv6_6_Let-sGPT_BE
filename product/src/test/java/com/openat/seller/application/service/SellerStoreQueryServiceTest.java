package com.openat.seller.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.openat.seller.domain.model.SellerStore;
import com.openat.seller.domain.repository.SellerStoreRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("판매자 스토어 조회 서비스")
class SellerStoreQueryServiceTest {

  @InjectMocks private SellerStoreQueryService sellerStoreQueryService;
  @Mock private SellerStoreRepository sellerStoreRepository;

  @Test
  @DisplayName("투영된 스토어들의 표시명을 id별 맵으로 반환한다")
  void findStoreNames_returnsMapById() {
    // given
    UUID storeId = UUID.randomUUID();
    SellerStore sellerStore =
        SellerStore.project().sellerInfoId(storeId).storeName("오픈앳 스튜디오").build();
    given(sellerStoreRepository.findAllById(List.of(storeId))).willReturn(List.of(sellerStore));

    // when
    Map<UUID, String> storeNames = sellerStoreQueryService.findStoreNames(List.of(storeId));

    // then
    assertThat(storeNames).containsEntry(storeId, "오픈앳 스튜디오");
  }

  @Test
  @DisplayName("빈 id 목록이면 저장소를 조회하지 않고 빈 맵을 반환한다")
  void findStoreNames_empty_returnsEmptyMapWithoutQuery() {
    // when
    Map<UUID, String> storeNames = sellerStoreQueryService.findStoreNames(List.of());

    // then
    assertThat(storeNames).isEmpty();
    then(sellerStoreRepository).should(never()).findAllById(any());
  }
}

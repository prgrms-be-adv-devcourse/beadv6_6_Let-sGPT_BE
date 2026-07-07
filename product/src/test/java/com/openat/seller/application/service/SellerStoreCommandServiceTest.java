package com.openat.seller.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.openat.seller.domain.model.SellerStore;
import com.openat.seller.domain.repository.SellerStoreRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("판매자 스토어 명령 서비스")
class SellerStoreCommandServiceTest {

  @InjectMocks private SellerStoreCommandService sellerStoreCommandService;
  @Mock private SellerStoreRepository sellerStoreRepository;

  @Test
  @DisplayName("투영에 없는 스토어를 upsert하면 새로 저장한다")
  void upsert_absent_savesNew() {
    // given
    UUID sellerInfoId = UUID.randomUUID();
    given(sellerStoreRepository.findById(sellerInfoId)).willReturn(Optional.empty());

    // when
    sellerStoreCommandService.upsert(sellerInfoId, "오픈앳 스튜디오");

    // then
    then(sellerStoreRepository).should().save(any(SellerStore.class));
  }

  @Test
  @DisplayName("이미 있는 스토어를 upsert하면 표시명만 변경하고 저장하지 않는다")
  void upsert_present_changesNameWithoutSave() {
    // given
    UUID sellerInfoId = UUID.randomUUID();
    SellerStore existing =
        SellerStore.project().sellerInfoId(sellerInfoId).storeName("옛 이름").build();
    given(sellerStoreRepository.findById(sellerInfoId)).willReturn(Optional.of(existing));

    // when
    sellerStoreCommandService.upsert(sellerInfoId, "새 이름");

    // then
    assertThat(existing.getStoreName()).isEqualTo("새 이름");
    then(sellerStoreRepository).should(never()).save(any());
  }
}

package com.openat.drop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.DropRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("드롭 종료 서비스")
class DropCloseServiceTest {

  @InjectMocks private DropCloseService dropCloseService;
  @Mock private DropRepository dropRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  @Test
  @DisplayName("진행 중 드롭을 종료하면 CLOSE로 전이하고 종료 이벤트를 발행한다")
  void close_active_closesAndPublishesEvent() {
    // given
    UUID dropId = UUID.randomUUID();
    Drop drop = drop();
    given(dropRepository.findById(dropId)).willReturn(Optional.of(drop));

    // when
    dropCloseService.close(dropId);

    // then
    assertThat(drop.getStatus()).isEqualTo(DropStatus.CLOSE);
    then(eventPublisher).should().publishEvent(any(DropClosedEvent.class));
  }

  @Test
  @DisplayName("이미 종료된 드롭이면 아무것도 하지 않는다(멱등)")
  void close_alreadyClosed_isNoOp() {
    // given
    UUID dropId = UUID.randomUUID();
    Drop drop = drop();
    drop.close();
    given(dropRepository.findById(dropId)).willReturn(Optional.of(drop));

    // when
    dropCloseService.close(dropId);

    // then
    then(eventPublisher).should(never()).publishEvent(any());
  }

  @Test
  @DisplayName("존재하지 않는 드롭이면 아무것도 하지 않는다")
  void close_notFound_isNoOp() {
    // given
    UUID dropId = UUID.randomUUID();
    given(dropRepository.findById(dropId)).willReturn(Optional.empty());

    // when
    dropCloseService.close(dropId);

    // then
    then(eventPublisher).should(never()).publishEvent(any());
  }

  private Drop drop() {
    return Drop.schedule()
        .product(null)
        .dropPrice(10_000L)
        .totalQuantity(100)
        .openAt(Instant.parse("2026-07-01T00:00:00Z"))
        .build();
  }
}

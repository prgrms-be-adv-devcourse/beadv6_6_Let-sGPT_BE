package com.openat.payment.infrastructure.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// 종결불가 행 백오프 레지스트리 — 미종결 등록 시 건너뛰기, 지수 증가, 종결 해제, 상한 초과 축출을 검증.
class TtlBackoffRegistryTest {

  @Test
  void 처음_보는_행은_건너뛰지_않는다() {
    TtlBackoffRegistry registry = new TtlBackoffRegistry();
    UUID id = UUID.randomUUID();

    assertFalse(registry.shouldSkip(id, LocalDateTime.now()));
  }

  @Test
  void 미종결로_등록하면_다음_시도_시각_전까지_건너뛴다() {
    TtlBackoffRegistry registry = new TtlBackoffRegistry();
    UUID id = UUID.randomUUID();
    LocalDateTime now = LocalDateTime.now();

    registry.recordUnresolved(id, now); // 1회 미종결 → 2분 백오프

    assertTrue(registry.shouldSkip(id, now.plusSeconds(30))); // 2분 안 — 건너뜀
    assertFalse(registry.shouldSkip(id, now.plusMinutes(3))); // 2분 지남 — 재시도 대상
  }

  @Test
  void 연속_미종결이면_백오프가_지수적으로_늘어난다() {
    TtlBackoffRegistry registry = new TtlBackoffRegistry();
    UUID id = UUID.randomUUID();
    LocalDateTime now = LocalDateTime.now();

    registry.recordUnresolved(id, now); // 1회 → 2분
    registry.recordUnresolved(id, now); // 2회 → 4분

    // 3분 시점: 2분 백오프였다면 지났겠지만, 4분으로 늘었으므로 아직 건너뛴다.
    assertTrue(registry.shouldSkip(id, now.plusMinutes(3)));
    assertFalse(registry.shouldSkip(id, now.plusMinutes(5)));
  }

  @Test
  void 종결에_성공하면_레지스트리에서_제거돼_즉시_재대상이_된다() {
    TtlBackoffRegistry registry = new TtlBackoffRegistry();
    UUID id = UUID.randomUUID();
    LocalDateTime now = LocalDateTime.now();

    registry.recordUnresolved(id, now);
    assertTrue(registry.shouldSkip(id, now.plusSeconds(30)));

    registry.recordResolved(id);

    assertFalse(registry.shouldSkip(id, now.plusSeconds(30)));
    assertEquals(0, registry.size());
  }

  @Test
  void 엔트리_상한을_넘기면_오래된_것을_축출해_상한을_유지한다() {
    TtlBackoffRegistry registry = new TtlBackoffRegistry(2);
    LocalDateTime now = LocalDateTime.now();

    registry.recordUnresolved(UUID.randomUUID(), now);
    registry.recordUnresolved(UUID.randomUUID(), now);
    registry.recordUnresolved(UUID.randomUUID(), now);

    assertEquals(2, registry.size());
  }
}

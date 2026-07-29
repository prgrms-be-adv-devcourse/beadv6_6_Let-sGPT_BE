package com.openat.payment.infrastructure.scheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 종결불가(poison) 행 완화용 인메모리 백오프 레지스트리.
// 배경: TTL 스캐너는 '오래된 순' 선두부터 회수하는데, 영원히 종결 못 하는 행(PG NOT_FOUND, 원 Payment 부재
// Refund 등)이 그 선두를 매 주기 다시 점유하면 그 뒤의 '확정 가능한' 행이 영영 스캔되지 못하는 livelock이
// 생긴다. 처리 시도 후에도 PENDING으로 남는 행은 여기에 지수 백오프로 등록해 두고, 다음 시도 시각 전까지는
// 스캔에서 건너뛰어 뒤 행에 자리를 내준다.
//
// 이 완화의 한계: 상태(연속 미종결 횟수·다음 시도 시각)를 인메모리로만 들고 있어 파드 재시작 시 리셋된다.
// 완화 목적상 허용한다 — 재시작 후 몇 주기 동안 poison 행을 다시 시도하다 재등록될 뿐이다. 근본 수정(백오프
// 상태를 스키마 컬럼으로 영속화해 조회 SQL에서 아예 제외)은 별도 안건으로 남긴다.
public class TtlBackoffRegistry {

  // 엔트리 상한 — 초과 시 다음 시도 시각이 가장 이른(곧 재시도될, 잃어도 손실이 가장 작은) 엔트리부터 제거.
  private final int maxEntries;
  // 백오프 상한 — 아무리 여러 번 실패해도 이 간격을 넘겨 미루지 않는다(그 사이 회복될 수도 있으므로).
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
  // 지수 계산 시 시프트 오버플로 방지용 상한(2^30분이면 이미 MAX_BACKOFF를 한참 넘는다).
  private static final int MAX_SHIFT = 30;

  private static final class Entry {
    private int misses;
    private LocalDateTime nextAttemptAt;
  }

  private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

  public TtlBackoffRegistry() {
    this(10_000);
  }

  public TtlBackoffRegistry(int maxEntries) {
    this.maxEntries = maxEntries;
  }

  // 지금 이 행을 스캔에서 건너뛰어야 하는가 — 백오프 중이고 아직 다음 시도 시각 전이면 true.
  public boolean shouldSkip(UUID id, LocalDateTime now) {
    Entry e = entries.get(id);
    return e != null && e.nextAttemptAt != null && e.nextAttemptAt.isAfter(now);
  }

  // 처리 시도했지만 여전히 종결 못 한(PENDING 유지) 행 — 연속 미종결 횟수를 늘리고 다음 시도 시각을
  // now + min(2^횟수 분, 30분)으로 미룬다.
  public void recordUnresolved(UUID id, LocalDateTime now) {
    entries.compute(
        id,
        (k, existing) -> {
          Entry e = existing != null ? existing : new Entry();
          e.misses++;
          long backoffMinutes =
              Math.min(1L << Math.min(e.misses, MAX_SHIFT), MAX_BACKOFF.toMinutes());
          e.nextAttemptAt = now.plusMinutes(backoffMinutes);
          return e;
        });
    evictIfOverCapacity();
  }

  // 종결에 성공했거나 다른 경로가 이미 확정한 행 — 레지스트리에서 제거해 다음에 새 행처럼 즉시 대상이 되게 한다.
  public void recordResolved(UUID id) {
    entries.remove(id);
  }

  public int size() {
    return entries.size();
  }

  private void evictIfOverCapacity() {
    while (entries.size() > maxEntries) {
      entries.entrySet().stream()
          .min(Comparator.comparing(en -> en.getValue().nextAttemptAt))
          .map(Map.Entry::getKey)
          .ifPresent(entries::remove);
    }
  }
}

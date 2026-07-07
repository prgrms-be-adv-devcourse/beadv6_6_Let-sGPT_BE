# 재고 게이트키퍼 설계서 (Stock Gatekeeper)

> 한정 수량 드롭(Drop)의 선착순 재고 차감을 담당하는 **상품(product) 도메인** 설계임.
> 결정 근거는 [`DECISIONS.md`](DECISIONS.md), 전역 계약은 [`../../docs/PROJECT.md`](../../docs/PROJECT.md), 모듈 구조는 [`PRODUCT.md`](PRODUCT.md)를 따른다.
> 세부 구현값(키 네이밍·TTL·Lua 인자 레이아웃 등)은 §13에 모았고 구현 시 확정한다.

---

## 1. 개요 및 역할
- 주문 도메인이 호출하는 **내부 동기 API**로, "오픈된 드롭에 차감 가능한 재고가 있는지"를 판정하고 선점함.
- **책임 경계:** 재고 선점(차감)·복원(롤백)·1인 한도·오픈 판정에 집중함. **결제 타임아웃 감지와 사가 오케스트레이션은 주문(order) 책임**이며, 상품은 롤백 API만 제공하고 **재고 관련 이벤트를 발행하지 않는다**(재고 통신은 동기 API 단일 경로).

---

## 2. 핵심 전략
- **Redis 캐시(라이브) + Lua(원자) 게이트키퍼:** 활성(오픈/임박) 드롭의 상태·잔여를 캐시가 1차 관리하고, [멱등·1인 한도·재고]를 Lua 단일 원자 단위로 확인·차감해 레이스를 차단함.
- **재고 SSOT = 이력 원장(`stock_histories`, append-only):** 잔여 스냅샷 컬럼을 두지 않고 `total_quantity + SUM(quantity_delta)`로 계산함. 라이브 값은 Redis, 영구 진실은 이력 합.
- **핫패스 쓰기 = INSERT만:** 당첨자마다 `drops`를 UPDATE하지 않음(핫로우 직렬화 회피). 새 이력 행 INSERT는 무경합이라 병렬.
- **기록이 응답보다 먼저:** Redis 차감 → 이력 INSERT 커밋 → 응답. "응답 후 기록 전" 구간을 제거함.

---

## 3. 데이터 구조
- **RDB(원장):**
  - `drops` — 메타(`total_quantity`·`drop_price`·`open_at`·`close_at`(nullable)·`limit_per_user`(nullable)) + `status`.
  - `stock_histories` — append-only, 부호 있는 `quantity_delta`(DEDUCT 음수 / ROLLBACK 양수), `UNIQUE(order_id, change_type)`(멱등), `INDEX(drop_id)`.
- **캐시(Redis):**
  - `drop:{id}` — 라이브 `remaining`, `open_at`, `close_at`, `limit_per_user`.
  - `drop:{id}:buyers` — `buyerId → 누적 구매 수량`(1인 한도).
  - `order:{orderId}` — 차감 멱등 결과(L1).
  - `order:{orderId}:rollback` — 롤백 멱등 결과(L1). 차감·롤백이 한 주문에 공존하므로 키를 분리한다.

---

## 4. 드롭 상태 (영구 마일스톤 + 런타임 파생)
- **DB `drops.status`에는 영구 마일스톤만 저장한다 — `REGISTERED`(등록) → `CLOSE`(종료).**
- **`OPEN`·`SOLD_OUT`은 저장하지 않고 런타임에 파생한다**(시각 + 캐시 `remaining`):

  | 표기 | 조건 |
  | :-- | :-- |
  | 예정 | `REGISTERED` + `now < openAt` |
  | `OPEN` | `REGISTERED` + `openAt ≤ now < closeAt`(closeAt 없으면 상한 없음) + `remaining > 0` |
  | `SOLD_OUT` | 위 오픈 시각 조건 + `remaining = 0` |
  | `CLOSE` | `status = CLOSE` |

- **파생 표현 노출:** `OPEN`·`SOLD_OUT`은 `DropStatus` enum 값을 재사용하되 **DB `status` 컬럼엔 저장하지 않고**, 조회 응답 매핑 시 위 표대로 계산해 내려줌. DB `status`가 갖는 값은 `REGISTERED`·`CLOSE`뿐.
- **`SOLD_OUT`은 가역적이다:** 선점(차감) 후 결제 실패/타임아웃으로 롤백되면 재고가 복원되어 다시 구매 가능해짐. 그래서 매진을 DB에 박지 않고 캐시 `remaining`으로만 관리함(핫로우 토글 회피).

### 엔티티 조정 (기존 코드 → 설계 반영)
- `DropStatus`: 기존 `SCHEDULED`를 **`REGISTERED`로 rename**. `OPEN`·`SOLD_OUT`은 enum 값으로 두되 위 파생 표현 전용(DB 미저장).
- `Drop.status` 초기값 = `REGISTERED`(`schedule()` 빌더).
- 상태전이 메서드는 **`close()`**(`REGISTERED → CLOSE`; `closeAt` 종료 예약·판매자 취소에서 호출)만 추가. `OPEN`·매진 전이 메서드는 두지 않음(파생).
- `Drop.closeAt`(nullable) 유지(기간 한정 — `DECISIONS #7`).

---

## 5. 드롭 등록 & 캐시 워밍·스케줄링

**드롭 등록 (판매자):**
- `POST /api/v1/drops`, 판매자(`@CurrentUser` sellerId) + `@Valid`. **상품 등록(PRODUCT §8) 패턴 동일** — `201 Created` + `Location`, `Drop.schedule()` 빌더로 생성.
- product 존재·소유 확인: `ProductQueryUseCase.getOwnedProduct(productId, sellerId)`로 **Product 엔티티**를 조회해 `Drop`의 `@ManyToOne`에 연결한다. 게이트웨이 신뢰는 **sellerId의 진위**(회원↔판매자 매핑)만 보증하므로, "그 상품이 이 판매자의 것인지"(product↔seller 소유)는 product가 검증한다 — 없으면 `PRODUCT_NOT_FOUND`, 남의 상품이면 `PRODUCT_NOT_OWNER`(남의 상품에 드롭 등록 차단).
- 입력 검증: `openAt` 미래, `closeAt > openAt`(있을 때), `totalQuantity > 0`, `dropPrice > 0`, `limitPerUser > 0`(nullable=무제한).
- 등록 직후 워밍/종료 예약을 TaskScheduler에 등록(아래).

**캐시 적재·스케줄링:**
- **사전 적재:** `openAt - 5분`(설정값) 시점에 캐시 적재. 적재(준비)와 오픈(판정)은 분리되어, 미리 올려도 오픈 전 차감은 Lua가 시각비교로 거절함 → 오픈 순간 캐시 미스 0.
- **잔여 산정 — 두 개념, 한 식:** 잔여는 항상 `total + SUM(이력)` 단일 식으로 적재한다. 적재 경로의 개념은 둘로 갈리지만 같은 식으로 안전하게 수렴한다.
  - **TaskScheduler 오픈 적재(정상):** 집계가 사실상 불필요하다 — 오픈 전엔 캐시가 없어 차감이 불가(Lua 거절)하므로 이력이 **항상 0건**이고, `SUM=0`이라 `remaining = total`(최초 등록 수량이 곧 재고)이 된다. `REGISTERED → 오픈`은 생애 1회.
  - **복구 적재(콜드부팅·캐시 장애):** 라이브 캐시가 유실된 상태라 집계가 **반드시 필요**하다 — `remaining = total + SUM(이력)`으로 잔여를 복원하고 `buyers`는 `buyer_id` GROUP BY로 재구성한다(이력 1회 스캔으로 잔여·카운터 동시 집계).
  - 구현(`DropCacheWarmer`)은 두 경로를 분기하지 않고 항상 집계식을 쓴다 — 정상 오픈 땐 이력이 0건이라 결과가 `total`과 같고, "최초인지 복구인지"를 코드로 식별하려면 어차피 이력 조회가 필요해 분기보다 단일 식이 단순·안전하다.
- **스케줄러 = TaskScheduler(예약 기반):** 등록 시 워밍 예약(`openAt-5m`) + 종료 예약(`closeAt`, nullable이라 있을 때만)을 `schedule`. 부팅 시 DB에서 미래 드롭을 재등록하고, `Map<dropId, ScheduledFuture>`로 수정·취소를 관리. 콜드부팅 복구는 `ApplicationRunner`로 부팅 시 1회 재워밍. (정밀 타이밍은 Lua 시각판정이 책임지므로 스케줄러 지연·중복은 무해)
- **다중 인스턴스:** 워밍 중복 방지는 필요해지면 도입(워밍 `SET NX` 멱등 가드 또는 Redis ZSET 원자 pop).

---

## 6. 재고 차감 (내부 API)
- `POST /internal/drops/{dropId}/stock-deductions`, body `{orderId, buyerId, quantity}`.
- **Lua 원자 처리:** ⓪ 캐시 존재 확인(`drop:{id}` 없으면 `DROP_NOT_CACHED` 거절 — 워밍 전/비활성) → ① 멱등(`order:{orderId}` 존재 시 기존 결과 반환) → ② 오픈 판정(`openAt ≤ now < closeAt`) → ③ 1인 한도(`buyers[buyerId] + quantity > limit`) → ④ 재고(`remaining ≥ quantity`) → ⑤ 차감(`remaining-`, `buyers[buyerId]+`, 멱등키 SET).
- **동기 기록:** Lua 성공 → `stock_histories` INSERT(DEDUCT) 커밋 → `200 {remainingQuantity}`.
- **거절:** `409 DROP_SOLD_OUT`·`DROP_NOT_CACHED`·`DROP_CLOSED` / `400 DROP_NOT_OPEN`·`DROP_LIMIT_EXCEEDED`. RDB 미도달(thundering herd 차단).
- **INSERT 실패(UNIQUE 외):** Redis 보상 롤백(역연산) 후 `5xx`.
- 현재시각(`now`)은 앱이 ARGV로 전달(다중 인스턴스는 NTP 동기화 가정).

---

## 7. 재고 롤백 (내부 API)
- `POST /internal/drops/{dropId}/stock-rollbacks`, body `{orderId, buyerId, quantity}`.
- **주문(order)이 트리거** — 결제 실패·타임아웃·환불(결제 완료 후 취소)에 공용으로 호출.
- **오픈 중:** Lua 복원(`remaining+`, `buyers[buyerId]-`) + `stock_histories` INSERT(ROLLBACK).
- **종료(CLOSE) 후:** close가 캐시를 `markClosed`만 하고 즉시 evict하지 않으므로, **drain 창(캐시 TTL) 동안 in-flight 롤백은 라이브 캐시로 정상 복원·기록**된다(신규만 차단, in-flight 취소 허용 — §8). 캐시 만료 뒤엔 `NOT_CACHED`로 떨어지고 DB `status = CLOSE`면 **no-op**(복원·기록 안 함 — 재판매 없어 무의미, 주문 측은 환불로 사후 처리). 장애(캐시만 유실)면 복구 재워밍이 이력 합산으로 자동 반영.
- **멱등:** L1(`order:{orderId}:rollback` — 차감 키와 분리) + L2(`UNIQUE(order_id, ROLLBACK)`). 한 주문당 롤백 1회.

---

## 8. 종료
- **트리거 2가지:** ① 판매자 삭제(오픈 후 → `DELETE`가 `CLOSE`를 겸함, §11·DECISIONS 2026-06-26 #1) ② `closeAt` 종료 예약(TaskScheduler). 둘 다 **`status = CLOSE` + 캐시 `markClosed`** — 드롭 해시 `closeAt = now`로 신규 선점만 거절하고(Lua 시각판정), **이미 선점한 in-flight는 캐시 TTL(drain 창) 동안 유지**한다(`evict` 즉시 아님). evict는 **오픈 전 삭제**(soft delete) 정리에만 쓴다.
- **선점 완료 주문은 close와 무관하게 결제까지 진행**됨 — close는 "신규 선점만 차단", in-flight 완료·취소는 drain 창 안에서 허용(상세한 취소 컷은 주문 사가 책임 — §7). product는 close 시점에 해당 드롭의 재고 책임을 종료함.
- **매진은 종료가 아님**(가역) — closeAt 도래 또는 판매자 취소로만 종료.
- (기간 한정 가치를 위해 `closeAt`(nullable)을 유지하되, 없으면 매진/취소까지 무기한. 수량 희소성이 기본, 기간은 선택 — `DECISIONS #7`)

---

## 9. 내부 API 계약
- **경로 prefix 우회:** `@InternalApi` 마커를 붙인 컨트롤러를 `WebConfig`의 `/api/v1` prefix predicate에서 제외함(외부는 `/api/v1`, 내부는 `/internal` 원형). 외부 차단은 게이트웨이가 담당.
- **인증:** 내부 호출이라 `@CurrentUser`(헤더) 대신 호출 측(order)이 `buyerId`를 body로 전달(게이트웨이 외부 차단 + order가 인증된 주문자 검증).
- **응답:** 성공 `200 {remainingQuantity}` — 단, 롤백이 라이브 캐시 없이 처리되면(종료 후 no-op 등) 복원할 라이브 잔여가 없어 **본문 없이 `204 No Content`**로 응답한다(`PROJECT §8`·`PRODUCT §8`의 "본문 없는 응답=204" 컨벤션). 실패는 HTTP 상태로 구분 — `4xx`(영구 실패: 품절·미오픈·한도·종료 → 보상) / `5xx`(일시 오류 → 재시도)로 사가가 분기. 본문은 전역 `ErrorResponse`.

---

## 10. 정합성·안전 원칙
- **오버셀 불가:** Lua 단일 스레드 원자 실행으로 `remaining < quantity`면 차감하지 않음.
- **멱등 2계층:** L1 Redis 멱등키(핫패스 빠른 멱등, 재고 이중 차감·보상 회피) + L2 DB `UNIQUE`(권위·영속 안전망).
- **안전 편향:** 모든 예외/모호는 거절(undercount)로 기울고 오버셀로는 절대 가지 않음.
- **매진 가역:** 선점 → 롤백 → 복원이 정상 흐름. product의 이력은 "선점 추적"이고, 판매량·정산은 `order_completed_events`(결제 완료 기준)라는 별도 소스가 담당.

---

## 11. 장애·복구
- **콜드부팅/캐시 장애:** OPEN 드롭을 `total + SUM(이력)`으로 재워밍, `buyers`는 GROUP BY로 재구성(트래픽 수용 전 완료).
- **유령 차감**(Redis 차감 후 이력 INSERT 전 크래시): 보수적 거절 상태로 남고, 재워밍 시 이력 기준으로 사라져 자가 치유됨. 해당 주문은 order가 타임아웃 처리.
- **`closeAt=null` 드롭:** 활동 기반 TTL + 만료 시 lazy 복구로 캐시 수명을 관리(종료 후 트래픽이 적어 thundering herd 없음). 구체 정책은 §13.

---

## 12. 부하 테스트 및 검증
- **k6:** 대규모 동시성 하 TPS·p95/p99 측정. 선착순 경쟁에서 오버셀 완전 차단, 캐시 다운 시 RDB 원장 기반 재정합(재워밍)을 집중 검증.
- **정합성 테스트:** 동시 차감·1인 한도·중복 차감 방지를 Testcontainers(Redis+PG) + `ExecutorService`로 단언(`TEST_CONVENTION` §8).

---

## 13. 구현 시 구체화 과제
- Redis 키 네이밍·자료구조 세부, Lua 스크립트 내부 로직·인자(KEYS/ARGV) 레이아웃, 보상 시퀀스.
- L1 멱등키 **TTL = 주문 saga 최대 수명**(차감→결제 완료/타임아웃) 기준 — `closeAt`이 아니라 주문 수명에 맞춘다(종료된 주문 키를 드롭 기간 내내 보관하지 않기 위함; saga 종료 후 재차감은 비정상이라 L2 `UNIQUE`가 최종 방어선). 1인 한도 카운터는 드롭 캐시와 동일 TTL.
- `closeAt=null` 캐시 정리(활동 TTL/배치)·워밍 실패 재시도.
- `DropErrorCode` 클라 노출 `code`는 도메인 접두사 `DROP_`를 **일관 적용**(`DROP_NOT_OPEN`/`DROP_SOLD_OUT`/`DROP_LIMIT_EXCEEDED`/`DROP_CLOSED`/`DROP_NOT_CACHED`). enum 상수명은 접두사 없이(`NOT_OPEN`·`CLOSED`·`NOT_CACHED` 등) 두고 `code`에만 접두사를 붙여 `ProductErrorCode` 패턴과 맞춘다.
- 판매자 삭제 = **단일 `DELETE` 엔드포인트**로 구현(취소·삭제 통합): 오픈 전이면 soft delete(`PRODUCT §11`), 오픈 후면 `CLOSE` 전이(종료·데이터 유지, §8). 종료 캐시 정리는 evict가 아니라 `markClosed`(drain). (DECISIONS 2026-06-26 #1)
- 드롭 등록 시 product 엔티티 반환 포트(`ProductQueryUseCase` 확장 vs 별도), 등록 검증(openAt 미래, closeAt > openAt).
- 마감 드롭 판매 리포팅(이력 보존 의존, 데이터 증가 시 적재 검토).
- 구매자 조회 API(목록 = `REGISTERED/CLOSE` + 캐시 `remaining` 파생 덧칠 / 상세 = 캐시 라이브; **범위 2순위**).
- 복구 시 1인 한도 카운터 = `buyer_id` GROUP BY `SUM(-quantity_delta)`(순구매; `DEDUCT −q`·`ROLLBACK +q`라 부호 반전). 보상 롤백 역연산 = `remaining+`·`buyers[buyerId]-`·멱등키 DEL. 둘 다 Lua KEYS/ARGV 레이아웃과 함께 구현 시 확정.

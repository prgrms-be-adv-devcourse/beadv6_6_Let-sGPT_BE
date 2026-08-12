# 재고 게이트키퍼 설계서 (Stock Gatekeeper)

> 한정 수량 드롭(Drop)의 선착순 재고 차감을 담당하는 **상품(product) 도메인** 설계임.
> 결정 근거는 [`DECISIONS.md`](DECISIONS.md), 전역 계약은 [`../../docs/PROJECT.md`](../../docs/PROJECT.md), 모듈 구조는 [`PRODUCT.md`](PRODUCT.md)를 따른다.
> 2026-07-27 `dev` 구현 기준. Redis 키·TTL·Lua 계약과 현재 남은 운영 위험은 §13에 모았다.

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
  - **복구 적재(콜드부팅·캐시 장애):** 라이브 캐시가 유실된 상태라 집계가 **반드시 필요**하다 — `remaining = total + SUM(이력)`으로 잔여를 복원하고 `buyers`는 `buyer_id` GROUP BY로 재구성한다.
  - 구현(`DropCacheWarmer`)은 두 경로를 분기하지 않고 항상 집계식을 쓴다 — 정상 오픈 땐 이력이 0건이라 결과가 `total`과 같고, "최초인지 복구인지"를 코드로 식별하려면 어차피 이력 조회가 필요해 분기보다 단일 식이 단순·안전하다.
- **권위 스냅샷:** 워밍 트랜잭션은 drop 행의 비관적 쓰기 잠금과 PostgreSQL `REPEATABLE_READ`로 생명주기 변경 및 두 원장 집계의 관측 시점을 고정한다. Redis는 단일 `warm.lua`가 drop·buyers 두 해시를 삭제 후 전체 재작성하고 같은 TTL을 부여해 유령 buyer와 부분 적재를 남기지 않는다. 이 계약은 오픈 전 예약 워밍, 단일 replica 기동 복구, 신규 차감이 막힌 삭제 롤백 복구 경로를 대상으로 하며 live 다중 replica 재워밍까지 보장하지 않는다.
- **스케줄러 = TaskScheduler(예약 기반):** 등록 시 워밍 예약(`openAt-5m`) + 종료 예약(`closeAt`, nullable이라 있을 때만)을 `schedule`. 부팅 시 DB의 모든 `REGISTERED` 드롭을 다시 등록하고, `Map<dropId, ScheduledFuture>`로 수정·취소를 관리. 콜드부팅 복구는 `ApplicationRunner`로 부팅 시 1회 재워밍한다. 예약 종료의 DB·캐시 동기화가 실패하면 설정 간격 뒤 다시 시도한다. (정밀 타이밍은 Lua 시각판정이 책임지므로 스케줄러 지연·중복은 무해)
- **다중 인스턴스:** 워밍 중복 방지는 필요해지면 도입(워밍 `SET NX` 멱등 가드 또는 Redis ZSET 원자 pop).

---

## 6. 재고 차감 (내부 API)
- `POST /internal/drops/{dropId}/stock-deductions`, body `{orderId, buyerId, quantity}`.
- **Lua 원자 처리:** ⓪ 캐시 존재 확인(`drop:{id}` 없으면 `DROP_NOT_CACHED` 거절 — 워밍 전/비활성) → ① 멱등(`order:{orderId}` 존재 시 기존 결과 반환) → ② 오픈 판정(`openAt ≤ now < closeAt`) → ③ 1인 한도(`buyers[buyerId] + quantity > limit`) → ④ 재고(`remaining ≥ quantity`) → ⑤ 차감(`remaining-`, `buyers[buyerId]+`, 멱등키 SET).
- **동기 기록:** Lua 성공 → `stock_histories` INSERT(DEDUCT) 커밋 → `200 {remainingQuantity}`.
- **중복 완료 판정:** Lua의 `DUPLICATE`는 Redis 처리 흔적일 뿐 완료 증거가 아니다. 같은 `orderId`·변경 유형의 L2 원장이 커밋돼 있고 `dropId`·`buyerId`·`quantity`까지 일치할 때만 기존 성공을 반환한다. 원장이 아직 보이지 않으면 `503 DROP_STOCK_CHANGE_IN_PROGRESS`, 튜플이 다르면 `409 DROP_STOCK_REQUEST_MISMATCH`로 거절한다.
- **L1 만료 뒤 L2 충돌:** Redis 멱등키가 만료·유실되어 Lua가 다시 `OK`를 반환해도 DB UNIQUE 충돌을 곧바로 성공으로 간주하지 않는다. 방금 적용한 캐시 변경을 먼저 보상하고 기존 원장의 전체 요청 튜플을 검증한다. 일치할 때만 멱등 성공, 불일치는 `409 DROP_STOCK_REQUEST_MISMATCH`, 충돌 원장이 조회되지 않는 비정상 제약 실패는 원래 저장 예외를 전파한다.
- **라이브 재고 지표:** 성공한 라이브 재고 요청이 drop별 Gauge를 한 번만 등록하고, 측정 시점마다 권위 있는 Redis 현재 잔여를 읽는다. 응답·DB 커밋 완료 순서가 Redis 변경 순서와 달라도 과거 응답값으로 Gauge가 역행하지 않으며, 캐시가 없거나 조회할 수 없으면 `NaN`으로 노출한다.
- **거절:** `409 DROP_SOLD_OUT`·`DROP_NOT_CACHED`·`DROP_CLOSED` / `400 DROP_NOT_OPEN`·`DROP_LIMIT_EXCEEDED`. RDB 미도달(thundering herd 차단).
- **INSERT 실패(UNIQUE 외):** Redis 보상 롤백(역연산) 후 `5xx`.
- 차감의 오픈 판정과 종료·오픈 전 삭제 fence는 모두 Lua 실행 시점의 Redis `TIME`을 사용해 노드 시계 편차 없이 같은 원자 경계에서 순서를 판정한다.

---

## 7. 재고 롤백 (내부 API)
- `POST /internal/drops/{dropId}/stock-rollbacks`, body `{orderId, buyerId, quantity}`.
- **주문(order)이 트리거** — 결제 실패·타임아웃·환불(결제 완료 후 취소)에 공용으로 호출.
- **선행 차감 검증:** RDB의 DEDUCT 이력을 권위 있게 조회하고 `orderId`·`dropId`·`buyerId`·`quantity`가 요청과 모두 같을 때만 복원을 시작한다. 이력이 없거나 튜플이 다르면 Redis를 변경하지 않고 `409 DROP_ROLLBACK_NOT_ALLOWED`로 거절한다.
- **오픈 중:** 선행 차감 검증 후 Lua 복원(`remaining+`, `buyers[buyerId]-`) + `stock_histories` INSERT(ROLLBACK).
- **종료(CLOSE) 후:** close가 캐시를 `markClosed`만 하고 즉시 evict하지 않으므로, **drain 창(캐시 TTL) 동안 in-flight 롤백은 라이브 캐시로 정상 복원·기록**된다(신규만 차단, in-flight 취소 허용 — §8). 캐시 만료 뒤엔 `NOT_CACHED`로 떨어지고 DB `status = CLOSE`면 **no-op**(복원·기록 안 함 — 재판매 없어 무의미, 주문 측은 환불로 사후 처리). 활성 드롭에서 캐시만 유실된 경우에는 ROLLBACK 이력을 DB에 기록하지만 즉시 재워밍하지는 않으며 다음 부팅 워밍 때 원장 합계로 복구한다.
- **멱등:** L1(`order:{orderId}:rollback` — 차감 키와 분리) + L2(`UNIQUE(order_id, ROLLBACK)`). 롤백 트랜잭션은 선행 DEDUCT 원장 행을 `PESSIMISTIC_WRITE`로 잠가 같은 주문의 선행 검증 → Redis 복원 → ROLLBACK 원장 확정을 replica 사이에서도 직렬화한다. ROLLBACK INSERT는 독립 트랜잭션으로 확정해 기존 UNIQUE 충돌 보상 계약을 유지한다. 잠금을 얻은 뒤 커밋된 ROLLBACK 원장을 Redis보다 먼저 확인하며, 요청 튜플이 일치하면 캐시를 다시 변경하지 않고 현재 라이브 잔여만 반환한다(캐시가 없으면 `204`). 따라서 L1 키가 만료·유실돼도 뒤 요청은 캐시를 재복원하지 않는다. 방어적인 L2 충돌 경로는 방금 적용한 캐시 변경을 보상한 뒤 원장 튜플이 일치할 때만 실제 잔여를 응답하고, 불일치는 `409 DROP_STOCK_REQUEST_MISMATCH`다.

---

## 8. 종료
- **트리거 2가지:** ① 판매자 삭제(오픈 후 → `DELETE`가 `CLOSE`를 겸함, §11·DECISIONS 2026-06-26 #1) ② `closeAt` 종료 예약(TaskScheduler). 둘 다 **`status = CLOSE` + 캐시 `markClosed`** — 드롭 해시 `closeAt = now`로 신규 선점만 거절하고(Lua 시각판정), **이미 선점한 in-flight는 캐시 TTL(drain 창) 동안 유지**한다(`evict` 즉시 아님). evict는 **오픈 전 삭제**(soft delete) 정리에만 쓴다.
- **커밋 경계:** close와 오픈 전 soft delete는 drop 행 잠금을 잡은 트랜잭션의 `BEFORE_COMMIT`에서 각각 `markClosed`·`evict`를 완료한다. delete fence Lua는 실행 시점의 Redis 시각과 캐시 `openAt`을 차감과 같은 원자 경계에서 비교한다. 이미 오픈 경계를 지났으면 evict를 거절해 직접 삭제와 상품 하향 삭제 트랜잭션을 모두 롤백하고, 그 사이 성공한 차감과 buyers를 보존한다. Redis 실패도 DB 커밋을 막아 DB만 CLOSE/삭제되고 캐시가 판매 가능한 상태로 남는 구간을 만들지 않는다. DB 롤백 시 close는 원래 `closeAt`만 복구해 drain 중 `remaining`·buyers 변경을 보존하고, delete는 원장 스냅샷으로 재워밍한다. 프로세스 중단 등 롤백 복구도 실패하면 다음 기동의 `REGISTERED` 워밍이 수렴시키며, `AFTER_COMMIT` 리스너는 인메모리 예약 취소만 담당한다.
- **drain TTL:** `markClosed`는 drop과 buyers의 남은 TTL이 `close-margin`보다 짧을 때만 최소 drain 여유까지 연장한다. 더 긴 TTL과 무기한 TTL은 줄이지 않는다.
- **선점 완료 주문은 close와 무관하게 결제까지 진행**됨 — close는 "신규 선점만 차단", in-flight 완료·취소는 drain 창 안에서 허용(상세한 취소 컷은 주문 사가 책임 — §7). product는 close 시점에 해당 드롭의 재고 책임을 종료함.
- **매진은 종료가 아님**(가역) — closeAt 도래 또는 판매자 취소로만 종료.
- **도메인 판매 기간:** `closeAt`이 없으면 종료 시각 없이 매진 또는 판매자 취소까지 판매하는 계약이다(수량 희소성이 기본, 기간은 선택 — `DECISIONS #7`). 다만 현재 Redis 캐시는 7일 뒤 만료되고 lazy 재적재가 없어, 7일을 넘기는 판매에는 운영 재적재나 구현 보강이 필요하다(§11·§13).

---

## 9. 내부 API 계약
- **경로 prefix 우회:** `@InternalApi` 마커를 붙인 컨트롤러를 `WebConfig`의 `/api/v1` prefix predicate에서 제외함(외부는 `/api/v1`, 내부는 `/internal` 원형). 외부 차단은 게이트웨이가 담당.
- **인증:** 내부 호출이라 `@CurrentUser`(헤더) 대신 호출 측(order)이 `buyerId`를 body로 전달(게이트웨이 외부 차단 + order가 인증된 주문자 검증).
- **응답:** 성공 `200 {remainingQuantity}` — 단, 롤백이 라이브 캐시 없이 처리되면(종료 후 no-op 등) 복원할 라이브 잔여가 없어 **본문 없이 `204 No Content`**로 응답한다(`PROJECT §8`·`PRODUCT §8`의 "본문 없는 응답=204" 컨벤션). product는 `4xx`를 영구 실패(품절·미오픈·한도·종료·선행 차감 불일치·중복 요청 튜플 불일치), `5xx`를 일시 오류(원장 커밋 전 중복 포함) 의미로 제공하며 본문은 전역 `ErrorResponse`다. 현재 order 호출자는 새 정합성 오류 코드를 세밀하게 매핑하지 않으므로, 호출자 계약이 보강되기 전에는 일부 `4xx`가 통합 실패·재시도로 축약될 수 있다.

---

## 10. 정합성·안전 원칙
- **오버셀 불가:** Lua 단일 스레드 원자 실행으로 `remaining < quantity`면 차감하지 않음.
- **멱등 2계층:** L1 Redis 멱등키(핫패스 빠른 중복 감지, 재고 이중 차감·보상 회피) + L2 DB `UNIQUE`(권위 있는 완료 판정·영속 안전망). 성공 응답은 L2 커밋 뒤에만 확정한다.
- **복원 상한 보호:** 롤백은 권위 있는 DEDUCT 이력과 요청 튜플의 완전 일치를 선행 조건으로 삼고 그 원장 행 잠금을 L2 확정까지 유지해, 존재하지 않거나 다른 주문의 차감 및 동시 재시도로 재고를 늘리지 않는다.
- **안전 편향:** 모든 예외/모호는 거절(undercount)로 기울고 오버셀로는 절대 가지 않음.
- **매진 가역:** 선점 → 롤백 → 복원이 정상 흐름. product의 이력은 "선점 추적"이고,
  확정 판매량 조정은 `order.stock.adjusted.events`, 정산 적재는
  `payment.settlement.events`라는 별도 소스가 담당한다.

---

## 11. 장애·복구
- **콜드부팅:** `DropBootstrapRunner`가 모든 `REGISTERED` 드롭을 다시 스케줄한다. 워밍 시점이 이미 지났으면 즉시 `total + SUM(이력)`과 buyer GROUP BY의 동일 DB 스냅샷을 Redis에 완전 교체한다. 커밋 전 cache fence 뒤 프로세스가 중단돼 DB가 `REGISTERED`로 남은 경우 stale close·evict 상태도 이 경로로 복구한다.
- **런타임 캐시 유실:** 현재 요청 경로에는 자동 lazy 재워밍이 없다. 열린 드롭도 `DROP_NOT_CACHED`로 거절되고, active 롤백은 DB 원장만 보정한다. 재기동 또는 별도 운영 재워밍이 필요하다.
- **유령 차감**(Redis 차감 후 이력 INSERT 전 크래시): 보수적 거절 상태로 남고, 재워밍 시 이력 기준으로 사라져 자가 치유됨. 해당 주문은 order가 타임아웃 처리.
- **`closeAt=null` 드롭:** 현재 캐시 TTL은 7일 고정이며 활동 기반 갱신이나 만료 시 lazy 복구가 없다. 7일을 넘겨 계속 판매할 드롭은 운영 재워밍 또는 구현 보강이 필요하다.

---

## 12. 부하 테스트 및 검증
- **k6:** 현재 `loadtest/k6/drop-flow.js`는 배포 Gateway에서
  드롭→대기열→주문→WireMock PG 흐름의 TPS·p95/p99와 결과 분포를 측정하고 PG 결함을
  주입한다. 실행 후 원장·잔여를 대조하는 오버셀 단언과 Redis 중단·재워밍 실험은
  스크립트에 포함돼 있지 않다.
- **정합성 테스트:** `DropCacheRedisAdaptorTest`가 Testcontainers Redis +
  `ExecutorService`로 동시 차감·원자 snapshot 교체·drain TTL을 검증한다.
  `DropStockConsistencyIntegrationTest`는 Testcontainers PostgreSQL·Redis를 함께 사용해 완료 롤백과
  동시 최초 롤백의 DB 잠금·L1 키 유실·교차 차감, 실제 동시 차감의 오버셀·ghost buyer 방지,
  lifecycle cache 실패의 DB 롤백, 삭제 fence의 오픈 경계 경합, 롤백·기동 복구,
  종료 드롭의 상품 삭제 뒤 drain 롤백을 검증한다(`TEST_CONVENTION` §8).

---

## 13. 현재 구현값과 남은 운영 과제

### 확정 구현값

- Redis key: `drop:{dropId}`, `drop:{dropId}:buyers`, `order:{orderId}`, `order:{orderId}:rollback`.
- Lua: `redis/deduct.lua`, `rollback.lua`, `compensate.lua`, `warm.lua`, `close.lua`, `restore_close.lua`, `evict_before_open.lua`. 차감·롤백·보상 외에도 워밍 전체 교체, close fence·롤백 복구, 실행 시각 기준 오픈 전 evict를 각각 원자 처리한다.
- 기본 설정: `warm-before=5m`, `close-margin=10m`, `null-close-ttl=7d`, `idempotency-ttl=1h`, `close-retry-delay=10s`.
- `TaskScheduler` pool size는 2다. 등록은 커밋 후 예약하고, 삭제·종료는 커밋 전 cache fence 성공 뒤 DB를 확정하며 커밋 후 예약만 취소한다.
- 차감·롤백 이력은 `UNIQUE(order_id, change_type)`로 L2 멱등성을 보장한다. 롤백은 DEDUCT 원장 행 잠금으로 같은 주문을 L2 확정까지 직렬화한다. Redis 효과와 DB 기록이 충돌하면 Lua 역연산으로 캐시를 보상하고, L1 중복은 L2 원장 커밋과 튜플 일치를 확인한다.
- `DropErrorCode`는 `DROP_NOT_OPEN`, `DROP_SOLD_OUT`, `DROP_LIMIT_EXCEEDED`, `DROP_CLOSED`, `DROP_NOT_CACHED`, `DROP_STOCK_CHANGE_IN_PROGRESS`, `DROP_STOCK_REQUEST_MISMATCH`, `DROP_ROLLBACK_NOT_ALLOWED`를 실제 응답 코드로 사용한다.
- 단일 `DELETE` 경로는 오픈 전 soft delete, 오픈 뒤 `CLOSE` 전이로 동작한다. 상품 하향 삭제도 오픈 전 캐시만 evict하고 이미 오픈했던 종료 드롭의 캐시는 TTL 동안 drain 용도로 보존한다. 구매자 목록·상세 조회와 seller 소유 검증도 구현돼 있다.

### 남은 운영 위험

- 여러 product replica가 같은 드롭을 각각 스케줄·워밍하며 분산 멱등 가드는 없다.
- 런타임 Redis 유실과 `closeAt=null` 7일 TTL 만료에 대한 자동 재워밍 경로가 없다.
- 워밍 작업 실패에 대한 별도 재시도·알림이 없다.
- 이력 증가에 따른 재워밍 집계 비용과 마감 드롭 판매 리포팅 적재 전략은 별도 검토가 필요하다.

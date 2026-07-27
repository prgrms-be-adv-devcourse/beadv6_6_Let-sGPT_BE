# PROJECT.md — 한정 수량 드롭 커머스 플랫폼 (openAt)

> 이 저장소의 **프로젝트 정보**(도메인·아키텍처·기술 스택·컨벤션·엔티티)를 정의한다.
> 코드를 작성·리뷰할 때 항상 이 문서의 규칙과 맥락을 따른다.
> **프로젝트 정보가 새로 정해지거나 바뀌면 이 파일을 갱신한다.** (작업 방식·지침은 로컬 `AGENTS.md`)

---

## 1. 배경

- 프로그래머스 KDT 7기 "Spring AI와 MSA를 활용한 백엔드 개발" 팀 프로젝트 (**5인, 1인 1도메인**).
- **세미(14일) → 파이널**로 동일 코드베이스를 이어서 확장 (총 약 40일).
- 팀 원칙: 다양한 기술을 얕게보다 **깊은 기술 경험**을 중시.
- 핵심 도전: 굿즈·한정판을 정해진 시각에 한정 수량으로 오픈(드롭). **오픈 순간 동시 트래픽에서 재고·결제 정합성 유지**가 본질.

---

## 2. 서비스(도메인)

| 서비스 | 포트 | 책임 |
|---|---|---|
| 회원(member) | 9100 | 회원가입·로그인·JWT, 판매자 등록(Role), **공통 모듈 주도** |
| 상품(product) | 9110 | 상품·드롭 등록/조회, 드롭 상태 관리, 재고 감소/롤백(내부 API) |
| 주문(order) | 9120 | 주문 요청, **사가 오케스트레이션**, 재고 보상, 취소·환불 요청 |
| 결제(payment) | 9130 | 지갑·PG 결제, 충전·환불, PG 대사, 결과·정산 이벤트 발행 |
| 정산(settlement) | 9140 | 이벤트 기반 판매 적재, 일 대사, Spring Batch 월 정산 |
| 대기열(queue) | 9150 | Redis 기반 드롭 대기열, 재고 인지형 입장권·부분 수량 선택 |
| AI(ai) | 9160 | 개인화 추천과 관리자 챗봇 — read-model·외부 도구·MVC SSE·자체 추론 서버 연동 |
| 검색(search) | 9240 | 상품 이벤트 색인, Elasticsearch 벡터·조건 검색, 이미지 분석 |

- 실제 포트 할당: 핵심 도메인은 **10단위 증가**(`9100`~`9140`). queue는 Elasticsearch 기본 포트 `9200`과의 충돌을 피해 `9150`, ai는 `9160`, search는 `9240`을 사용한다. Gateway는 `8000`, AI management 포트는 `9161`이다.

---

## 3. 아키텍처 원칙 (반드시 준수)

### 클린 아키텍처 (계층 구조)

- 각 서비스는 **클린 아키텍처**를 따른다. 의존성은 **항상 안쪽(도메인)으로만** 향한다 (바깥 계층이 안쪽을 알고, 안쪽은 바깥을 모른다).
- 계층(패키지) 매핑:

  | 계층 | 패키지 | 책임 |
  |---|---|---|
  | 도메인 | `domain` (`model`·`repository`(인터페이스)·`exception`) | 핵심 비즈니스 규칙·엔티티. 가장 안쪽 |
  | 애플리케이션 | `application` (`usecase`·`service`·`dto`) | 유스케이스 조합, 도메인 오케스트레이션 |
  | 인프라 | `infrastructure` (`persistence` 등) | DB·외부 연동 등 기술 세부 구현 (도메인 인터페이스 구현) |
  | 프레젠테이션 | `presentation` (`controller`·`dto`·`exception`) | 외부 진입점(REST). 얇게 유지 |

#### 허용된 예외 (Architecture Exceptions)

> 순수 클린 아키텍처 원칙을 의도적으로 완화한 항목. 비용 대비 이득을 따져 합의한 예외만 여기에 적는다. **여기 없는 위반은 금지.**

1. **모듈별 JPA 매핑 방식** — member·product·order·settlement는 도메인 모델에 JPA 매핑을 직접 적용하고, payment는 도메인 모델과 `infrastructure.persistence.entity`를 분리한다. queue는 JPA를 사용하지 않으며 search의 JPA 모델은 전체 재색인용 read-model이다. 프로젝트 전체에 하나의 영속화 방식을 가정하지 않고 각 모듈의 현재 구조를 따른다.
2. **AI 관리자 조회용 read-model** — 현재 구현은 같은 물리 DB에서 관리자 집계를 제공하기 위한 교차 도메인 조회 예외 후보다. `ai`가 `ai_read` DDL·기동 검증·조회 어댑터를 소유하고, 전용 계정 `ai_query_app`은 승인된 비식별 집계 view에만 `SELECT`할 수 있다. 개별 주문은 공개 주문번호 정확 일치와 제한된 반환 열을 강제하는 `SECURITY DEFINER` 함수만 허용하며 전용 계정에는 그 함수의 `EXECUTE`만 부여한다. 원본 스키마·테이블 및 소유자 전용 view 직접 조회와 주 DataSource 폴백은 허용하지 않는다. 로컬 프로필은 기존 주 DB 자격증명으로 누락된 계약을 자동 구성하고, 배포는 DDL 산출물 또는 조회 Secret revision이 달라질 때만 identity가 다른 선행 Job을 실행한다. k3s ConfigMap은 현재 `CHAT_DATA_ENABLED=true`로 기능을 켜고 이 Job을 배포 흐름에 연결한다. 이는 기술적 실행 상태를 뜻하며, 데이터 의미·소유권·변경 통지에 관한 관련 팀의 운영 예외 합의가 끝났다는 뜻은 아니다.
3. **검색 전체 재색인용 product 스키마 조회** — search의 `ProductDbToEsJobConfig`는 `product.products`·`product.categories`를 읽어 Elasticsearch 전체 색인을 복구한다. 평상시 증분 동기화는 `product.created/updated/deleted.events`로 처리한다. 현재 코드는 조회 모델만 정의하지만 DB 계정 차원의 읽기 전용 강제는 없으므로, 이는 현행 구현 사실을 기록한 제한적 예외이며 다른 교차 스키마 조회로 확장하지 않는다.

- **MSA / 데이터 격리:** 서비스는 자기 데이터만 소유. **다른 도메인의 DB·스키마·테이블을 직접 조회·조인하지 말 것.** 타 도메인 데이터는 원칙적으로 **API 호출 또는 이벤트로만** 접근한다. 위 AI read-model과 검색 전체 재색인만 현재 코드에 존재하는 제한적 예외이며 그 밖의 예외는 허용하지 않는다. (FK 대신 "값 참조")
- **API Gateway (Spring Cloud Gateway):** 모든 외부 요청은 Gateway 통과.
  - `/api/**` → 외부 노출. 공개 경로, 일반 access JWT, 판매자 scoped JWT, 역할 제한을
    `SecurityConfig`의 matcher 순서로 구분한다.
  - `/internal/**` → 서비스 간 호출 전용, **외부 차단.**
- **인증(JWT):** 회원 서비스가 **RS256**으로 토큰을 발급하고 Gateway가 **JWKS**(`/auth/jwks`)로 공개키를 받아 검증·인가한 뒤 신뢰 헤더를 주입한다. 클라이언트가 직접 보낸 인증 컨텍스트 헤더는 토큰 유무와 관계없이 먼저 제거한다.
  - **회원 access JWT:** `sub`=memberId, `roles`=접두사 없는 `USER`/`SELLER`/`ADMIN`. Gateway가 `X-User-Id`(memberId)와 `X-User-Roles`(`ROLE_USER,ROLE_SELLER` 형식)를 주입한다. 수신 서비스는 `common`의 `UserContextFilter`·`@CurrentUser UserContext`를 사용한다. (가이드: `common/docs/CURRENT_USER_GUIDE.md`)
  - **판매자 scoped JWT:** 회원 access JWT로 `POST /api/v1/seller/token`을 호출하면 member가 활성 `sellerInfoId` 소유권을 검증하고 `sub`=sellerInfoId, `act.sub`=memberId, `aud`=`openat-product`, `scope`=`product:write`, `typ`=`scoped`인 단기 토큰을 발급한다.
  - **상품 전달 계약:** Gateway는 product 쓰기 경로에서 scoped JWT의 `typ`·`aud`를 검사하고 `X-Seller-Id`(sellerInfoId)만 주입한다. `X-User-Id`·`X-User-Roles`는 전달하지 않으며 memberId는 감사용 `act.sub`에만 남는다. product의 `@CurrentUser UUID`는 `X-Seller-Id`를 바인딩한다. `scope=product:write` claim은 발급되지만 현재 Gateway 인가 조건에는 포함되지 않는다.
  - **헤더 상수:** 세 헤더 모두 `common.auth.UserHeaders`를 사용한다.
- **통신 방식 분리 (핵심 설계):**
  - **동기 (Spring RestClient):** 즉시 성공/실패 판단이나 최신 조회값이 필요한 HTTP 호출. → **주문↔상품의 주문 스냅샷·재고 감소·롤백, payment의 주문 검증, 주문의 결제 상태 조회·환불, settlement의 payment 일 대사 조회.** queue·AI 추천은 현재 product/member/order/search의 공개 조회 API도 서비스 주소로 직접 호출한다.
  - **비동기 (Kafka 이벤트):** 결과를 기다릴 필요 없는 전파. → **결제·환불 결과, 정산 적재, 상품 색인, 대기열 판매량 조정, 추천 신호.** (PG처럼 느리거나 사후 통지 성격)
- **분산 트랜잭션:** **사가(오케스트레이션).** 주문 서비스가 오케스트레이터. 실패 시 **보상 트랜잭션**으로 롤백(재고 복원 등). **멱등키**로 중복/재시도 방지.
- **데이터 정합성:** 최종적 일관성(이벤트) + 보상(사가). **2PC 사용 안 함.**
  - 세미 = 보상 없이 **해피패스만** / 파이널 = **사가 오케스트레이션(보상)** 완성.

---

## 4. 핵심 비즈니스 흐름 (드롭 즉시 주문 사가)

1. 주문 생성 (`status=PAYMENT_PENDING`) → 사가 시작
2. **재고 감소** (상품 서비스 **동기** 호출). 품절·미오픈·드롭종료·한도초과면 주문을 즉시 `FAILED`로 전이
3. 재고 성공 → 클라이언트에 `orderId`·금액·결제 만료 시각 반환. 지갑 결제는 `POST /api/v1/payments`, PG는 토스 SDK 승인 뒤 `POST /api/v1/payments/confirm`으로 진입한다.
4. 결제 결과를 **이벤트로 수신**
   - `payment.completed.events` → 주문 `COMPLETED` 전이 + `order.completed.events` 발행
   - `payment.failed.events` → 실패한 결제 시도를 이력에 남기되 주문은 재시도·만료 판단을 위해 `PAYMENT_PENDING` 유지
   - payment는 `order.completed.events`로 seller/product를 사후 채운 뒤 `payment.settlement.events`를 발행한다.
5. 결제 TTL 경과 → 주문이 payment 내부 조회로 최종 상태를 먼저 확인한다. `APPROVED`면 완료 보정, `FAILED`·`CANCELED`·미결제면 주문 실패와 상품 재고 롤백, 조회 연속 3회 실패면 `PAYMENT_NO_RESPONSE`로 보상한다. 아직 진행 중이면 5분 뒤 재확인한다.
6. 취소·환불
   - `PAYMENT_PENDING` 취소는 `POST /internal/v1/refunds`로 결제 레이스를 확인한다. 미결제면 `CANCELLED` + 재고 복원, 환불 접수면 `CANCEL_REQUESTED`, 결제 진행 중이면 `409 PAYMENT_IN_PROGRESS`다.
   - `COMPLETED` 주문은 `POST /api/v1/orders/{orderId}/refund-requests`로 `CANCEL_REQUESTED`가 되고 payment 내부 환불을 요청한다. 최종 `refund.completed/failed.events`가 `REFUNDED` 또는 `REFUND_FAILED`를 결정한다.
   - 완료 주문에 결제 API로 직접 수행한 **부분 환불**은 주문을 `COMPLETED`로 유지하고 이력만 기록한다.
7. 정산: `payment.settlement.events` 적재 → payment의 매일 01시 PG 대사 → settlement의 매일 02시 payment↔settlement 일 대사 → **Spring Batch** (`0 0 3 1 * *`, 매월 1일 03시)로 수수료·환불 차감 정산. 세 cron은 현재 `zone`을 지정하지 않아 JVM 기본 시간대를 따른다. 정산은 `order.completed.events`를 직접 구독하지 않는다.

---

## 5. 상태값 Enum (정확히 일치시킬 것)

| 대상 | 값 |
|---|---|
| drop | `REGISTERED` / `OPEN` / `CLOSE` / `SOLD_OUT` (DB 저장은 영구 마일스톤 `REGISTERED`·`CLOSE`만, `OPEN`·`SOLD_OUT`은 캐시 잔여+시각으로 파생) |
| order | `PAYMENT_PENDING` / `COMPLETED` / `FAILED` / `CANCELLED` / `CANCEL_REQUESTED` / `REFUND_PENDING`(예약 — 현재 전이 경로 없음) / `REFUNDED` / `REFUND_FAILED` |
| payment | `PENDING` / `PAYMENT_PENDING` / `APPROVED` / `PARTIALLY_REFUNDED` / `FAILED` / `CANCELED` / `REFUNDED` |
| refund | `PENDING` / `COMPLETE` / `FAILED` |
| wallet charge | `PENDING` / `APPROVED` / `FAILED` |
| PG reconciliation | `NOT_CHECKED` / `MATCHED` / `MISMATCH` |
| queue | `READY` / `WAITING` / `NOT_IN_QUEUE` / `SOLD_OUT` / `DECISION_REQUIRED` |
| member role | 엔티티·DB는 `ROLE_USER` / `ROLE_SELLER` / `ROLE_ADMIN`; JWT `roles` claim은 접두사 없는 값, `X-User-Roles`는 다시 `ROLE_` 접두사를 붙인 값 |

서비스 간 문자열로 주고받을 때 값이 어긋나지 않게 한다. Enum 자체는 각 도메인이 설계.

---

## 6. 기술 스택

| 구분 | 채택 | 비고 |
|---|---|---|
| 언어 | **Java 21**, **Kotlin 2.1.20**(queue) | JVM target 21 |
| 프레임워크 | **Spring Boot 4.1.0** | Spring Cloud 2025.1.2 |
| 빌드 | **Gradle (Kotlin DSL)** | 모노레포 멀티모듈, 서비스별 디렉토리 |
| DB | **PostgreSQL** | `runtimeOnly("org.postgresql:postgresql")` |
| DB 구조 | **공유 DB(`openat`) + 서비스별 독립 스키마** | `hibernate.default_schema: <domain>` 원칙. 주문은 예약어 회피로 `orders` 사용 |
| ORM | **Spring Data JPA** (+ QueryDSL 도메인별) | |
| 마이그레이션 | 모듈별 적용 | order·payment는 Flyway + `ddl-auto: validate`; member는 기본 `validate`, local `create-drop`, compose `update`; product·settlement·ai는 현재 `update`; search는 `none` |
| PK | **UUID** | member는 Hibernate `VERSION_7`, payment는 자체 v7 생성기를 쓴다. product·order·settlement는 현재 Hibernate `TIME`(RFC 4122 v1 호환)이라 모듈별 전략이 통일돼 있지 않다 |
| 통신 | **Spring RestClient**(동기 HTTP) / **Apache Kafka**(비동기) | |
| 재고 동시성 | **Redis+Lua 게이트키퍼** | append-only 이력 원장 기반, 설계 단계부터 Redis 캐시+Lua 도입 |
| 캐시·동시성 | **Redis** (Drop 캐시·Lua 원자 처리) | |
| 결제 | **토스페이먼츠**(테스트), 민감정보 **AES-GCM 암호화** | |
| 정산 | **Spring Batch** + Spring Scheduler | |
| 인증/보안 | Spring Security + JWT(jjwt 0.12.3) | |
| 문서 | **springdoc-openapi (Swagger)** | `/swagger-ui.html`, `/api-docs` |
| 인프라 | Docker / GitHub Actions(paths 필터, GHCR) | 세미 = Docker Compose, 파이널 = Kubernetes(K3s) |
| 부하 테스트 | **k6** | |
| 검색·AI | Elasticsearch(역색인+벡터), 자체 추론 서버, 개인화 추천, 관리자 AI 어시스턴트(MVC SSE·운영 문서·외부 도구) | 현재 구현 |

---

## 7. 코드 컨벤션 (반드시 준수)

> **공통 vs 서비스 내부 컨벤션:** 서비스 간 계약·공통 모듈에 닿는 컨벤션(패키지 계층, 네이밍, DTO 접미사, API/이벤트 포맷, 커밋 등)은 **팀 전체가 동일하게 준수**한다. 반면 한 서비스 내부에서만 닫히는 코드 컨벤션(예: 엔티티 생성 방식)은 **담당자가 독립적으로 판단·확정**할 수 있다(현재 product는 본인 담당). 내부 컨벤션을 정하거나 바꿀 때는 근거를 `product/docs/DECISIONS.md`에 남긴다.

- **패키지:** 클린 아키텍처 계층 구조 — `com.openat.<domain>.{domain, application, infrastructure, presentation}` (전부 소문자). 계층별 책임은 [§3 클린 아키텍처](#3-아키텍처-원칙-반드시-준수) 참고.
- **네이밍:** 클래스 `PascalCase`, 메서드·변수 `camelCase`, 상수 `UPPER_SNAKE`. 불리언은 **긍정형**(`isOpen` ⭕ / `isNotClosed` ❌).
- **DB 테이블/엔티티:** 엔티티 클래스는 단수, 신규 테이블은 복수형과 `snake_case`를 기본으로 한다. 다만 현재 member에는 `member`·`role`·`role_history`·`seller_info`·`wishlist_item`, settlement에는 `settlement_batchs`라는 실제 이름이 남아 있다. 이 규칙만으로 기존 테이블명을 추정하거나 임의로 마이그레이션하지 않는다.
- **DTO:**
  - 컨트롤러: 요청 `~Request` / 응답 `~Response` (**`~Dto` 지양**)
  - 서비스: 요청 `~Command` / 반환 `~Info`
- **Lombok:** `@Getter`·`@Builder` 허용. **엔티티에 `@Data`·`@Setter` 지양.**
- **엔티티 생성(product 내부 컨벤션):** 기능명을 진입 메서드로 갖는 빌더(`@Builder(builderMethodName=...)`)로 생성 — `Product.create()…build()`·`Drop.schedule()…build()`·`Category.create()…build()`. 같은 타입 파라미터가 호출부에서 뒤섞이는 것을 필드명 명시로 차단하고, 진입 메서드 이름으로 생성 의도를 표현. (배경·트레이드오프는 `product/docs/DECISIONS.md`)
- **검증 위치:** 입력 검증은 **컨트롤러 `@Valid`(Bean Validation)** 에서 수행하고, **HTTP 진입이 단일 경로라는 가정** 하에 도메인 팩토리의 중복 검증은 생략. (이벤트 컨슈머 등 다른 진입 경로는 [§9](#9-eda이벤트-컨벤션-반드시-준수)의 자체 방어 검증을 따른다.)
- **예외:** 커스텀 예외 계층(`BusinessException` 등 **언체크**) + `@RestControllerAdvice` 전역 처리. 도메인별 에러 enum이 `common.error.ErrorCode`를 구현한다.
- **테스트:** given-when-then, `@DisplayName` 한글 허용. **사가·재고·결제 핵심 로직 우선.** (product 모듈 상세 지침은 [`product/docs/TEST_CONVENTION.md`](../product/docs/TEST_CONVENTION.md))
- **포맷터:** 팀 합의 자동 포맷 도구 적용.

---

## 8. API 컨벤션 (반드시 준수)

- **URL:** 복수형 + 케밥케이스. (`/api/v1/products`, `/internal/drops/{dropId}/stock-deductions`)
- **버전/경계:** 외부 `/api/v1/...`, 내부 `/internal/...`.
- **메서드:** 전체 수정 **PUT** / 일부 수정 **PATCH**.
- **성공 응답:** 봉투 없이 리소스를 그대로 반환(`ResponseEntity<T>`). 상태코드는 HTTP 상태 라인이 단일 기준이며 본문에 중복하지 않음. 생성은 `201 Created` + `Location` 헤더, 본문 없는 응답은 `204 No Content`.

  ```json
  { /* 리소스 본문 */ }
  ```

- **에러 응답:** 도메인별 error enum. **클라 응답의 `error` 필드에는 도메인 접두사를 일관 적용**(예: `DROP_*`·`PRODUCT_*`·`PAYMENT_*`). (HTTP 상태코드는 상태 라인으로 전달)

  ```json
  { "error": "DROP_SOLD_OUT", "message": "재고가 없습니다" }
  ```

  주요 코드 예: `DROP_SOLD_OUT`, `DROP_NOT_OPEN`, `DROP_LIMIT_EXCEEDED`, `PAYMENT_FAILED`.
- **상태코드:** 표준 준수. (품절 `409` / 미오픈 `400` / 없음 `404`)
- **페이징:** 오프셋 기반 `?page=&size=`, 공통 응답 `{ content, page, size, totalElements, totalPages }`.
- **날짜·시간:** JSON은 ISO 8601로 직렬화한다. product·order·search의 외부 시각은 주로
  `Instant`(UTC)를 쓰지만, settlement 조회 응답은 현재 `LocalDateTime`, payment↔settlement
  일 대사 계약은 `OffsetDateTime`을 사용해 전역 타입이 완전히 통일돼 있지 않다. 소비자는
  각 endpoint DTO의 offset 유무를 그대로 따라야 한다.
- **문서:** Swagger(springdoc-openapi).

---

## 9. EDA(이벤트) 컨벤션 (반드시 준수)

- **토픽 네이밍:** 원칙은 `[도메인].[행위].events`, **과거형**이다. (예: `order.completed.events`, `payment.completed.events`) 현재 판매자 스토어 투영용 두 토픽은 예외적으로 underscore 이름(`seller_registered_events`, `seller_updated_events`)을 사용한다.
- **토픽 분리:** 결제·환불 결과처럼 순서와 소비자가 다른 사건은 `payment.completed.events` / `payment.failed.events`처럼 나눈다. 같은 회원의 찜 변경처럼 순서 보장이 우선인 경우에는 `wishlist.changed.events` 한 토픽에 `type`을 둔다.
- **payload:** 현재 구현은 공통 `IntegrationEvent` 봉투를 강제하지 않고 **토픽별 JSON DTO를 평면 직렬화**한다. `eventId`·`eventType`·`occurredAt`은 정산·재고 조정처럼 해당 계약이 요구할 때 payload에 포함한다.
- **직렬화:** JSON. 날짜·시간은 DTO의 `Instant` 또는 `LocalDateTime` 계약을 그대로 사용하므로 생산자·소비자 DTO를 함께 검증한다.
- **이벤트 DTO 위치:** **각 서비스에 복제 정의** (공통 모듈로 공유하지 않음).
- **멱등성:** 소비자가 계약에 맞는 키를 사용한다. order는 Kafka topic/partition/offset 기반 inbox, queue는 `eventId`를 사용하며 member·order·payment의 이벤트 발행은 각 모듈 outbox 레코드 식별자를 기준으로 관리한다.
- **실패 처리:** order·payment·settlement의 주요 소비자는 재시도와 DLQ를 구성한다. 모든 토픽이 같은 정책을 쓰는 것은 아니므로 각 consumer 설정과 수동 재처리 API를 함께 확인한다.

### 이벤트 카탈로그

| 토픽 | 발행 | 구독 | payload(요약) |
|---|---|---|---|
| `order.completed.events` | 주문 | 결제·AI | orderId, sellerId, productId, memberId, amount |
| `payment.completed.events` | 결제 | 주문 | paymentId, orderId, memberId, amount, method, pgTxId, approvedAt |
| `payment.failed.events` | 결제 | 주문 | paymentId, orderId, reason |
| `refund.completed.events` | 결제 | 주문 | refundId, paymentId, orderId, amount, refundedAt |
| `refund.failed.events` | 결제 | 주문 | refundId, paymentId, orderId, reason |
| `payment.settlement.events` | 결제 | 정산 | `PaymentSettlementCompleted` 또는 `RefundSettlementCompleted`; eventId, eventType, occurredAt + 판매/환불 정산 필드 |
| `product.created.events` | 상품 | 검색 | id, sellerId, name, description, categoryId, categoryName, sellerName, price, thumbnailKey, createdAt, updatedAt |
| `product.updated.events` | 상품 | 검색 | created와 같은 상품 스냅샷 |
| `product.deleted.events` | 상품 | 검색 | id, deletedAt |
| `seller_registered_events` | 회원 | 상품 | sellerInfoId, storeName |
| `seller_updated_events` | 회원 | 상품 | sellerInfoId, storeName |
| `wishlist.changed.events` | 회원 | AI | userId, productId, type(`CREATE`\|`DELETE`), occurredAt |
| `order.stock.adjusted.events` | 주문 | 대기열 | eventId, dropId, count, reason(`COMPLETED`\|`REFUNDED`) |

---

## 10. 주요 엔티티 (요약)

> PK 타입은 UUID이며 생성 버전은 §6처럼 모듈별로 다르다. 타 도메인 참조는 "값 참조"(FK 아님).
> payment의 `pgPaymentKey`·`pgRefundKey`는 AES-GCM으로 암호화한다.

- **회원(9100):** `Member`, `RoleEntity`, `RoleHistory`, `SellerInfo`, `WishlistItem`, `OutboxEvent`
  - 회원:판매자 = **1:N** — `sellerId`(=`SellerInfo.id`, UUIDv7)는 `memberId`와 **별도 식별자**(한 회원이 다중 판매자 보유 가능). 활성 `SellerInfo` 유무로 role을 SELLER↔USER 승강.
- **상품(9110):** `Product`, `Category`, `Drop`(재고·오픈시각의 주인), `StockHistory`(append-only 원장), `SellerStore`(member 이벤트의 스토어명 투영)
- **주문(9120):** `Order`, `OrderHistory`, `OrderSagaState`, `InboxEvent`, `OutboxEvent`
- **결제(9130):** `Wallet`, `WalletTransaction`, `WalletCharge`, `Payment`, `PaymentEvent`, `Refund`, `OutboxEvent`, `ReconciliationDiscrepancy`
- **정산(9140):** `SettlementOrder`, `SettlementRefund`, `SettlementAdjustment`, `SettlementBatch`, `SellerSettlement`, 일 대사 결과·불일치
- **대기열(9150):** JPA 엔티티 없이 Redis ZSET·Hash로 대기자, 입장권, 재고·확정 판매량 스냅샷 관리
- **검색(9240):** Elasticsearch `ProductDocument`; 전체 재색인 시 product 스키마 read model 사용
- **AI(9160):** 추천 신호·결과 Redis 캐시, 관리자 `ai_read` 조회 모델과 MVC SSE 대화 스트림

---

## 11. 초기 합의 사항

- 타 도메인 데이터는 직접 DB 조회·조인 금지 — **API 또는 이벤트로만** 접근 (FK 대신 값 참조).
- 통신 방식: **응답이 필수(즉시 성공/실패 판단)면 내부 동기 API, 아니면 Kafka 이벤트.**
- 분산 트랜잭션은 **사가 + 보상 + 멱등키** (2PC 미사용).
- 민감정보는 **평문 저장 금지 — 암호화 저장.**
- 공통 응답/에러 포맷·토픽 네이밍 등 **공통 컨벤션 준수.**

---

## 12. 현재 범위와 후속 과제

- 예치금은 payment의 지갑·충전·지갑 결제로 구현됐다. 장바구니는 별도 모듈·API가 없고 드롭 즉시 주문만 제공한다.
- PG는 프론트가 토스 SDK successUrl 결과를 받아 `/api/v1/payments/confirm`으로 확정하고, 백엔드는 Kafka로 주문·정산에 전파한다.
- search의 product 스키마 직접 조회는 전체 재색인에만 남아 있다. 전용 읽기 계정 또는 API 기반 스냅샷으로 강화할지는 후속 합의가 필요하다.
- 카테고리 GET은 공개지만 POST/PATCH/DELETE는 현재 Gateway에서 일반 access JWT만 요구하고 역할 제한은 없다. ADMIN 전용이 의도라면 인가 정책 보강이 필요하다.
- 정산 관리자 GET은 Gateway에서 ADMIN 역할을 검사하지만 `retry-failed`·`monthly/run`·`reconciliation/run` POST는 현재 일반 access JWT만 요구한다. 또한 판매자 정산 GET은 SELLER 역할만 확인하고 controller가 현재 판매자의 sellerInfoId로 조회 범위를 강제하지 않아 다른 판매자 또는 전체 결과를 요청할 수 있다. 두 경계 모두 운영 전 인가 보강이 필요하다.
- 카테고리: 상품 서비스 내 **`categories` 테이블**로 분리 완료(`Product`가 `@ManyToOne`으로 **선택 참조** — nullable, 카테고리 없이 상품 등록 가능·삭제 시 미분류). 계층 구조·카테고리별 수수료는 추후 컬럼 확장으로 대응.
- 공통 모듈 범위·QueryDSL 도입 여부: 도메인별 결정 사항.

---

## 13. 빌드 / 실행 / 형상 관리

- 멀티모듈 Gradle(Kotlin DSL). 루트 `settings.gradle.kts`에 `apigateway/common/member/product/order/payment/settlement/queue/search/ai` 포함.
- 공통 의존성은 루트 `build.gradle.kts`의 `subprojects` 블록에서 일괄 관리.
- **프로파일:** 기본값은 `local`, 컨테이너·k3s 배포는 `compose`. 현재 별도 `dev`·`prod` 설정 파일은 없고 k3s ConfigMap/Secret이 `compose` 값을 주입한다.
- **시크릿:** 코드에 두지 않고 **GitHub Secrets** 주입.
- **브랜치 전략:** `dev`에서 기능 브랜치 분기 → **기능 단위 PR**. 금요일(주말) 저녁 멘토 `dev` 코드리뷰 요청, main PR에 멘토 리뷰어 지정.
- **공통 모듈:** 회원 모듈 담당자가 주도 — 에러코드·공통응답·보안설정 인터페이스 정의, **각 도메인이 구현.** (이벤트 DTO는 공통 모듈에 두지 않고 복제)
- 상세 셋업: `docs/SETUP.md`. Windows PowerShell은 `.\gradlew.bat`, Git Bash/WSL은 `./gradlew`.

---

## 14. 커밋 메시지 컨벤션

형식: `<type>(<scope>): <한글 제목>` + 빈 줄 + 본문(필요 시).

> **스코프 = 담당 모듈명**(멀티모듈이라 표기). 예: `feat(product)`·`fix(order)`·`docs(member)`. 여러 서비스 공통이거나 공용 모듈은 `common`. PR 제목 스코프 관례와 동일하게 맞춘다.

### 커밋 유형 (영어 소문자)

| 유형 | 의미 |
|---|---|
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서 수정 |
| `style` | 코드 포맷팅, 세미콜론 누락 등 코드 자체 변경이 없는 경우 |
| `refactor` | 코드 리팩토링 |
| `test` | 테스트 코드 추가·리팩토링 |
| `chore` | 패키지 매니저 수정, 그 외 기타 (예: `.gitignore`) |
| `design` | CSS 등 사용자 UI 디자인 변경 |
| `comment` | 필요한 주석 추가 및 변경 |
| `rename` | 파일/폴더명 수정 또는 이동만 수행한 경우 |
| `remove` | 파일 삭제만 수행한 경우 |
| `!BREAKING CHANGE` | 커다란 API 변경 |
| `!HOTFIX` | 급하게 치명적인 버그를 고쳐야 하는 경우 |

### 규칙
1. 커밋 유형은 위 표의 영어 소문자 표기를 그대로 사용.
2. 제목과 본문은 **빈 줄 1개로 분리.** 제목·본문 모두 **한글**로 내용이 잘 전달되게 작성.
3. 제목 **끝에 마침표(`.`) 금지.**
4. 제목은 **영문 기준 50자 이내.**
5. 본문에는 **무엇(What)·왜(Why)** 를 설명 (어떻게(How)보다 우선).
6. 항목이 여러 개면 글머리 기호(`-`)로 가독성 확보.

### 본문 작성 스타일 (의도-우선)

제목과 빈 줄 1개로 구분한 뒤 하이픈(`-`) 글머리로 작성한다.

- **첫 글머리 = 의도/문제(Why):** 식별자만 나열하지 말고 동기·판단·트레이드오프·발견한 문제를 자연스러운 한국어 문장으로 풀어쓴다. 길어도 한 문장으로 끝내고, 변경이 자명해 보여도 **생략하지 않는다** (작성자 시점의 의도를 남긴다).
- **이후 글머리 = 핵심 변경(What):** 컴포넌트·모듈·식별자 단위로 짧게 한 줄. `->`로 이전/이후 또는 원인/결과를 압축.
- 모듈 풀 경로·시그니처 전체 표기·옵션 나열 등 **diff만 봐도 즉시 아는 것은 적지 않는다.**

```
feat(common): 공통 API 응답·에러 표준 구조 추가

- 도메인마다 제각각이던 응답/에러 포맷을 표준화해 클라이언트·서비스 간 계약을 일관되게 맞추기 위함
- 성공은 ResponseEntity로 리소스 직접 반환(봉투 없음), 에러는 ErrorResponse 공통 정의
- ErrorCode 인터페이스 + 도메인 enum 구현 방식 채택 -> 도메인별 에러코드를 공통 계약으로 통일
- GlobalExceptionHandler를 AutoConfiguration으로 전역 등록
```

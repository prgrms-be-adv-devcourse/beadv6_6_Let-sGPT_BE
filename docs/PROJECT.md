# PROJECT.md — 한정 수량 드롭 커머스 플랫폼 (openAt)

> 이 저장소의 **프로젝트 정보**(도메인·아키텍처·기술 스택·컨벤션·엔티티)를 정의한다.
> 코드를 작성·리뷰할 때 항상 이 문서의 규칙과 맥락을 따른다.
> **프로젝트 정보가 새로 정해지거나 바뀌면 이 파일을 갱신한다.** (작업 방식·지침은 `CLAUDE.md`)

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
| 주문(order) | 9120 | 주문 요청, **사가 오케스트레이션**, 보상, 주문 취소 |
| 결제(payment) | 9130 | PG 연동, 결제/환불, 결과 이벤트 발행, 민감정보 암호화 |
| 정산(settlement) | 9140 | 이벤트 기반 판매 적재, Spring Batch 월 정산 |
| 검색(search, **파이널**) | - | Elasticsearch 색인·하이브리드 검색 |
| AI(ai, **파이널**) | - | Spring AI·RAG 챗봇·임베딩·WebFlux SSE |

- 포트 규칙: 메인 도메인은 **10단위 증가**, 도메인 파생 서비스는 **부모 포트 + 1**.

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

1. **엔티티에 JPA 직접 사용** — 도메인 엔티티(`domain.model`)에 JPA 매핑 어노테이션을 직접 부착한다. 순수 원칙은 도메인을 프레임워크에서 분리(별도 영속 모델)하지만, 도메인/영속 모델 이중 정의의 유지비가 이득보다 커서 **도메인 엔티티가 곧 JPA 엔티티**다.

- **MSA / 데이터 격리:** 서비스는 자기 데이터만 소유. **다른 도메인의 DB·스키마·테이블을 직접 조회·조인하지 말 것.** 타 도메인 데이터는 **API 호출 또는 이벤트로만** 접근. (FK 대신 "값 참조")
- **API Gateway (Spring Cloud Gateway):** 모든 외부 요청은 Gateway 통과.
  - `/api/**` → 외부 노출 + JWT 검증.
  - `/internal/**` → 서비스 간 호출 전용, **외부 차단.**
- **인증(JWT):** 회원 서비스가 발급, Gateway가 검증 후 사용자 정보를 **헤더로 전달**. 각 서비스는 헤더의 사용자 정보를 신뢰.
- **통신 방식 분리 (핵심 설계):**
  - **동기 (OpenFeign 내부 API):** 즉시 성공/실패 판단이 필요한 호출. → **재고 감소·재고 롤백·재고 이력 조회.** (우리 DB라 빠르고 외부 의존 없음)
  - **비동기 (Kafka 이벤트):** 결과를 기다릴 필요 없는 전파. → **결제·환불 결과, 정산 적재, 상품 색인.** (PG처럼 느리거나 사후 통지 성격)
- **분산 트랜잭션:** **사가(오케스트레이션).** 주문 서비스가 오케스트레이터. 실패 시 **보상 트랜잭션**으로 롤백(재고 복원 등). **멱등키**로 중복/재시도 방지.
- **데이터 정합성:** 최종적 일관성(이벤트) + 보상(사가). **2PC 사용 안 함.**
  - 세미 = 보상 없이 **해피패스만** / 파이널 = **사가 오케스트레이션(보상)** 완성.

---

## 4. 핵심 비즈니스 흐름 (드롭 즉시 주문 사가)

1. 주문 생성 (`status=PAYMENT_PENDING`) → 사가 시작
2. **재고 감소** (상품 서비스 **동기** 호출). 품절·미오픈·한도초과면 즉시 실패(`order.failed`)
3. 재고 성공 → 결제 진입 (결제 서비스가 PG 결제창 호출 → 승인)
4. 결제 결과를 **이벤트로 수신**
   - COMPLETE → `order.complete` + `order_completed` 이벤트 발행 (→ 정산)
   - FAILED → **재고 롤백(보상)** + `order.cancelled` / `payment_failed`
5. 결제 미회신 시간초과 → 상품이 `stock_timeout` 이벤트 발행 → 주문 `order.failed` (재고 자동 정리)
6. 주문 취소 → 환불 요청 → 환불 결과 이벤트 (COMPLETE: `order.refund` / FAILED: `order.refund_failed` 수동 처리)
7. 월 정산: `order_completed`·`refund_completed` 적재 → **Spring Batch** (`cron 0 3 5 * *`)로 수수료·환불 차감 정산

---

## 5. 상태값 Enum (정확히 일치시킬 것)

| 대상 | 값 |
|---|---|
| drop | `SCHEDULED` / `OPEN` / `CLOSE` / `SOLD_OUT` |
| order | `PAYMENT_PENDING` / `COMPLETE` / `CANCELLED` / `PAYMENT_FAILED` / `FAILED` / `REFUND` / `REFUND_FAILED` |
| payment | `PENDING` / `COMPLETE` / `FAILED` |
| refund | `PENDING` / `COMPLETE` / `FAILED` |
| member role | `BUYER` / `SELLER` |

서비스 간 문자열로 주고받을 때 값이 어긋나지 않게 한다. Enum 자체는 각 도메인이 설계.

---

## 6. 기술 스택

| 구분 | 채택 | 비고 |
|---|---|---|
| 언어 | **Java 21** | 루트 toolchain `JavaLanguageVersion.of(21)` |
| 프레임워크 | **Spring Boot 4.1.0** | Spring Cloud 2025.1.2 |
| 빌드 | **Gradle (Kotlin DSL)** | 모노레포 멀티모듈, 서비스별 디렉토리 |
| DB | **PostgreSQL** | `runtimeOnly("org.postgresql:postgresql")` |
| DB 구조 | **공유 DB(`openat`) + 서비스별 독립 스키마** | `hibernate.default_schema: <domain>`, 현재 `ddl-auto: update` |
| ORM | **Spring Data JPA** (+ QueryDSL 도메인별) | |
| 마이그레이션 | Flyway *(계획, 현재 deps 미포함)* | 도입 전까지 `ddl-auto: update`로 스키마 관리 |
| PK | **UUIDv7** | 시간 정렬 UUID (인덱스 삽입 지역성 확보) |
| 통신 | **OpenFeign**(동기 내부) / **Apache Kafka**(비동기) | |
| 재고 동시성 | **Redis+Lua 게이트키퍼** | append-only 이력 원장 기반, 설계 단계부터 Redis 캐시+Lua 도입 |
| 캐시·동시성 | **Redis** (Drop 캐시·Lua 원자 처리) | |
| 결제 | **토스페이먼츠**(테스트), 민감정보 **AES 암호화** | |
| 정산 | **Spring Batch** + Spring Scheduler | |
| 인증/보안 | Spring Security + JWT(jjwt 0.12.3) | |
| 문서 | **springdoc-openapi (Swagger)** | `/swagger-ui.html`, `/api-docs` |
| 인프라 | Docker / GitHub Actions(paths 필터, GHCR) | 세미 = Docker Compose, 파이널 = Kubernetes(K3s) |
| 부하 테스트 | **k6** | |
| 파이널 추가 | Elasticsearch(역색인+벡터), Spring AI, WebFlux(SSE), RAG | 대부분 파이널에서 확정 |

---

## 7. 코드 컨벤션 (반드시 준수)

> **공통 vs 서비스 내부 컨벤션:** 서비스 간 계약·공통 모듈에 닿는 컨벤션(패키지 계층, 네이밍, DTO 접미사, API/이벤트 포맷, 커밋 등)은 **팀 전체가 동일하게 준수**한다. 반면 한 서비스 내부에서만 닫히는 코드 컨벤션(예: 엔티티 생성 방식)은 **담당자가 독립적으로 판단·확정**할 수 있다(현재 product는 본인 담당). 내부 컨벤션을 정하거나 바꿀 때는 근거를 `product/docs/DECISIONS.md`에 남긴다.

- **패키지:** 클린 아키텍처 계층 구조 — `com.openat.<domain>.{domain, application, infrastructure, presentation}` (전부 소문자). 계층별 책임은 [§3 클린 아키텍처](#3-아키텍처-원칙-반드시-준수) 참고.
- **네이밍:** 클래스 `PascalCase`, 메서드·변수 `camelCase`, 상수 `UPPER_SNAKE`. 불리언은 **긍정형**(`isOpen` ⭕ / `isNotClosed` ❌).
- **DB 테이블/엔티티:** 엔티티 클래스는 **단수**(`Product`·`Drop`), 테이블명은 **복수형**(`products`·`drops`). 예약어(`order`·`drop`·`user`) 충돌 회피 + 일관성 목적. 컬럼은 `snake_case`. 인덱스·제약 이름도 **복수 테이블 기준**(`idx_products_*`, `uk_categories_name`). 공통 DB(`openat`)를 공유하므로 테이블 일관성을 위해 **팀 합의로 승격한 전사 컨벤션**(이유·대안 공유 후 채택).
- **DTO:**
  - 컨트롤러: 요청 `~Request` / 응답 `~Response` (**`~Dto` 지양**)
  - 서비스: 요청 `~Command` / 반환 `~Info`
- **Lombok:** `@Getter`·`@Builder` 허용. **엔티티에 `@Data`·`@Setter` 지양.**
- **엔티티 생성(product 내부 컨벤션):** 기능명을 진입 메서드로 갖는 빌더(`@Builder(builderMethodName=...)`)로 생성 — `Product.create()…build()`·`Drop.schedule()…build()`·`Category.create()…build()`. 같은 타입 파라미터가 호출부에서 뒤섞이는 것을 필드명 명시로 차단하고, 진입 메서드 이름으로 생성 의도를 표현. (배경·트레이드오프는 `product/docs/DECISIONS.md`)
- **검증 위치:** 입력 검증은 **컨트롤러 `@Valid`(Bean Validation)** 에서 수행하고, **HTTP 진입이 단일 경로라는 가정** 하에 도메인 팩토리의 중복 검증은 생략. (이벤트 컨슈머 등 다른 진입 경로는 [§9](#9-eda이벤트-컨벤션-반드시-준수)의 자체 방어 검증을 따른다.)
- **예외:** 커스텀 예외 계층(`BusinessException` 등 **언체크**) + `@RestControllerAdvice` 전역 처리. 에러코드는 **공통 enum**으로 관리.
- **테스트:** given-when-then, `@DisplayName` 한글 허용. **사가·재고·결제 핵심 로직 우선.**
- **포맷터:** 팀 합의 자동 포맷 도구 적용.

---

## 8. API 컨벤션 (반드시 준수)

- **URL:** 복수형 + 케밥케이스. (`/api/v1/products`, `/internal/drops/{id}/stock-histories`)
- **버전/경계:** 외부 `/api/v1/...`, 내부 `/internal/...`.
- **메서드:** 전체 수정 **PUT** / 일부 수정 **PATCH**.
- **성공 응답:** 봉투 없이 리소스를 그대로 반환(`ResponseEntity<T>`). 상태코드는 HTTP 상태 라인이 단일 기준이며 본문에 중복하지 않음. 생성은 `201 Created` + `Location` 헤더, 본문 없는 응답은 `204 No Content`.

  ```json
  { /* 리소스 본문 */ }
  ```

- **에러 응답:** 도메인별 error enum. (HTTP 상태코드는 상태 라인으로 전달)

  ```json
  { "error": "SOLD_OUT", "message": "재고가 없습니다" }
  ```

  주요 코드 예: `SOLD_OUT`, `NOT_OPEN`, `LIMIT_EXCEEDED`, `PAY_FAILED`.
- **상태코드:** 표준 준수. (품절 `409` / 미오픈 `400` / 없음 `404`)
- **페이징:** 오프셋 기반 `?page=&size=`, 응답 `{ content, totalPages, totalElements }`.
- **날짜·시간:** ISO 8601 + **UTC**.
- **문서:** Swagger(springdoc-openapi).

---

## 9. EDA(이벤트) 컨벤션 (반드시 준수)

- **토픽 네이밍:** `[도메인]_[행위]_events`, **과거형**. (예: `order_completed_events`, `payment_completed_events`)
- **토픽 분리:** **이벤트마다 분리.** 예: `payment_completed_events` / `payment_failed_events`로 결과별 토픽을 나눈다.
- **공통 봉투(IntegrationEvent):** 메타 + payload 구조로 감싼다.

  ```json
  {
    "eventId": "evt-payment-202605-000001",
    "eventType": "PaymentCompletedIntegrationEvent",
    "eventVersion": "1.0",
    "occurredAt": "2026-06-01T00:00:05",
    "producer": "payment-service",
    "aggregateType": "PAYMENT",
    "aggregateId": "PAY-202605-000001",
    "traceId": "trace-payment-001",
    "payload": { /* 이벤트별 데이터 */ }
  }
  ```

- **직렬화:** JSON. **날짜·시간:** ISO 8601 + UTC.
- **이벤트 DTO 위치:** **각 서비스에 복제 정의** (공통 모듈로 공유하지 않음).
- **멱등성:** 각 도메인이 `eventId`(또는 `orderId`)로 중복 수신 차단. `inbox_event` 테이블 기록.
- **실패 처리:** 세미 = 로그 / 파이널 = **최대 3회 재시도 후 DLQ**(선택), `inbox_event` FAILED 저장.

### 이벤트 카탈로그

| 토픽 | 발행 | 구독 | payload(요약) |
|---|---|---|---|
| `order_completed_events` | 주문 | 정산 | orderId, sellerId, productId, memberId, saleAmount, quantity |
| `payment_completed_events` | 결제 | 주문 | paymentId, orderId, amount, pgPaymentKey |
| `payment_failed_events` | 결제 | 주문 | orderId, reason |
| `refund_completed_events` | 결제 | 주문, 정산 | refundId, orderId, amount |
| `refund_failed_events` | 결제 | 주문 | orderId, reason |
| `product_created_events` | 상품 | 검색·AI(파이널) | productId, name, category, price |
| `product_updated_events` | 상품 | 검색·AI(파이널) | productId, name, category, price |

---

## 10. 주요 엔티티 (요약)

> PK는 **UUIDv7**. 타 도메인 참조는 "값 참조"(FK 아님). 민감정보(`settlementAccount`, `pgPaymentKey`)는 **AES 암호화**.

- **회원(9100):** `Member`(role[BUYER/SELLER]), `SellerProfile`(상호·사업자번호·정산계좌[암호화]), `RefreshToken`
  - 회원:판매자 = **1:N** — `sellerId`는 `memberId`와 **별도 식별자**(한 회원이 다중 판매자 보유 가능). 판매자 엔티티(이름·필드)·역할 모델 상세는 회원 도메인 확정 TODO.
- **상품(9110):** `Product`(상품 마스터), `Category`(상품 카테고리: name[고유]), `Drop`(**재고·오픈시각의 주인**: totalQuantity·dropPrice·openAt·closeAt·limitPerUser·status; 잔여 수량은 스냅샷 없이 `StockHistory` 합산), `StockHistory`(append-only 재고 이력)
- **주문(9120):** `Order`(dropId·quantity·orderPrice 스냅샷·status), `OrderSagaState`(사가 진행/보상 추적)
- **결제(9130):** `Payment`(pgPaymentKey[암호화]·idempotencyKey[UNIQUE]), `Refund`
- **정산(9140):** `SettlementRecord`(이벤트 적재: SALE/REFUND), `Settlement`(월 정산 결과 periodYm·수수료·실지급액)

---

## 11. 초기 합의 사항

- 타 도메인 데이터는 직접 DB 조회·조인 금지 — **API 또는 이벤트로만** 접근 (FK 대신 값 참조).
- 통신 방식: **응답이 필수(즉시 성공/실패 판단)면 내부 동기 API, 아니면 Kafka 이벤트.**
- 분산 트랜잭션은 **사가 + 보상 + 멱등키** (2PC 미사용).
- 민감정보는 **평문 저장 금지 — 암호화 저장.**
- 공통 응답/에러 포맷·토픽 네이밍 등 **공통 컨벤션 준수.**

---

## 12. 열린 결정 사항 (작업 중 합의 필요)

- 결제 이벤트 미회신 시 정리 시점/방식 (`stock_timeout` 트리거 기준 시간 등).
- 결제 결과 사용자 응답 경로(리다이렉트 successUrl/failUrl) vs 백엔드 이벤트 전파의 역할 분담.
- 예치금·장바구니: 과제 필수지만 현재 **TODO**. 현재는 PG 직접 결제·드롭 즉시 주문.
- 카테고리: 상품 서비스 내 **`categories` 테이블**로 분리 완료(`Product`가 `@ManyToOne`으로 **선택 참조** — nullable, 카테고리 없이 상품 등록 가능·삭제 시 미분류). 계층 구조·카테고리별 수수료는 추후 컬럼 확장으로 대응.
- 공통 모듈 범위·QueryDSL 도입 여부: 도메인별 결정 사항.

---

## 13. 빌드 / 실행 / 형상 관리

- 멀티모듈 Gradle(Kotlin DSL). 루트 `settings.gradle.kts`에 `member/order/payment/product/settlement` 포함.
- 공통 의존성은 루트 `build.gradle.kts`의 `subprojects` 블록에서 일괄 관리.
- **프로파일:** `local`(EC2/로컬 DB 자유) / `dev`(**GitHub Secrets 주입, `.env` 미사용**) / `prod`(dev 검증 후 push). 모듈별 `application-{local,dev,compose,prod}.yml`.
- **시크릿:** 코드에 두지 않고 **GitHub Secrets** 주입.
- **브랜치 전략:** `dev`에서 기능 브랜치 분기 → **기능 단위 PR**. 금요일(주말) 저녁 멘토 `dev` 코드리뷰 요청, main PR에 멘토 리뷰어 지정.
- **공통 모듈:** 회원 모듈 담당자가 주도 — 에러코드·공통응답·보안설정 인터페이스 정의, **각 도메인이 구현.** (이벤트 DTO는 공통 모듈에 두지 않고 복제)
- 상세 셋업: `docs/SETUP.md`. Windows는 `./gradlew`.

---

## 14. 커밋 메시지 컨벤션

형식: `<type>: <한글 제목>` + 빈 줄 + 본문(필요 시).

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
feat: 공통 API 응답·에러 표준 구조 추가

- 도메인마다 제각각이던 응답/에러 포맷을 표준화해 클라이언트·서비스 간 계약을 일관되게 맞추기 위함
- 성공은 ResponseEntity로 리소스 직접 반환(봉투 없음), 에러는 ErrorResponse 공통 정의
- ErrorCode 인터페이스 + 도메인 enum 구현 방식 채택 -> 도메인별 에러코드를 공통 계약으로 통일
- GlobalExceptionHandler를 AutoConfiguration으로 전역 등록
```
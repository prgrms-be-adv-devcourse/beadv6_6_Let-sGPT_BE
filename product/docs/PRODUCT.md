# PRODUCT.md — 상품(product) 모듈 가이드

> 담당 도메인인 **상품 모듈 내부**의 구조·컨벤션을 정리한 문서.
> 전 서비스 공통 정보·컨벤션은 [`../../docs/PROJECT.md`](../../docs/PROJECT.md), 결정 근거는 [`DECISIONS.md`](DECISIONS.md), 재고 게이트키퍼 상세는 [`STOCK_GATEKEEPER.md`](STOCK_GATEKEEPER.md), 테스트 작성은 [`TEST_CONVENTION.md`](TEST_CONVENTION.md).
> 여기엔 **product 내부에서만 닫히는 규칙**만 적는다(전역과 겹치면 PROJECT.md를 따른다). 내부 컨벤션을 바꾸면 `DECISIONS.md`에 근거를 남긴다.

---

## 1. 모듈 개요
- 포트 **9110**. 책임: 상품·드롭 등록/조회, 드롭 상태 관리, 재고 감소/롤백(내부 동기 API). (전역 맥락은 PROJECT §2·§4)
- 의존: `:common`(응답·에러·예외 표준).

---

## 2. 내부 패키지 구조 — 서브도메인 평탄 분리
PROJECT §7의 일반형(`com.openat.<domain>.{layer}`)을 product 내부 컨벤션으로 특화한다. **모듈 자체가 곧 서비스 경계**이므로, 내부를 관리 단위별 서브도메인으로 **평탄하게** 나눈다. (근거: DECISIONS 2026-06-22 #2)

```
com.openat
├── ProductApplication     @SpringBootApplication (스캔: com.openat 중 common 제외)
├── config/                모듈 공통 설정 (Security · Web · OpenApi · QueryDsl · Scheduling · DropProperties)
├── support/               공통 지원 (auth · web · docs) — @CurrentUser · @InternalApi 등
├── product/               상품             — domain · application · infrastructure · presentation
├── drop/                  재고/드롭(게이트키퍼 본진) — domain · application · infrastructure · presentation
├── category/              카테고리          — domain · application · infrastructure · presentation
└── seller/                판매자 스토어 표시명 읽기 모델(member 이벤트 투영) — domain · application · infrastructure
```

- `@SpringBootApplication`을 `com.openat` 루트에 둔다 → 엔티티·Spring Data 리포지토리 스캔이 기본값으로 전 서브도메인을 덮는다(common엔 엔티티·리포지토리 없음).
- **컴포넌트 스캔만** `com.openat` 기준에서 `com.openat.common`을 제외(블랙리스트)한다 → 새 서브도메인은 자동 포함되고, common의 `GlobalExceptionHandler`(@AutoConfiguration 등록)는 이중 등록되지 않는다.

---

## 3. 서브도메인 책임 & 의존 방향
| 서브도메인 | 책임 |
| :--- | :--- |
| `product` | 상품 마스터 등록·조회 |
| `drop` | 한정 드롭 판매 + 재고(이력 원장). 재고 게이트키퍼 본진 |
| `category` | 상품 카테고리(참조 데이터) + 존재 판정 |
| `seller` | 판매자 스토어 표시명(`storeName`) 로컬 읽기 모델 — member 스토어 이벤트(Kafka) 투영. 카탈로그 벤더 표기 N+1 회피 |

- **의존 방향은 단방향: `drop → product → category`.** 역참조 금지(순환 차단). FK 방향(`drops.product_id`, `products.category_id`)과 일치.
- **`seller`는 product·drop가 표시명 조회(읽기 포트 `SellerStoreQueryUseCase`)로만 의존하는 리프 읽기 모델**이다. 자체 비즈니스 데이터를 소유하지 않고 member 이벤트로만 채워지며(역참조·역방향 의존 없음), member `SellerInfo.id`를 PK(값 참조)로 둔다.
- **`products.category_id`는 선택 참조(nullable).** 카테고리 없이 상품 등록 가능(미분류), 카테고리 삭제 시 SET NULL로 미분류 전환. (§11 삭제 전략)

---

## 4. 서브도메인 간 호출 규칙
- 다른 서브도메인의 **repository·내부 구현에 직접 접근 금지.** 반드시 그 서브도메인의 **application 포트**를 통한다.
- "그 개념에 대한 판정·예외"는 **소유 서브도메인**이 가진다.
  - 예: "없는 카테고리"는 category가 소유 — `CategoryQueryUseCase.getById()`가 `CategoryErrorCode.NOT_FOUND`를 던지고, product는 포트만 호출.
- 엔티티 참조는 FK상 불가피하다(예: product가 `Category` 엔티티 참조). 같은 모듈·DB라 수용. 서비스로 분리되는 시점엔 읽기 모델/식별자 참조로 전환.
- **참조 무결성(삭제 차단 등)은 DB FK 제약이 책임진다.** 애플리케이션 레벨에서 "참조 중인지"를 확인하지 않는다 — 그 판정 지식은 참조하는 쪽(예: product)에 있어, 코드로 확인하려면 역방향 참조(`category → product`)가 생겨 §3 단방향이 깨지기 때문. 무결성을 FK(두 서브도메인보다 아래 계층)에 맡겨 방향 충돌을 원천 차단한다. (근거: DECISIONS 2026-06-23 #2)

---

## 5. 레이어 컨벤션 (각 서브도메인 내부)
| 계층 | 패키지 | 구성 |
| :--- | :--- | :--- |
| 도메인 | `domain` | `model`(JPA 엔티티) · `repository`(포트 인터페이스) · `error`(도메인 ErrorCode enum) |
| 애플리케이션 | `application` | `usecase`(Query/Command 포트) · `service`(`QueryService`=readOnly / `CommandService`=write) · `dto`(`~Command`·`~Query`·`~Info`) |
| 인프라 | `infrastructure` | `persistence` — `<Aggregate>JpaRepository`(Spring Data) + `<Aggregate>RepositoryAdaptor`(도메인 포트 구현) |
| 프레젠테이션 | `presentation` | `controller`(얇게) · `dto`(Request/Response) |

---

## 6. 네이밍 / 생성 컨벤션 (product 특화)
- **엔티티 생성 = 의미론적 빌더**: `Product.create()` · `Drop.schedule()` · `StockHistory.record()`. (DECISIONS 2026-06-19 #5)
- **CQRS 경량(서비스 분리)**: application 포트·서비스를 읽기/쓰기로 분리 — `Xxx`**`Query`**`UseCase`+`Xxx`**`Query`**`Service`(`@Transactional(readOnly = true)` 클래스 레벨) / `Xxx`**`Command`**`UseCase`+`Xxx`**`Command`**`Service`(`@Transactional`). 필요한 쪽만 생성하고, 경계를 넘는 의존은 읽기(Query) 포트로 제한. (DECISIONS 2026-06-22 #4)
- **같은 서브도메인 내 서비스는 자기 도메인의 다른 서비스를 참조하지 않는다**: Command/Query 서비스는 각자 책임(쓰기/읽기)만 진다. Command가 엔티티를 로드해야 하면 같은 도메인 Query 서비스를 부르지 말고 **자기 `Repository.findById().orElseThrow()`를 직접** 쓴다(여러 메서드가 공유하면 private 헬퍼로 — 예: `getCategory`·`getOwnedProduct`). 서비스가 다른 서비스의 유스케이스를 참조하는 것은 **다른 서브도메인**의 application 포트에 한한다(예: `ProductCommandService` → `CategoryQueryUseCase`, §4).
- **DTO**: presentation `~Request`/`~Response`, application 쓰기 입력 `~Command` / 읽기 입력 `~Query` / 출력 `~Info`. 생성 흐름 동사는 **`create`로 통일**(컨트롤러 메서드·usecase·빌더까지 한 단어).
- **영속 포트 구현**: `<Aggregate>JpaRepository`(Spring Data 인터페이스) + `<Aggregate>RepositoryAdaptor`(`@Repository`, 도메인 포트 구현).
- **조회 네이밍 — `get`/`find`/`search` 구분**: `getXxx`는 없으면 예외를 던진다(반드시 존재 보장). `findXxx`는 **부재 가능**을 나타낸다 — `Optional<T>` 또는 객체/`null` 두 형태를 모두 허용한다(어느 쪽이든 호출부가 부재를 처리). `searchXxx`는 동적 조건으로 목록을 조회한다(`Page`/`List` 반환, 결과 없으면 빈 결과). 예: 단건 `getById`(NOT_FOUND throw) ↔ 리포지토리 `findById`(Optional) ↔ 목록 `searchProducts`(동적 조건 검색). 단순 식별자 단건이 아니라 조건 기반 목록이면 `getXxx(s)`가 아니라 `searchXxx`를 쓴다.
- **엔티티 변수명**: 로드한 **기존** 엔티티는 엔티티명 그대로(`category`), **새로 생성**(미영속) 엔티티는 `new<Entity>`(`newCategory`·`newProduct`). 단, 한 스코프에 신규·기존이 함께 있어 비교가 필요하면 맥락에 어울리는 이름으로 구분한다.
- **메서드 본문 — 사고 흐름 단계대로**: 메서드 내부 코드는 사람의 사고 흐름 단계와 일치하도록 단계별로 작성한다. 동일 성능이면 가독성을 우선하고, 중첩 호출로 압축하기보다 단계를 지역 변수로 풀어 한 문장당 하나의 일로 읽히게 한다.
- **로컬 헬퍼 추출 기준**: 한 메서드에서만 쓰는 로직은 private 메서드로 분리하지 않고 본문에 인라인한다(위 단계 원칙대로 푼다). **둘 이상의 메서드가 공유할 때만** private로 추출한다(예: `ProductCommandService.toCategory`·`getOwnedProduct`). 서브도메인을 넘어 반복되면 `support`로 승격을 검토한다.
- **에러코드**: 서브도메인별 enum(`CategoryErrorCode` 등)이 `common.error.ErrorCode`를 구현. 클라이언트 노출 `code` 문자열은 안정적으로 유지(예: `"CATEGORY_NOT_FOUND"`).

---

## 7. 영속성 컨벤션
- PK **UUIDv7**(`@UuidGenerator(style = TIME)`).
- 엔티티 단수 / 테이블 복수, 컬럼 `snake_case`, `@Column(comment=...)`로 의도 명시. (전역 규칙은 PROJECT §7)
- **인덱스**: FK 및 타 도메인 값 참조 컬럼에 부여. 이름 `idx_<table>_<column>`, 유니크 `uk_<table>_<…>`. (DECISIONS 2026-06-19 #6)
- 타 도메인/서비스 참조는 **값 참조(UUID)**, FK 아님(예: `StockHistory.orderId`/`buyerId`).
- **재고 이력 원장(`stock_histories`)**: append-only, 부호 있는 `quantity_delta`, `UNIQUE(order_id, change_type)`로 멱등. (DECISIONS 2026-06-22 #1, 상세 STOCK_GATEKEEPER)
- **쓰기 포트 입력 객체**: 쓰기 포트(재고 차감·롤백·보상·이력 기록)는 application `~Command`를 그대로 넘기지 않고 도메인 값 객체(`~Mutation` @ `domain.repository`)로 받는다 — 식별 튜플(`dropId`/`orderId`/`buyerId`/`quantity`)을 개별 인자로 풀지 않고 묶어 연속 UUID 위치-인자 혼동을 막는다. 변환은 `~Command.toMutation()`(읽기 `~SearchRequest.toCondition()`→`~SearchCondition`의 쓰기 짝). 예: `StockMutation`.
- **N+1 방어**: LAZY 연관 조회의 N+1은 전역 `default_batch_fetch_size`(IN 배치)를 안전망으로 둔다(`application.yml`). 동적·복잡 조회는 **QueryDSL**(OpenFeign 포크)로 작성한다 — 상품 목록 조회(`ProductRepositoryAdaptor.search`)부터 적용. ToOne 연관은 `fetchJoin`으로 단일 쿼리화한다.
- **QueryDSL 작성 규칙**: 적용 대상은 **동적 조건·N+1 위험 조회만**(단순 단건·존재 조회는 Spring Data 메서드 유지).
  - **위치**: 영속 어댑터(`<Aggregate>RepositoryAdaptor`)가 `JPAQueryFactory`로 직접 구현한다. 전용 QueryDSL 클래스·공용 유틸을 따로 두지 않고 어댑터에 응집(빈은 `config.QueryDslConfig`). 구 `QuerydslRepositorySupport`는 쓰지 않는다.
  - **검색 조건**: 포트는 도메인 질의 명세(`~SearchCondition` @ `domain.repository`)를 받는다(application DTO를 포트로 넘기지 않음). presentation `~SearchRequest.toCondition()`으로 변환.
  - **동적 where**: `BooleanBuilder` + `if`로 메서드 본문에서 조립한다(null/blank 조건은 추가하지 않음). content·count 쿼리가 같은 `where`를 공유.
  - **N+1·페이징**: **ToOne 연관만 `fetchJoin`**(컬렉션은 페이징이 깨지므로 금지 → batch 안전망 사용). count는 fetchJoin 없이 분리하고 `PageableExecutionUtils.getPage`로 감싼다.
  - **정렬**: 실제 요구가 있을 때만 도입한다(현재 상품 목록은 최신순 `createdAt desc` 고정). 사용자 선택 정렬이 필요해지면 허용 필드 화이트리스트로 변환한다.
  - **테스트**: 영속 슬라이스는 `@Import`에 `QueryDslConfig`를 포함한다(`@DataJpaTest`는 `@Configuration`을 스캔하지 않아 `JPAQueryFactory` 빈이 없음).

---

## 8. API 엔드포인트 컨벤션 — 상품 등록(`POST /products`) 기준

전역 응답/에러 표준은 PROJECT §8을 따른다(성공 `ResponseEntity<T>`·봉투 없음, 에러 `ErrorResponse`). 아래는 그 위에 product가 적용하는 내부 규칙 — **상품 등록 흐름을 모든 엔드포인트의 템플릿으로 삼는다.**

**컨트롤러는 얇게** — 명세 구현 + 인증 추출 + 검증 + 유스케이스 호출 + 응답 구성만. 비즈니스 로직은 service.
- Swagger 명세 전담 인터페이스(`ProductApiSpec`)를 `implements` → 컨트롤러엔 제어 흐름만, 문서 어노테이션은 인터페이스로 분리.
- 인증 식별자(`@CurrentUser UUID sellerId`)는 활성 스토어의 **`sellerInfoId`**다. 게이트웨이가 판매자 scoped JWT를 검증해 `X-Seller-Id`로 주입하며, 인증 파라미터는 API 입력 모델에 노출하지 않는다.
- 회원과 판매자는 1:N이므로 memberId를 상품 소유 식별자로 사용하지 않는다. member가 판매자 토큰 발급 시 회원↔판매자 소유를 검증하고, product는 상품·드롭↔sellerId 리소스 소유를 검증한다. 상세 계약은 §12.
- 입력 검증은 `@Valid`(Bean Validation). 단일 진입 가정으로 도메인 중복 검증 생략. (`spring-boot-starter-validation`)

**예외**
- `BusinessException`(언체크) + 서브도메인 ErrorCode → common `GlobalExceptionHandler`가 `ErrorResponse`로 변환. (@AutoConfiguration 등록 — product는 스캔에서 common 제외하므로 자동설정 경로로만 등록)

**응답 (전역 표준의 product 적용)**
- 생성: `201 Created` + `Location` 헤더 — Location은 common `Locations.fromCurrentRequest(id)`로 `현재요청/{id}` 구성. **생성 리소스 id는 본문이 아니라 Location으로 전달**(전용 응답 본문을 두지 않음). 예: `POST /products` → `201`, `Location: /api/v1/products/{id}`, 본문 없음.
- 본문 없는 응답(삭제 등): `204 No Content`(표준 `ResponseEntity.noContent()`).

**Swagger 문서** (명세 인터페이스에 집약)
- 성공 응답은 **엔드포인트별로 `@ApiResponse`를 직접 선언**(상태코드·설명·헤더를 엔드포인트마다 자유롭게). 예: 생성 `@ApiResponse(responseCode = "201", headers = @Header(name = "Location", ...))`.
- 공통 에러는 `@ApiErrorResponses`(공통 메타 어노테이션) 재사용. 데이터 모델 스키마(`@Schema`)는 DTO record에 매핑.

(근거: DECISIONS.md — Swagger 인터페이스 방식 · 인증 파라미터 내부 추출 · 응답 ResponseEntity 전환)

---

## 9. 보안 경계
- 외부 인증·인가는 게이트웨이가 담당한다. product의 `config.SecurityConfig`는 `csrf` off + `anyRequest().permitAll()`을 유지하며, 서비스 직접 포트는 외부에 노출하지 않는다.
- 게이트웨이는 클라이언트가 보낸 `X-User-Id`·`X-User-Roles`·`X-Seller-Id`를 모두 제거한 뒤 검증된 토큰에서 신뢰 헤더를 다시 만든다.
- 상품·드롭 쓰기 경로는 `typ=scoped`, `aud=openat-product` 판매자 JWT만 허용한다. product의 `CurrentUserArgumentResolver`는 `X-Seller-Id`가 없거나 UUID 형식이 아니면 `UNAUTHENTICATED(401)`로 거절한다.

---

## 10. 설정 / 시드
- `application.yml`: `default_schema=product`, `ddl-auto=update`(콜드부팅 재고 이력 복구를 검증하려면 부팅 간 원장이 보존돼야 해 `create`→`update` 전환; PROJECT §6 전역 기본과 일치), `defer-datasource-initialization=true` + `sql.init.mode=always`.
- `data.sql`: `categories` 시드(의류·액세서리·문구·전자기기·피규어·기타), `ON CONFLICT (name) DO NOTHING`.
- **데모 시드(`support.seed.SeedDataRunner`)**: `local`/`dev` 한정 `ApplicationRunner`(`@Order(0)`, 부트스트랩보다 먼저). 상품이 비었을 때만 멱등 삽입 — 상품 16·드롭 10·`SellerStore` 데모 1. OPEN/SOLD_OUT 드롭의 잔여는 **재고 이력 원장 DEDUCT로 선반영**해 기동 워밍이 `총량+원장`으로 계산하게 한다(직접 캐시 워밍은 부트스트랩에 덮임).

---

## 11. 삭제 전략 (soft delete)

삭제는 **참조 데이터(category)와 비즈니스 레코드(product·drop)의 성격 차이**로 이원화한다. 정합성은 §4 원칙대로 DB/영속 계층에 위임하고, 코드에 역방향 참조를 만들지 않는다. (근거: DECISIONS 2026-06-23 #3 + 2026-06-26 보완)

| 대상 | 전략 | 메커니즘 |
| :-- | :-- | :-- |
| `category` (참조 데이터) | 하드 삭제 + 참조 끊기 | `products.category_id` nullable + FK `ON DELETE SET NULL` → 참조 상품은 미분류(null). DB가 끊으므로 역참조 없음 |
| `product`·`drop` (비즈니스 레코드) | soft 삭제 | `@SoftDelete(strategy = TIMESTAMP, columnName = "deleted_at")` — DELETE→UPDATE 자동 변환 + 조회 자동 필터 |
| `stock_histories` (감사 원장) | 삭제 안 함 | append-only |

- **하향 전파(product → drop)**: product를 soft 삭제하면 **동기 인프로세스 이벤트**(product가 발행 → drop 리스너 수신; `drop → product` 정방향·동일 트랜잭션·실패 시 롤백)로 그 product의 drop을 정리한다. 단 **진행 중(오픈/매진) 드롭이 하나라도 있으면 삭제를 차단** — drop 리스너가 라이브 드롭을 발견하면 예외(`DROP_OPEN_EXISTS`)를 던져 상품 삭제까지 롤백한다(라이브 거래를 끊지 않음·먼저 종료해야 함). 라이브가 없으면 자식 drop을 일괄 soft 삭제한다. **차단 판정은 product가 아니라 drop이 소유**(§4 역방향 참조 금지).
- **drop 자체 삭제는 오픈 전만 soft 삭제**: 오픈 후 drop은 삭제가 아니라 **종료(CLOSE)**다(직접 삭제·캐스케이드 공통으로 라이브 drop은 soft 삭제 대상이 아님 — §8·STOCK_GATEKEEPER). 라이브 drop은 종료를 거쳐야 사라진다.
- **원장 예외(감사 독립성)**: `stock_histories`는 soft 삭제된 drop을 계속 참조해야 하므로, drop을 **엔티티 연관이 아니라 값 참조(`drop_id` UUID 컬럼)**로 든다 — soft 삭제 필터가 걸리는 연관 네비게이션 자체가 없어 원장 집계·복구가 drop 삭제와 무관하다. (`@SoftDelete` 엔티티로의 to-one 지연 연관을 프레임워크가 금지하는 제약도 동시 회피)
- **조회 정합성**: 부모 삭제로 인한 자식 숨김은 항상 자식→부모(정방향) 필터로 처리하고, 영속 계층 자동 필터에 위임한다.

---

## 12. 인증·판매자 식별 계약

판매자 식별은 **member 소유권 검증 + 게이트웨이 신뢰 헤더 + product 리소스 소유 검증**으로 분담한다. (근거: DECISIONS 2026-06-25 #3, 전역 계약 PROJECT §3)

**토큰 발급**
- 클라이언트는 회원 access JWT로 `POST /api/v1/seller/token`에 `{ "sellerInfoId": "..." }`를 요청한다.
- member는 요청한 `sellerInfoId`가 해당 memberId 소유의 활성 스토어인지 검증한다.
- 성공하면 `sub=sellerInfoId`, `act.sub=memberId`, `aud=openat-product`, `scope=product:write`, `typ=scoped`인 단기 판매자 JWT를 발급한다. memberId는 위임 감사 정보이며 product 소유 식별자로 전달하지 않는다.

**게이트웨이 전달**
- 상품·드롭 쓰기 경로는 `typ=scoped`이고 audience에 `openat-product`가 포함된 JWT만 허용한다.
- 게이트웨이는 외부 요청의 인증 컨텍스트 헤더를 제거한 뒤 scoped JWT의 `sub`를 `X-Seller-Id`로 주입한다. scoped 요청에는 `X-User-Id`·`X-User-Roles`를 주입하지 않는다.
- product의 `support.auth.CurrentUserArgumentResolver`는 `UserHeaders.SELLER_ID`를 UUID로 변환해 `@CurrentUser UUID sellerId`에 주입한다.

**책임 경계**
- member는 **회원↔판매자 소유와 활성 상태**를 판매자 토큰 발급 시 검증한다.
- 게이트웨이는 **토큰 서명·만료·타입·대상 서비스와 헤더 위조 방지**를 책임진다.
- product는 전달된 sellerId의 회원 귀속을 다시 조회하지 않는다. 대신 상품·드롭이 그 sellerId 소유인지 계속 검증하며, 불일치하면 `PRODUCT_NOT_OWNER` 등 도메인 오류로 거절한다.
- product 전 계층의 `sellerId`는 `SellerInfo.id`와 같은 값인 **sellerInfoId**를 뜻한다.

---

## 13. 참고
- 테스트 작성 지침: [`TEST_CONVENTION.md`](TEST_CONVENTION.md)
- 결정 근거: [`DECISIONS.md`](DECISIONS.md)
- 재고 게이트키퍼 설계: [`STOCK_GATEKEEPER.md`](STOCK_GATEKEEPER.md)
- 전역 정보·컨벤션: [`../../docs/PROJECT.md`](../../docs/PROJECT.md)

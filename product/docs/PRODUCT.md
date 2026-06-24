# PRODUCT.md — 상품(product) 모듈 가이드

> 담당 도메인인 **상품 모듈 내부**의 구조·컨벤션을 정리한 문서.
> 전 서비스 공통 정보·컨벤션은 [`../../docs/PROJECT.md`](../../docs/PROJECT.md), 결정 근거는 [`DECISIONS.md`](DECISIONS.md), 재고 게이트키퍼 상세는 [`STOCK_GATEKEEPER.md`](STOCK_GATEKEEPER.md).
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
├── config/                SecurityConfig — 모듈 공통 설정
├── product/               상품      — domain · application · infrastructure · presentation
├── drop/                  재고/드롭  — domain (Drop · StockHistory · DropStatus · StockChangeType)
└── category/              카테고리   — domain · application · infrastructure
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

- **의존 방향은 단방향: `drop → product → category`.** 역참조 금지(순환 차단). FK 방향(`drops.product_id`, `products.category_id`)과 일치.
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
- **DTO**: presentation `~Request`/`~Response`, application 쓰기 입력 `~Command` / 읽기 입력 `~Query` / 출력 `~Info`. 생성 흐름 동사는 **`create`로 통일**(컨트롤러 메서드·usecase·빌더까지 한 단어).
- **영속 포트 구현**: `<Aggregate>JpaRepository`(Spring Data 인터페이스) + `<Aggregate>RepositoryAdaptor`(`@Repository`, 도메인 포트 구현).
- **조회 네이밍 — `get`/`find` 구분**: `getXxx`는 없으면 예외를 던진다(반드시 존재 보장). `findXxx`는 `Optional`을 반환한다(없을 수 있음). 예: 포트 `CategoryQueryUseCase.getById`(NOT_FOUND throw) ↔ 리포지토리 `findById`(Optional).
- **엔티티 변수명**: 로드한 **기존** 엔티티는 엔티티명 그대로(`category`), **새로 생성**(미영속) 엔티티는 `new<Entity>`(`newCategory`·`newProduct`). 단, 한 스코프에 신규·기존이 함께 있어 비교가 필요하면 맥락에 어울리는 이름으로 구분한다.
- **메서드 본문 — 사고 흐름 단계대로**: 메서드 내부 코드는 사람의 사고 흐름 단계와 일치하도록 단계별로 작성한다. 동일 성능이면 가독성을 우선하고, 중첩 호출로 압축하기보다 단계를 지역 변수로 풀어 한 문장당 하나의 일로 읽히게 한다.
- **에러코드**: 서브도메인별 enum(`CategoryErrorCode` 등)이 `common.error.ErrorCode`를 구현. 클라이언트 노출 `code` 문자열은 안정적으로 유지(예: `"CATEGORY_NOT_FOUND"`).

---

## 7. 영속성 컨벤션
- PK **UUIDv7**(`@UuidGenerator(style = TIME)`).
- 엔티티 단수 / 테이블 복수, 컬럼 `snake_case`, `@Column(comment=...)`로 의도 명시. (전역 규칙은 PROJECT §7)
- **인덱스**: FK 및 타 도메인 값 참조 컬럼에 부여. 이름 `idx_<table>_<column>`, 유니크 `uk_<table>_<…>`. (DECISIONS 2026-06-19 #6)
- 타 도메인/서비스 참조는 **값 참조(UUID)**, FK 아님(예: `StockHistory.orderId`/`buyerId`).
- **재고 이력 원장(`stock_histories`)**: append-only, 부호 있는 `quantity_delta`, `UNIQUE(order_id, change_type)`로 멱등. (DECISIONS 2026-06-22 #1, 상세 STOCK_GATEKEEPER)

---

## 8. API 엔드포인트 컨벤션 — 상품 등록(`POST /products`) 기준

전역 응답/에러 표준은 PROJECT §8을 따른다(성공 `ResponseEntity<T>`·봉투 없음, 에러 `ErrorResponse`). 아래는 그 위에 product가 적용하는 내부 규칙 — **상품 등록 흐름을 모든 엔드포인트의 템플릿으로 삼는다.**

**컨트롤러는 얇게** — 명세 구현 + 인증 추출 + 검증 + 유스케이스 호출 + 응답 구성만. 비즈니스 로직은 service.
- Swagger 명세 전담 인터페이스(`ProductApiSpec`)를 `implements` → 컨트롤러엔 제어 흐름만, 문서 어노테이션은 인터페이스로 분리.
- 인증 식별자(`@CurrentUser UUID`)는 **회원(memberId)** 이다(게이트웨이 전달, 현재 임시 `X-User-Id`). **API 명세엔 노출하지 않는다**(내부 파라미터).
- 상품 소유 주체인 **판매자(`sellerId`)는 회원과 1:N**이라 memberId에서 곧장 얻지 못한다 — sellerId 해석 방식은 게이트웨이/JWT 인증 연동 시 확정(**열린 설계 포인트**). 현 코드는 인증 스텁 단계라 임시로 인증 주체를 sellerId로 사용.
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

## 9. 보안 (임시)
- 현재 `config.SecurityConfig`: `csrf` off + `anyRequest().permitAll()`.
- **임시 베이스라인**이다 — 게이트웨이/JWT 인증(PROJECT §3)이 붙으면 교체한다.

---

## 10. 설정 / 시드
- `application.yml`: `default_schema=product`, `ddl-auto=create`(구현 초기 — 엔티티 변경이 잦아 당분간 매 기동 재생성, 안정화 후 `update` 복귀), `defer-datasource-initialization=true` + `sql.init.mode=always`.
- `data.sql`: `categories` 시드(의류·액세서리·문구·전자기기·피규어·기타), `ON CONFLICT (name) DO NOTHING`.

---

## 11. 삭제 전략 (soft delete)

삭제는 **참조 데이터(category)와 비즈니스 레코드(product·drop)의 성격 차이**로 이원화한다. 정합성은 §4 원칙대로 DB/영속 계층에 위임하고, 코드에 역방향 참조를 만들지 않는다. (근거: DECISIONS 2026-06-23 #3)

| 대상 | 전략 | 메커니즘 |
| :-- | :-- | :-- |
| `category` (참조 데이터) | 하드 삭제 + 참조 끊기 | `products.category_id` nullable + FK `ON DELETE SET NULL` → 참조 상품은 미분류(null). DB가 끊으므로 역참조 없음 |
| `product`·`drop` (비즈니스 레코드) | soft 삭제 | `@SoftDelete(strategy = TIMESTAMP, columnName = "deleted_at")` — DELETE→UPDATE 자동 변환 + 조회 자동 필터 |
| `stock_histories` (감사 원장) | 삭제 안 함 | append-only |

- **하향 전파(product → drop)**: product를 soft 삭제하면 그 product의 drop도 함께 soft 삭제한다(한정판·재고 이력 보존·복구 여지). 단 직접 호출은 §3 단방향을 깨므로 **동기 인프로세스 이벤트**로 처리 — product가 삭제 이벤트 발행, drop 리스너가 자기 drop을 soft 삭제(컴파일 의존 `drop → product` 정방향, 동일 트랜잭션·실패 시 롤백). 무결성=FK / 상태 전파=이벤트 역할 분담의 적용.
- **원장 예외**: `stock_histories`는 soft 삭제된 drop을 계속 참조하므로, 원장 조회는 `drop_id` 값 기준으로 다루고 soft 삭제 필터가 걸리는 `Drop` 연관 네비게이션에 의존하지 않는다(감사 독립성).
- **조회 정합성**: 부모 삭제로 인한 자식 숨김은 항상 자식→부모(정방향) 필터로 처리하고, 영속 계층 자동 필터에 위임한다.

---

## 12. 참고
- 결정 근거: [`DECISIONS.md`](DECISIONS.md)
- 재고 게이트키퍼 설계: [`STOCK_GATEKEEPER.md`](STOCK_GATEKEEPER.md)
- 전역 정보·컨벤션: [`../../docs/PROJECT.md`](../../docs/PROJECT.md)

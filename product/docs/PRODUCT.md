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

---

## 4. 서브도메인 간 호출 규칙
- 다른 서브도메인의 **repository·내부 구현에 직접 접근 금지.** 반드시 그 서브도메인의 **application 포트**를 통한다.
- "그 개념에 대한 판정·예외"는 **소유 서브도메인**이 가진다.
  - 예: "없는 카테고리"는 category가 소유 — `CategoryReader.getById()`가 `CategoryErrorCode.NOT_FOUND`를 던지고, product는 포트만 호출.
- 엔티티 참조는 FK상 불가피하다(예: product가 `Category` 엔티티 참조). 같은 모듈·DB라 수용. 서비스로 분리되는 시점엔 읽기 모델/식별자 참조로 전환.

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
- **에러코드**: 서브도메인별 enum(`CategoryErrorCode` 등)이 `common.error.ErrorCode`를 구현. 클라이언트 노출 `code` 문자열은 안정적으로 유지(예: `"CATEGORY_NOT_FOUND"`).

---

## 7. 영속성 컨벤션
- PK **UUIDv7**(`@UuidGenerator(style = TIME)`).
- 엔티티 단수 / 테이블 복수, 컬럼 `snake_case`, `@Column(comment=...)`로 의도 명시. (전역 규칙은 PROJECT §7)
- **인덱스**: FK 및 타 도메인 값 참조 컬럼에 부여. 이름 `idx_<table>_<column>`, 유니크 `uk_<table>_<…>`. (DECISIONS 2026-06-19 #6)
- 타 도메인/서비스 참조는 **값 참조(UUID)**, FK 아님(예: `StockHistory.orderId`/`buyerId`).
- **재고 이력 원장(`stock_histories`)**: append-only, 부호 있는 `quantity_delta`, `UNIQUE(order_id, change_type)`로 멱등. (DECISIONS 2026-06-22 #1, 상세 STOCK_GATEKEEPER)

---

## 8. 검증 / 예외 / 응답
- **검증**: 컨트롤러 `@Valid`(Bean Validation). HTTP 진입 단일 경로 가정으로 도메인 중복 검증 생략. (`spring-boot-starter-validation` 의존 추가)
- **예외**: `BusinessException`(언체크) + 서브도메인 ErrorCode. 전역 처리는 common `GlobalExceptionHandler`(@AutoConfiguration)에 위임 — product는 컴포넌트 스캔에서 common을 제외하므로 자동설정 경로로만 등록된다.
- **응답**: `ApiResponse.of(data, status)`. 생성은 `@ResponseStatus(CREATED)` + 201.

---

## 9. 보안 (임시)
- 현재 `config.SecurityConfig`: `csrf` off + `anyRequest().permitAll()`.
- **임시 베이스라인**이다 — 게이트웨이/JWT 인증(PROJECT §3)이 붙으면 교체한다.

---

## 10. 설정 / 시드
- `application.yml`: `default_schema=product`, `ddl-auto=update`, `defer-datasource-initialization=true` + `sql.init.mode=always`.
- `data.sql`: `categories` 시드(의류·액세서리·문구·전자기기·피규어·기타), `ON CONFLICT (code) DO NOTHING`.

---

## 11. 참고
- 결정 근거: [`DECISIONS.md`](DECISIONS.md)
- 재고 게이트키퍼 설계: [`STOCK_GATEKEEPER.md`](STOCK_GATEKEEPER.md)
- 전역 정보·컨벤션: [`../../docs/PROJECT.md`](../../docs/PROJECT.md)

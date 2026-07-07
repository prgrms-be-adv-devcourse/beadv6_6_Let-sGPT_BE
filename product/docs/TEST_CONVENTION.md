# TEST_CONVENTION.md — 상품(product) 테스트 컨벤션

> 상품 모듈의 **테스트 작성 지침**이다. 테스트 코드를 쓰기 전 항상 이 문서를 기준으로 삼는다.
> 전역 정보·컨벤션은 [`../../docs/PROJECT.md`](../../docs/PROJECT.md), 모듈 구조는 [`PRODUCT.md`](PRODUCT.md), 결정 근거는 [`DECISIONS.md`](DECISIONS.md), 재고 설계는 [`STOCK_GATEKEEPER.md`](STOCK_GATEKEEPER.md).
> 핵심 한 줄: **"깨질 수 있고 깨지면 아픈 것"에 집중하고, 커버리지 숫자를 채우기 위한 빈 테스트는 만들지 않는다.**

---

## 작성·검증 절차 (필수)

1. **기능(요청 단위)을 구현하면 그 기능의 테스트를 이 컨벤션에 따라 함께 작성한다.** 테스트 없는 기능 구현은 완료로 보지 않는다.
2. **테스트를 실행해 통과를 확인한다.** (단위: `./gradlew :product:test` / 영속·통합 포함: `:product:build`) 영속·통합 테스트는 Docker가 필요하므로, 데몬이 안 떠 있으면 **Docker Desktop을 먼저 실행**하고 준비된 뒤 돌린다.
3. **실패하면 테스트만 맞추지 말고 구현 기능 전체를 구조적 관점에서 재검토한다** — 실패가 구현의 구조적 문제(경계·책임·불변식)를 드러낼 수 있으므로, 테스트를 통과시키기 위해 코드를 무리하게 끼워 맞추지 않는다.
4. **수정할 때는 도메인 일관성을 먼저 검토한다** — 모듈 컨벤션·아키텍처 경계·서브도메인 규칙([`PRODUCT.md`](PRODUCT.md)·[`../../docs/PROJECT.md`](../../docs/PROJECT.md))이 깨지지 않는 방향으로 고친다.

---

## 0. 한눈에 보기 (치트시트)

**계층별로 도구를 다르게 쓴다** — 규칙은 단위로 빠르게, 정합성·계약은 실제에 가깝게.

| 계층 | 테스트 레벨 | 도구 | 클래스명 예시 |
| :-- | :-- | :-- | :-- |
| 도메인 모델 | 순수 단위 (컨텍스트 X) | JUnit5 + AssertJ | `StockHistoryTest` |
| 애플리케이션 서비스 | 단위 (협력자 mock) | JUnit5 + **Mockito** + AssertJ | `CategoryCommandServiceTest` |
| 영속 어댑터·매핑 | 슬라이스 통합 | `@DataJpaTest` + **Testcontainers(PostgreSQL)** | `ProductRepositoryAdaptorTest` |
| 컨트롤러·웹 | 슬라이스 | `@WebMvcTest` + **MockMvc** | `CategoryControllerTest` |
| 재고·동시성 | 통합/동시성 | Testcontainers(Redis) + `ExecutorService` | `DropCacheRedisAdaptorTest` |
| 부하 | 성능 E2E | **k6** (test 밖, 빌드와 분리) | `loadtest/*.js` |

**네이밍 한 줄**: 메서드명 `메서드_상황_결과`(lowerCamelCase + 언더바 구획) + 한글 `@DisplayName`.

**작성 규칙 핵심**
- 본문은 `// given` · `// when` · `// then` 3단으로.
- 메서드 내부도 **사고 흐름 단계대로** — 중첩으로 압축하지 말고 지역 변수로 풀고, 매직값 반복 제거.
- 단언은 **AssertJ로 통일** (`assertThat` / `assertThatThrownBy`). JUnit `Assertions`·Hamcrest 혼용 금지.

---

## 1. 테스트 전략 — 계층별 혼합

클린 아키텍처라 계층 경계가 또렷하다. **계층 성격에 맞는 도구를 쓴다.**

- **비즈니스 규칙·분기**는 컨텍스트를 띄우지 않고 **Mockito 단위**로 빠르게 — 피드백 루프를 짧게.
- **영속 정합성·동시성**은 우리 도메인의 본질(정합성·성능)이므로 **실제 PostgreSQL·Redis(Testcontainers)** 로 검증 — H2로는 UUIDv7·`@SoftDelete`·FK 동작이 달라 거짓 통과/실패가 난다.
- **우선순위**: PROJECT §7대로 **재고·정합성·핵심 비즈니스 규칙을 우선**한다.

---

## 2. 계층별 도구

| 계층 | 검증 초점 | 도구 |
| :-- | :-- | :-- |
| 도메인 모델 | 의미론적 빌더·불변식 (예: `StockHistory` delta 부호, `Drop` 초기 `REGISTERED`) | JUnit5 + AssertJ |
| 서비스 | 비즈니스 분기·포트 상호작용 (중복검증·동일이름 early return·category null 분기) | JUnit5 + Mockito + AssertJ |
| 영속 | 복합 unique 멱등·FK SET NULL·UUIDv7·`@SoftDelete`(미구현, 도입 시) | `@DataJpaTest` + Testcontainers(PostgreSQL) |
| 웹 | `@Valid` 검증·상태코드(201+Location/204)·에러 매핑 | `@WebMvcTest` + MockMvc |

### 슬라이스 테스트 설정 (Spring Boot 4 / Testcontainers 2.x — 실빌드 검증)

Spring Boot 4는 테스트 슬라이스를 기술별 모듈로 쪼갰다. 아래는 `:product:build`로 검증한 값이라 그대로 따르면 시행착오가 없다.

**의존성** (`build.gradle.kts` `testImplementation`)
- 웹: `org.springframework.boot:spring-boot-webmvc-test`
- 영속: `org.springframework.boot:spring-boot-data-jpa-test`, `org.springframework.boot:spring-boot-testcontainers`
- 컨테이너: `org.testcontainers:testcontainers-junit-jupiter:2.0.5`, `org.testcontainers:testcontainers-postgresql:2.0.5`
  → Testcontainers 2.x는 모듈명에 **`testcontainers-` 접두사**가 붙고, `io.spring.dependency-management`가 BOM을 전파하지 않아 **버전을 직접 명시**해야 한다.

**패키지 이동** (3.x → 4.x)
- `@WebMvcTest`·`@AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure`
- `@DataJpaTest` → `org.springframework.boot.data.jpa.test.autoconfigure`
- `@AutoConfigureTestDatabase` → `org.springframework.boot.jdbc.test.autoconfigure`
- `PostgreSQLContainer` → `org.testcontainers.postgresql`(2.x, **제네릭 없음**). 구 `org.testcontainers.containers`는 deprecated.

**웹 슬라이스 템플릿**

```java
@WebMvcTest(XxxController.class)
@AutoConfigureMockMvc(addFilters = false)               // 시큐리티 필터 제외(임시 permitAll이라 검증 대상 아님)
@Import({WebConfig.class, GlobalExceptionHandler.class}) // /api/v1 프리픽스·@CurrentUser·에러 매핑
class XxxControllerTest {
  @Autowired MockMvc mockMvc;
  @MockitoBean XxxUseCase useCase;
  ObjectMapper objectMapper = new ObjectMapper();        // 슬라이스가 ObjectMapper 빈을 안 만들어 직접 생성
}
```

- 응답 단언은 MockMvc DSL(`andExpect(status()/jsonPath()/header())`) — 여기 쓰는 Hamcrest matcher는 MockMvc 표준이라 §4의 "AssertJ 통일"과 무관.
- `@CurrentUser`는 `X-Seller-Id` 헤더(게이트웨이가 scoped 토큰 검증 후 주입하는 sellerInfoId)로 주입된다.

**영속 슬라이스 템플릿**

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(XxxRepositoryAdaptor.class)            // 어댑터(@Repository)는 슬라이스에 없어 Import
@TestPropertySource(properties = {
  "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true", // default_schema=product 생성
  "spring.sql.init.mode=never"                                       // data.sql 시드 격리
})
class XxxRepositoryAdaptorTest {
  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Autowired XxxRepository repository;
  @PersistenceContext EntityManager em;        // TestEntityManager 부재 → 표준 EntityManager
}
```

- 플러시는 `em.flush()`로 강제. DB 제약 위반은 `org.hibernate.exception.ConstraintViolationException`(EM 직접 호출이라 Spring 변환 전 단계).
- FK(`ON DELETE SET NULL`) 검증은 JPA 객체 그래프 검증에 막히므로 **네이티브 DELETE**로 DB에 직접 보낸다.
- **로컬·CI에 Docker 필요**(§0 절차 2). 컨테이너 싱글톤 재사용은 테스트가 늘면 베이스 클래스로 도입(현재는 클래스당 기동).

---

## 3. 네이밍

| 항목 | 규칙 | 예시 |
| :-- | :-- | :-- |
| 클래스명 | `<대상>Test` (단수) | `CategoryCommandServiceTest` |
| 메서드명 | `메서드_상황_결과` — lowerCamelCase, 언더바로 구획 구분 | `create_nameDuplicated_throwsException` |
| `@DisplayName` | 한글 서술형 (필수) | `"중복된 이름이면 DUPLICATE_NAME 예외를 던진다"` |
| 그룹핑 | `@Nested`로 대상 메서드/시나리오 묶음 (권장) | `class Create { ... }` |
| 패키지 | `src/main` 미러링 | `com.openat.category.application.service` |

> 메서드명 규칙은 우리 포맷터(`googleJavaFormat`)의 출처인 **Google Java Style Guide §5.2.3**를 따른다:
> *"Underscores may appear in JUnit test method names to separate logical components of the name, with each component written in lowerCamelCase."* (예: `pop_emptyStack`)
> 구획은 2~3개 자유 — 상황·결과만으로 충분하면 `getById_notFound_throwsException`.

실제 케이스 예:

| 메서드명 | `@DisplayName` |
| :-- | :-- |
| `create_nameDuplicated_throwsException` | 중복된 이름이면 DUPLICATE_NAME 예외를 던진다 |
| `update_sameName_returnsWithoutSaving` | 이름이 그대로면 변경 없이 종료한다 |
| `getById_notFound_throwsException` | 없는 카테고리를 조회하면 NOT_FOUND 예외를 던진다 |
| `create_categoryNull_savesUncategorized` | 카테고리 없이도 미분류 상품으로 등록된다 |

---

## 4. 본문 작성 규칙

### given-when-then 3단
`// given` · `// when` · `// then` 주석 마커로 단계를 나눈다.
> **주석 예외**: CLAUDE.md는 설명 주석을 금하지만, GWT 마커는 설명이 아니라 **채택된 테스트 구조 마커**(PROJECT §7)다. 테스트 코드에 한해 허용한다.

### 메서드 내부도 "사고 흐름 단계대로" (PRODUCT §6)
중첩 호출로 압축하지 말고, 단계를 지역 변수로 풀어 **한 문장당 하나의 일**로 읽히게 한다. 매직값 반복은 변수 한 단계로 제거해 의도를 드러낸다.

```java
// ❌ 매직값 반복 + 의도 흐려짐
given(categoryRepository.existsByName("의류")).willReturn(true);
assertThatThrownBy(() -> categoryCommandService.create(new CategoryCreateCommand("의류")))
    .isInstanceOf(BusinessException.class);

// ✅ 단계로 풀어 의도가 드러남
String duplicatedName = "의류";
var command = new CategoryCreateCommand(duplicatedName);
given(categoryRepository.existsByName(duplicatedName)).willReturn(true);
```

### 단언은 AssertJ로 통일
예외는 `assertThatThrownBy`로 타입과 함께 **어떤 에러코드인지까지** 단언한다.

```java
@ExtendWith(MockitoExtension.class)
class CategoryCommandServiceTest {

  @InjectMocks CategoryCommandService categoryCommandService;
  @Mock CategoryRepository categoryRepository;
  @Mock CategoryQueryUseCase categoryQueryUseCase;

  @Nested
  @DisplayName("카테고리 생성")
  class Create {

    @Test
    @DisplayName("중복된 이름이면 DUPLICATE_NAME 예외를 던진다")
    void create_nameDuplicated_throwsException() {
      // given
      String duplicatedName = "의류";
      var command = new CategoryCreateCommand(duplicatedName);
      given(categoryRepository.existsByName(duplicatedName)).willReturn(true);

      // when & then
      assertThatThrownBy(() -> categoryCommandService.create(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", CategoryErrorCode.DUPLICATE_NAME);
    }
  }
}
```

---

## 5. 픽스처·테스트 데이터

### 프로덕션 빌더 재사용이 기본
엔티티는 의미론적 빌더(`Product.create()`·`Drop.schedule()`·`Category.create()`)가 이미 있으니 **테스트도 이 빌더를 그대로 쓴다.** 픽스처 클래스는 미리 만들지 않고, **같은 조합이 반복되면 그때** `<Entity>Fixture`로 추출한다.
- 위치: `com.openat.<sub>.fixture.<Entity>Fixture` (test 소스)
- 표준 인스턴스를 시나리오명 정적 팩토리로 제공 (`uncategorized()`, `registeredDrop()`)

### 미영속 엔티티의 id·시간은 `ReflectionTestUtils`로 주입
`@UuidGenerator`·`@CreationTimestamp`는 영속 시점에 채워지므로 미영속 엔티티는 id/시간이 `null`이다. `save(x).getId()`처럼 id가 필요하면 **`ReflectionTestUtils.setField`로 주입**한다 — 캡슐화를 깨는 테스트 전용 setter/생성자를 프로덕션에 만들지 않는다.

```java
public final class ProductFixture {

  public static Product uncategorized(UUID sellerId) {
    return Product.create().sellerId(sellerId).name("기본 굿즈").price(10_000L).build();
  }

  public static Product persisted(UUID id, UUID sellerId) {
    Product product = uncategorized(sellerId);
    ReflectionTestUtils.setField(product, "id", id); // 영속된 것처럼 id 주입
    return product;
  }
}
```

### 고정 vs 랜덤

| 종류 | 정책 |
| :-- | :-- |
| id (UUID) | 기본 `UUID.randomUUID()`. 단언에서 매칭이 필요하면 지역 변수로 고정해 재사용 |
| 비즈니스 시각 (`openAt`·`closeAt`) | **고정 `Instant.parse("...")`** (재현성·가독성) |
| 감사 시각 (`createdAt`·`updatedAt`) | 자동 생성 → 단위 테스트에선 무시, "채워진다"를 볼 땐 영속 테스트에서만 |

---

## 6. 무엇을 테스트하고 무엇을 생략하나

**행위(behavior)를 테스트하고, 구현 디테일은 두지 않는다.**

| ✅ 테스트한다 | ❌ 생략한다 |
| :-- | :-- |
| 비즈니스 규칙·분기 (중복검증·early return·null 분기) | Lombok getter/builder 자체 |
| 도메인 불변식 (delta 부호·초기 상태) | 단순 위임 라인 (컨트롤러→usecase 호출 자체) |
| 영속 정합성 (멱등 unique·FK SET NULL·soft delete·UUIDv7) | 자명한 DTO 필드 복사 (`toCommand`) |
| 웹 계약 (`@Valid`·상태코드·에러 매핑) | 설정 클래스(Security/OpenApi)·프레임워크 기본 동작 |
| 동시성·멱등 (오버셀 차단·중복 차감 방지) | `data.sql` 시드 등 |

---

## 7. 커버리지 (JaCoCo)

- **`jacocoTestReport`로 측정·가시화**한다 — 사각지대 발견용. (플러그인 추가 필요)
- **측정 집중**: `domain.model` · `application.service` / **제외**: config · dto · `*ApiSpec` · `ProductApplication`
- **빌드 실패 게이트(`jacocoCoverageVerification`)는 두지 않는다** — 수치 강제는 ⓐ 단언 없는 빈 테스트를 부르고 ⓑ 초기 빌드를 깨뜨린다. 핵심 로직이 쌓인 뒤 **핵심 패키지에 한해 낮게** 재검토한다.
- 커버리지 숫자보다 **§6의 시나리오 체크리스트**를 실질 기준으로 둔다.

---

## 8. 재고·동시성·멱등 가이드

재고 게이트키퍼의 **정합성 축은 구현·검증됐다**(실제: `DropCacheRedisAdaptorTest` — Testcontainers+Redis+`ExecutorService`). 성능 수치(k6)·장애 회복은 예정. 검증은 성격이 다른 세 축이고 **도구·위치가 다르다.**

> **두 "수치"를 혼동하지 말 것.** §0~§7의 "수치"는 **커버리지 %**(채우려 들면 안 되는 양적 지표)이고, 아래 성능 "수치"는 **TPS·latency**(반드시 측정해야 하는 검증 대상)다.

| 검증 축 | 무엇을 | 도구·위치 |
| :-- | :-- | :-- |
| **정합성** | 오버셀 차단·재고 음수 불가·멱등(중복 주문 1회만 차감/롤백)·보수적 거절 | JUnit 동시성 테스트 + Testcontainers(Redis+PG), `@Tag("concurrency")`로 분리 |
| **성능 수치** | TPS·p95/p99 latency·동시성 한계 | **k6** (`loadtest/`, test 밖·빌드와 분리) |
| **장애 회복** | 캐시 다운 → RDB 원장 재워밍, 캐시·RDB 불일치 치유 | 통합 테스트(컨테이너 중단 시뮬레이션) — 여력 되면 |

**정합성(오버셀 차단)은 테스트 코드 안에서 한다** — 동시 요청을 만들어 결과를 단언:

```java
@Test
@DisplayName("동시 차감 요청에도 재고를 초과 판매하지 않는다")
void deduct_concurrentRequests_neverOversells() throws InterruptedException {
  // given
  int stock = 100, requests = 200;
  Drop drop = openDropWithStock(stock);
  ExecutorService executor = Executors.newFixedThreadPool(32);
  CountDownLatch done = new CountDownLatch(requests);
  AtomicInteger success = new AtomicInteger();

  // when
  for (int i = 0; i < requests; i++) {
    executor.submit(() -> {
      try {
        stockService.deduct(drop.getId(), newOrderId(), buyerId(), 1);
        success.incrementAndGet();
      } catch (BusinessException soldOut) {
        // 품절 거절은 정상 경로
      } finally {
        done.countDown();
      }
    });
  }
  done.await();

  // then
  assertThat(success.get()).isEqualTo(stock); // 정확히 stock건만 성공
  assertThat(remainingStock(drop)).isZero();  // 재고 0, 음수 없음(오버셀 X)
}
```

**성능 수치(TPS·latency)는 k6로** — 단일 JVM·제한된 스레드의 JUnit으로는 실제 부하를 재현할 수 없어 빌드와 분리한다. 부하 중 오버셀 정합성도 함께 본다(STOCK_GATEKEEPER §6).

---

## 9. 참고
- 모듈 구조·컨벤션: [`PRODUCT.md`](PRODUCT.md)
- 결정 근거: [`DECISIONS.md`](DECISIONS.md)
- 재고 게이트키퍼 설계: [`STOCK_GATEKEEPER.md`](STOCK_GATEKEEPER.md)
- 전역 정보·컨벤션: [`../../docs/PROJECT.md`](../../docs/PROJECT.md)
</content>
</invoke>

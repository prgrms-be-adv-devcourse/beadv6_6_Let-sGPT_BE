# 현재 로그인 사용자 정보 사용 가이드 (`@CurrentUser` / `UserContextHolder`)

## 1. 동작 흐름

```
클라이언트 요청
    │
    ▼
[apigateway]
  JWT 검증 → X-User-Id, X-User-Roles 헤더를 세팅해 다운스트림으로 전달
    │         (클라이언트가 직접 보낸 두 헤더는 항상 먼저 제거 — 위조 불가)
    ▼
[각 서비스]
  UserContextFilter (Ordered.HIGHEST_PRECEDENCE)
    X-User-Id, X-User-Roles 헤더를 읽어 UserContext를 만든 뒤
    ThreadLocal(UserContextHolder)에 저장 → 요청이 끝나면 clear()
    │
    ▼
  컨트롤러 / 서비스 어디서든 꺼내 쓰기
    ① @CurrentUser 애너테이션  (컨트롤러 파라미터용)
    ② UserContextHolder 정적 메서드  (서비스 계층 등 어디서든)
```

`common` 모듈이 클래스패스에 있으면 **자동으로 설정됩니다.** `@ComponentScan`이나
`@EnableWebMvc`를 별도로 선언할 필요 없습니다 (`CommonWebAutoConfiguration`이 Spring Boot
자동설정으로 등록됨).

---

## 2. `UserContext` 데이터 구조

```java
public record UserContext(String userId, Set<String> roles) {

    // "ROLE_" 접두사 있어도 없어도 동일하게 비교
    // hasRole("SELLER") == hasRole("ROLE_SELLER") → true
    public boolean hasRole(String role) { ... }
}
```

- `userId` — JWT의 `sub` claim 값 (DB의 member PK)
- `roles` — `ROLE_` 접두사가 **제거된** 상태로 저장됨. 예: `{"USER", "SELLER"}`

---

## 3. 방법 ① — `@CurrentUser` 애너테이션 (컨트롤러 파라미터)

컨트롤러 메서드 파라미터에 `@CurrentUser`를 붙이면 `UserContext` 객체가 자동으로 주입됩니다.
`@RequestParam`, `@PathVariable`처럼 선언만 하면 됩니다.

### 기본 사용 예시

```java
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // 내 주문 목록 조회 — 현재 로그인한 사용자의 주문만 반환
    @GetMapping
    public List<OrderResponse> getMyOrders(@CurrentUser UserContext currentUser) {
        return orderService.findByUserId(currentUser.userId());
    }

    // 주문 생성
    @PostMapping
    public OrderResponse createOrder(
            @CurrentUser UserContext currentUser,
            @RequestBody OrderCreateRequest request
    ) {
        return orderService.create(currentUser.userId(), request);
    }
}
```

### 역할(role) 확인이 필요한 경우

```java
@GetMapping("/admin/all")
public List<OrderResponse> getAllOrders(@CurrentUser UserContext currentUser) {
    if (!currentUser.hasRole("ADMIN")) {
        throw new BusinessException(CommonErrorCode.FORBIDDEN);
    }
    return orderService.findAll();
}
```

> **주의**: `@CurrentUser`는 **인증 필수**를 의미합니다. 컨텍스트가 없으면(게이트웨이 없이
> 서비스를 직접 호출하거나, `permitAll` 경로에서 호출하는 등) `BusinessException(UNAUTHENTICATED)`
> 이 던져집니다. `permitAll` 경로에서 선택적으로 사용자 정보를 쓰고 싶다면 방법 ②를 사용하세요.

---

## 4. 방법 ② — `UserContextHolder` 정적 메서드 (서비스 계층 등)

컨트롤러가 아닌 서비스, 도메인 로직 등 **어디서든** 직접 꺼낼 수 있습니다.

### 제공 메서드

```java
// UserContext 전체 객체 반환 — 없으면 BusinessException(UNAUTHENTICATED) 발생
UserContext context = UserContextHolder.require();

// userId만 바로 꺼내기 — 없으면 BusinessException(UNAUTHENTICATED) 발생
String userId = UserContextHolder.currentUserId();

// 역할 목록 반환 — 없으면 BusinessException(UNAUTHENTICATED) 발생
Set<String> roles = UserContextHolder.currentRoles();

// 특정 역할 보유 여부 확인 — 없으면 BusinessException(UNAUTHENTICATED) 발생
boolean isSeller = UserContextHolder.currentUserHasRole("SELLER");

// nullable — 인증 안 된 요청에서도 안전하게 쓰고 싶을 때
UserContext ctx = UserContextHolder.get(); // 없으면 null 반환
```

### 서비스 계층에서 사용 예시

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderResponse create(OrderCreateRequest request) {
        // 컨트롤러에서 userId를 파라미터로 전달받지 않아도 직접 꺼낼 수 있음
        String userId = UserContextHolder.currentUserId();
        Order order = Order.create(userId, request);
        return OrderResponse.from(orderRepository.save(order));
    }

    public void cancelOrder(Long orderId) {
        String userId = UserContextHolder.currentUserId();
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));

        if (!order.getOwnerId().equals(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        order.cancel();
    }
}
```

### `permitAll` 경로에서 선택적으로 사용자 정보 활용

```java
@GetMapping("/products/{id}")
public ProductResponse getProduct(
        @PathVariable Long id
) {
    // 로그인 안 해도 조회는 되지만, 로그인했으면 개인화된 정보를 추가
    UserContext ctx = UserContextHolder.get(); // null-safe
    return productService.findById(id, ctx != null ? ctx.userId() : null);
}
```

---

## 5. 두 방법 비교

| | `@CurrentUser` (방법 ①) | `UserContextHolder` (방법 ②) |
|---|---|---|
| 사용 위치 | 컨트롤러 파라미터만 | 어디서든 (서비스, 도메인 등) |
| 인증 없으면 | `BusinessException` 자동 발생 | `get()`은 null 반환, `require()`는 예외 발생 |
| 코드 가독성 | 파라미터 선언만으로 의도 명확 | 내부 어디서든 꺼낼 수 있어 유연 |
| 권장 상황 | 컨트롤러에서 인증 필수 경로 | 서비스 계층, 혹은 선택적 인증 |

둘을 섞어도 됩니다. 컨트롤러에서 `@CurrentUser`로 받아서 서비스에 `userId`를 인자로 넘기거나,
서비스 내부에서 `UserContextHolder.currentUserId()`로 직접 꺼내거나 — 팀 내에서 일관되게 정하면
됩니다.

---

## 6. 로컬에서 게이트웨이 없이 서비스를 직접 테스트할 때

게이트웨이를 거치지 않으면 `X-User-Id`/`X-User-Roles` 헤더가 없어서 `UserContextHolder`가
비어 있습니다. `@CurrentUser`를 쓰는 엔드포인트에 직접 요청하면 401이 납니다.

**우회 방법**: HTTP 클라이언트(Postman/curl/Swagger)에서 헤더를 직접 세팅합니다.

```
X-User-Id: 1
X-User-Roles: ROLE_USER,ROLE_SELLER
```

`UserContextFilter`가 헤더를 파싱해 컨텍스트를 채워주기 때문에, 게이트웨이와 동일하게 동작합니다.

> **보안 주의**: 이 우회 방법은 로컬 개발 전용입니다. 운영 환경에서는 반드시 게이트웨이를 통해서만
> 서비스에 접근해야 합니다 (게이트웨이가 헤더 위조를 사전에 차단함).

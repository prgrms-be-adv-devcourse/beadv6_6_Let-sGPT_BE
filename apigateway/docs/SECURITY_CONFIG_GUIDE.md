# apigateway 보안 설정 가이드

## 컴포넌트 역할

| 컴포넌트 | 역할 |
|---|---|
| `RouteExistenceFilter` | 요청 경로에 매칭되는 라우트가 없으면 토큰 유무와 무관하게 즉시 404 반환 |
| `SecurityWebFilterChain` | JWT 검증 및 경로별 인증/인가 정책 적용 (401 / 403) |
| `UserContextRelayFilter` | 일반 access JWT는 `X-User-Id`·`X-User-Roles`, 판매자 scoped JWT는 `X-Seller-Id`만 전달. 토큰 유무와 관계없이 클라이언트가 보낸 세 헤더를 먼저 제거한다 |

판매자 scoped JWT(`typ=scoped`)는 `sub=sellerInfoId`, `act.sub=memberId`다. Gateway는
`sub`가 회원 ID로 오인되지 않도록 `X-User-Id`·`X-User-Roles`를 제거하고
`X-Seller-Id`만 주입한다. product 쓰기 경로는 `SecurityConfig.scopedFor("openat-product")`가
현재 `typ=scoped`와 `aud=openat-product`를 확인한다. 토큰에는 `scope=product:write`도
발급되지만 Gateway 인가 조건에서는 아직 이 claim을 검사하지 않는다.

---

## 새 서비스 추가 시 수정할 파일

### 1. `application-local.yaml` — 라우트 등록

```yaml
- id: order                          # 서비스 이름
  uri: http://localhost:9120         # 서비스 주소
  predicates:
    - Path=/api/v1/orders/**         # 외부에 노출할 경로
```

### 2. `application-compose.yaml` — 동일, 호스트만 컨테이너명으로

```yaml
- id: order
  uri: http://order:9120             # docker-compose 서비스명
  predicates:
    - Path=/api/v1/orders/**
```

> Swagger 문서 경로도 노출할 경우 `StripPrefix=1` 라우트를 별도 추가한다.
> ```yaml
> - id: order-docs
>   uri: http://order:9120
>   predicates:
>     - Path=/order/api-docs,/order/swagger-ui.html,/order/swagger-ui/**
>   filters:
>     - StripPrefix=1
> ```

### 3. `SecurityConfig.java` — 인가 정책 추가

현재 `anyExchange().access(authenticatedAndNotScoped())`가 catch-all이라 명시하지 않아도
일반 access JWT 인증은 요구되고 scoped JWT는 거부된다.
**공개 경로**이거나 **특정 역할 제한**이 필요한 경우에만 추가한다.

```java
.authorizeExchange(exchange -> exchange

    // 공개 (토큰 불필요)
    .pathMatchers(HttpMethod.POST, "/api/v1/payments/webhook").permitAll()

    // 일반 access JWT면 누구나. scoped JWT까지 허용하면 안 되는 경로에는
    // 실제 SecurityConfig의 authenticatedAndNotScoped()를 사용
    .pathMatchers("/api/v1/orders/**").access(authenticatedAndNotScoped())

    // 특정 역할만 — 해당 역할로 "전환하는" 진입점에는 사용하지 말 것
    .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")

    .anyExchange().access(authenticatedAndNotScoped())
)
```

현재 카테고리 GET은 공개고 POST/PATCH/DELETE는 별도 matcher가 없어 catch-all을 탄다.
따라서 카테고리 쓰기는 일반 access JWT만 있으면 가능하며 ADMIN 역할 제한은 없다.

정산은 GET `/api/v1/settlements/admin/*`에 ADMIN, GET
`/api/v1/settlements/seller/*`에 SELLER 역할을 요구한다. 반면
`retry-failed`, `monthly/run`, `reconciliation/run` 같은 관리자 POST는 현재 matcher가 없어
일반 access JWT catch-all을 탄다. settlement 서비스 자체는 전 경로 `permitAll`이므로,
관리자 전용이 의도라면 Gateway POST matcher를 보강해야 한다.

**역할 선택 기준**

| 상황 | 선택 |
|---|---|
| 로그인 없이 호출해야 함 | `permitAll()` |
| 일반 access JWT면 누구나 | `authenticatedAndNotScoped()` |
| scoped JWT까지 포함해 인증 토큰이면 모두 허용 | `authenticated()` — 의도한 경우에만 사용 |
| 이미 해당 역할을 가진 사람만 | `hasRole("ROLE명")` |
| 역할로 "전환"하는 첫 진입점 | `authenticatedAndNotScoped()` — 실제 권한 검증은 서비스 내부에서 |

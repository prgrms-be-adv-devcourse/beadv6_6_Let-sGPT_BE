# apigateway 보안 설정 가이드

## 컴포넌트 역할

| 컴포넌트 | 역할 |
|---|---|
| `RouteExistenceFilter` | 요청 경로에 매칭되는 라우트가 없으면 토큰 유무와 무관하게 즉시 404 반환 |
| `SecurityWebFilterChain` | JWT 검증 및 경로별 인증/인가 정책 적용 (401 / 403) |
| `UserContextRelayFilter` | 인증 통과 후 JWT에서 꺼낸 userId·roles를 `X-User-Id`, `X-User-Roles` 헤더에 담아 다운스트림으로 전달. 미인증 요청은 두 헤더를 강제 제거(위조 방지) |

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

`anyExchange().authenticated()`가 catch-all이라 명시하지 않아도 인증은 요구된다.
**공개 경로**이거나 **특정 역할 제한**이 필요한 경우에만 추가한다.

```java
.authorizeExchange(exchange -> exchange

    // 공개 (토큰 불필요)
    .pathMatchers(HttpMethod.POST, "/api/v1/orders/webhook").permitAll()

    // 인증만 되면 누구나
    .pathMatchers("/api/v1/orders/**").authenticated()

    // 특정 역할만 — 해당 역할로 "전환하는" 진입점에는 사용하지 말 것
    .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")

    .anyExchange().authenticated()
)
```

**역할 선택 기준**

| 상황 | 선택 |
|---|---|
| 로그인 없이 호출해야 함 | `permitAll()` |
| 로그인만 하면 누구나 | `authenticated()` |
| 이미 해당 역할을 가진 사람만 | `hasRole("ROLE명")` |
| 역할로 "전환"하는 첫 진입점 | `authenticated()` — 실제 권한 검증은 서비스 내부에서 |

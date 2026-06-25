# Swagger 설정 가이드

게이트웨이에서 Swagger UI를 통합 제공하는 구조와 새 서비스 추가 시 해야 할 작업을 설명한다.

---

## 구조 한눈에 보기

```
브라우저 → localhost:8000/swagger-ui.html
              │
              ├─ all-services   → GET /v3/api-docs/all          (apigateway가 직접 집계)
              ├─ member-service → GET /member/api-docs           (게이트웨이 → member 프록시)
              └─ settlement-service → GET /settlement/api-docs   (게이트웨이 → settlement 프록시)
```

---

## 설정 위치별 역할

### 1. `application.yaml` — Swagger UI 드롭다운 목록

```yaml
springdoc:
  swagger-ui:
    urls:
      - name: all-services        # "Select a definition" 드롭다운에 표시되는 이름
        url: /v3/api-docs/all     # OpenApiAggregateController가 처리
      - name: member-service
        url: /member/api-docs
      - name: settlement-service
        url: /settlement/api-docs
```

### 2. `application-local.yaml` — 게이트웨이 라우트 + 집계 서비스 목록

```yaml
spring:
  cloud:
    gateway:
      routes:
        # Swagger 문서 경로 프록시 (StripPrefix=1 로 /member 접두사 제거 후 서비스로 전달)
        - id: member-docs
          uri: http://localhost:9100
          predicates:
            - Path=/member/api-docs, /member/swagger-ui.html, /member/swagger-ui/**
          filters:
            - StripPrefix=1

openapi:
  aggregate:
    services:
      # OpenApiAggregateController가 이 목록을 순회해 /v3/api-docs/all 을 만든다
      # 서비스가 꺼져 있으면 해당 서비스는 건너뛰고 나머지만 집계된다
      member-service: http://localhost:9100/api-docs
      settlement-service: http://localhost:9140/api-docs
```

> `application-compose.yaml`에도 동일한 구조로 컨테이너명 URL을 작성한다.

### 3. 각 서비스 모듈 — api-docs 경로 설정

```yaml
# {서비스}/src/main/resources/application.yml
springdoc:
  api-docs:
    path: /api-docs   # 기본값 /v3/api-docs 대신 /api-docs 로 통일 (게이트웨이 라우트와 맞춤)
```

---

## 새 서비스 추가 체크리스트

서비스명을 `member`(포트 9100)로 가정한다.

### 서비스 모듈 (member)

- [ ] springdoc 의존성 — 루트 `build.gradle.kts`에서 `3.0.3`으로 공통 관리되므로 **별도 추가 불필요**
- [ ] `application.yml`에 api-docs 경로 설정
  ```yaml
  springdoc:
    api-docs:
      path: /api-docs   # 기본값 /v3/api-docs 대신 /api-docs 로 통일
  ```

### apigateway `application-local.yaml`

- [ ] Swagger 문서 라우트 추가
  ```yaml
  - id: member-docs
    uri: http://localhost:9100
    predicates:
      - Path=/member/api-docs, /member/swagger-ui.html, /member/swagger-ui/**
    filters:
      - StripPrefix=1
  ```
- [ ] 집계 서비스 목록에 추가
  ```yaml
  openapi:
    aggregate:
      services:
        member-service: http://localhost:9100/api-docs
  ```

### apigateway `application.yaml`

- [ ] Swagger UI 드롭다운 목록에 추가
  ```yaml
  springdoc:
    swagger-ui:
      urls:
        - name: member-service
          url: /member/api-docs
  ```

### apigateway `application-compose.yaml`

- [ ] `application-local.yaml`과 동일하게 작성 (URL만 컨테이너명으로 변경)
  ```yaml
  openapi:
    aggregate:
      services:
        member-service: http://member:9100/api-docs
  ```

# search (검색 서비스)

한정 수량 드롭 커머스의 **검색 도메인** 서비스. 이 모듈은 **기본 스캐폴딩**만 담고 있고, 실제 검색 기능(색인·조회)은 담당자가 이어서 구현한다.

- **포트:** `9240`
- **베이스 패키지:** `com.openat.search`
- **스키마:** 공유 DB `openat`의 `search` 스키마 (`hibernate.default_schema: search`)

## 클린 아키텍처 계층

의존성은 항상 안쪽(도메인)으로만 향한다 (PROJECT.md §3).

| 계층 | 패키지 | 이 모듈의 예시 |
|---|---|---|
| 도메인 | `domain` (`model`·`repository`(포트)·`error`) | `SearchErrorCode` |
| 애플리케이션 | `application` (`usecase`·`service`·`dto`) | `SearchHealthUseCase` → `SearchHealthService` |
| 인프라 | `infrastructure` (`persistence`·`config`) | `SecurityConfig`·`OpenApiConfig` |
| 프레젠테이션 | `presentation` (`controller`·`dto`) | `SearchHealthController` |

`SearchHealth*`는 계층 배선을 보여주는 참조 슬라이스다. 실제 검색 API로 대체한다.
빈 계층(`domain.model`·`domain.repository`·`infrastructure.persistence`)은 `package-info.java`에 책임을 적어 뒀다. 포트→어댑터 등 상세 예시는 `product`·`member` 모듈을 참고한다.

## 실행

전제: 로컬 PostgreSQL(`openat` DB, `search` 스키마)과 루트 `.env`(`DB_USER`/`DB_PASSWORD`). 스키마는 `db/init/01-schemas.sql`(신규 볼륨) 또는 `db/create-schemas.sh`(기존 컨테이너)로 생성된다.

```bash
./gradlew :search:bootRun        # 로컬 기동 (기본 프로필 local, 포트 9240)
./gradlew :search:test           # 컨텍스트 로딩 테스트 (TestContainers PostgreSQL)
./gradlew :search:build          # spotless + 컴파일 + 테스트
```

- 상태 확인: `GET http://localhost:9240/api/v1/search/health`
- Swagger: `http://localhost:9240/swagger-ui.html`

## 다음 단계 (담당자)

- 검색 엔진(Elasticsearch) 의존성·연결 설정 추가 → 색인/조회 어댑터를 `infrastructure.persistence`에 구현
- `product.created.events`·`product.updated.events` 구독(색인 갱신) — Kafka 의존성은 이때 추가 (PROJECT.md §9)

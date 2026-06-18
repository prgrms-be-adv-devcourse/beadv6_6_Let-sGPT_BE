# 초기 설정 & 실행 가이드

## 0. 최초 1회 준비

1. `.env.example`을 복사해서 `.env` 생성 (팀원 전체 동일한 값 사용)
   ```bash
   cp .env.example .env
   ```
2. JDK 21 설치 (1번 실행 시 IntelliJ가 직접 띄우고, 2번 실행 시 로컬에서 `./gradlew build`가 필요)
3. Docker Desktop(WSL2 backend) 설치 및 실행
4. IntelliJ Lombok 플러그인 설치 + Annotation Processing 활성화
   (Settings → Build Tools → Compiler → Annotation Processors → Enable)

## 1. 로컬 모듈 테스트 (`local` 프로필)

특정 모듈 하나를 IntelliJ로 직접 실행하면서 개발할 때 사용.

```bash
docker compose up -d postgres kafka redis
```

- `postgres`는 최초 기동(빈 볼륨) 시 `db/init/01-schemas.sql`이 자동 실행되어
  `openat` DB 안에 `member/product/order/payment/settlement` 5개 스키마가 자동 생성됨.
- 이미 떠 있던 볼륨이라 자동 생성이 안 됐다면:
  ```bash
  ./db/create-schemas.sh
  ```
- 이후 IntelliJ에서 원하는 모듈을 그냥 실행 (`application.yml` 기본 프로필이 `local`이라 별도 설정 불필요)
- 다른 모듈을 호출해야 하면 해당 모듈도 같은 방식으로 IntelliJ에서 함께 실행

### `.env` 자동 연동 (Run Configuration에 환경변수 수동 입력 불필요)

`.env`는 docker compose만 자동으로 읽고 IntelliJ/Gradle로 직접 실행하는 JVM은 모르기 때문에,
`${DB_USER}` 같은 placeholder가 풀리지 않아 `password authentication failed for user "${DB_USER}"`
같은 에러가 날 수 있다. 이를 막기 위해 `springboot4-dotenv` 라이브러리(루트 `build.gradle.kts`)를
추가해서 **앱이 기동할 때 직접 `.env`를 읽도록** 했다.

- 각 모듈 `application.yml`에 `springdotenv.directory: ..` 설정 — 모듈 폴더(예: `member/`)가
  작업 디렉토리이므로 한 단계 위(repo 루트)의 `.env`를 찾아서 읽음
- IntelliJ Run/Debug Configuration에 환경변수를 따로 입력할 필요 없음 — 그냥 실행하면 됨
- 실제 OS 환경변수가 있으면 그게 우선되고, 없을 때만 `.env` 값을 씀 (운영 환경에서는 안전)

## 2. 로컬 통합 테스트 (`compose` 프로필)

5개 모듈을 한 번에 컨테이너로 띄워서 통합 테스트할 때 사용. 직접 빌드하지 않고
GHCR에 올라온 이미지를 받아서 실행한다.

> 예전에는 별도의 `dev` 프로필(`application-dev.yml`)로 분리돼 있었지만,
> "GHCR 이미지를 받아 docker compose로 5개 모듈을 한 번에 띄운다"는 실행 방식 자체가
> 로컬(`docker-compose.full.yml`)과 EC2 배포(`docker-compose.dev.yml`)에서 동일해서
> `compose` 프로필 하나로 합쳤다. 차이는 값의 출처뿐: 로컬은 `.env`, EC2 배포는 GitHub Secrets.

```bash
# 1) 내가 수정한 코드를 dev 브랜치에 push
#    -> ci.yml이 5개 모듈을 빌드해 ghcr.io/.../{module}:latest 로 푸시함
# 2) 최초 1회: GHCR 로그인 (private 패키지인 경우)
docker login ghcr.io -u <github-id>   # PAT(read:packages) 필요

# 3) 최신 이미지 받아서 전체 기동
docker compose -f docker-compose.full.yml up
```

- 모든 서비스가 `image: ghcr.io/${GITHUB_REPOSITORY}/{module}:latest` + `pull_policy: always`로
  설정되어 있어 컴파일 없이 항상 최신 이미지를 받아 바로 실행됨.
- 특정 커밋 시점으로 고정해서 재현해야 할 때는 compose 파일의 태그를
  `:latest` → `:${commit-sha}`로 바꿔서 실행.
- `postgres`/`kafka`/`redis`는 빌드 없이 공개 이미지를 그대로 사용.

## 3. 인프라 통합 테스트 (EC2 배포, `compose` 프로필)

`dev` 브랜치 push 시 CI가 이미지를 GHCR에 올리고, 이후 배포 워크플로(`deploy.yml`, 구현 예정)가
EC2에서 `docker compose pull && up`을 수행. 비밀값은 `.env` 대신 GitHub Secrets로 주입됨.

## 4. CI 빌드 (`ci.yml`)

`dev`(또는 `main`) 브랜치 push 시 어떤 모듈 폴더가 바뀌었는지 `dorny/paths-filter`로 감지해서
**변경된 모듈만** `./gradlew :모듈:build`로 빌드한다 (`common/`이나 루트 빌드 설정이 바뀌면
영향 범위가 전체라 5개 모듈 다 빌드). 안 바뀐 모듈은 빌드 자체를 스킵.

- CI는 **컴파일 + 패키징 검증만** 한다. 아직 테스트 코드가 없어서(`src/test` 비어있음) DB/Kafka/Redis에
  실제로 접속하는 일이 없고, `CI_VARS`의 `DB_HOST`/`KAFKA_BROKER`/`REDIS_HOST` 등은 단지
  `application-compose.yml`의 `${...}` placeholder가 안 풀려서 빌드가 깨지는 것만 막는 더미값이다.
  그래서 postgres/kafka/redis 컨테이너를 CI에 띄우지 않는다.
- **실제 인프라 연동(DB 쿼리, Kafka 발행/구독, Redis 캐시 등)이 제대로 동작하는지는 2번(Local Full Stack)에서
  GHCR 이미지를 받아 실제 postgres/kafka/redis와 함께 띄워서 수동으로 확인한다.** CI에서 같은 걸 또
  컨테이너로 검증하면 2번과 중복되면서 빌드만 느려지므로 의도적으로 안 한다.
- 나중에 테스트 코드를 추가할 때는, CI에서 실제 컨테이너를 띄우는 대신 H2(인메모리 DB) +
  Kafka/Redis 클라이언트 모킹으로 단위/슬라이스 테스트를 돌리는 걸 권장 — 인프라 디테일(실제 Postgres
  전용 SQL, 실제 직렬화 등)은 어차피 2번에서 잡으므로, CI는 빠르게 로직만 검증하는 역할로 남긴다.

## 참고

- DB 자격증명·이미지 경로(`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `GITHUB_REPOSITORY`)와
  `KAFKA_BROKER`, `REDIS_HOST`, `REDIS_PORT`는 compose 파일이 직접 참조하므로 팀원 전체가 같은 `.env`를 가져야 함.
- `PG_SECRET_KEY`(결제), `JWT_SECRET`(회원)은 기능 구현 시 `.env`에 값 채우고
  해당 서비스의 `environment:` 블록에 매핑 추가 필요.

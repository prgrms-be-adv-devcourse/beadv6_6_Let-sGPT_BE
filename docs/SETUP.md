# 초기 설정 & 실행 가이드

## 0. 최초 1회 준비

1. 팀 채널에서 공유받은 값으로 저장소 루트에 `.env` 생성. 현재 저장소에는
   `.env.example`이 없으며 실제 `.env`는 커밋하지 않는다.
   - 공통 로컬 기동: `DB_USER`, `DB_PASSWORD`, `DB_NAME`
   - member: `JWT_KEY_ID`, `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`
   - payment: `PAYMENT_FIELD_ENCRYPTION_KEY`; 실제 토스 테스트 호출 시 `PG_SECRET_KEY`
   - AI·검색: 아래 각 절의 추론·조회·검색 키
2. JDK 21 설치 (1번 실행 시 IntelliJ가 직접 띄우고, 2번 실행 시 로컬에서 `./gradlew build`가 필요)
3. Docker Desktop(WSL2 backend) 설치 및 실행
4. IntelliJ Lombok 플러그인 설치 + Annotation Processing 활성화
   (Settings → Build Tools → Compiler → Annotation Processors → Enable)

## 1. 로컬 모듈 테스트 (`local` 프로필)

특정 모듈 하나를 IntelliJ로 직접 실행하면서 개발할 때 사용.

```bash
docker compose up -d
```

- 위 한 줄로 필수 인프라 전부(postgres·kafka·elasticsearch·redis·minio)가 뜬다. 서비스명을
  나열하지 않는다 — 나열하면 새로 추가된 인프라(예: minio)가 빠져 이미지 업로드 등이 조용히 깨진다.
- 상시 인프라 컨테이너는 `restart: always`라 최초 1회만 띄우면 이후 Docker/PC 재시작 시
  자동으로 같이 뜬다. 1회성 `minio-init`만 `restart: "no"`다.
- 웹 UI 도구(redis-commander·kafka-ui)는 `tools` 프로필로 분리되어 기본 기동에서 제외 —
  필요할 때 `docker compose up -d redis-commander` 처럼 이름을 지정해 띄운다.
- 상품 이미지 기능은 MinIO가 필요하며, `minio-init`이 로컬 이미지 버킷을 생성한다(1회성 컨테이너,
  Exited 상태가 정상).
- `postgres`는 최초 기동(빈 볼륨) 시 `db/init/01-schemas.sql`을 자동 실행한다. 현재 로컬
  초기화 파일은 `member/product/search/orders/payment/settlement/recommendation` 스키마를 만든다.
- AI의 기본 JPA 스키마는 `ai`다. 로컬 초기화 SQL에는 아직 포함되지 않았으므로 AI를 함께 띄우거나,
  이미 떠 있던 볼륨에 스키마를 보완할 때는 다음 멱등 스크립트를 실행한다.
  ```bash
  ./db/create-schemas.sh
  ```
  이 셸 스크립트는 `.env`를 직접 source하지 않는다. 현재 셸에 `DB_USER`가 export되지
  않았으면 `postgres`를 기본 사용자로 쓰므로, compose의 `DB_USER`가 다르면
  `DB_USER=<실제 사용자> ./db/create-schemas.sh`처럼 명시한다.
- k3s의 DB 초기화 목록은 `k8s/base/01-configmap.yaml`이 별도로 소유하며
  `member/product/orders/payment/settlement/ai`를 만든다. 현재 로컬 SQL과 목록이 다르므로
  한쪽 파일만 보고 다른 환경의 스키마를 추정하지 않는다.
- 이후 IntelliJ에서 원하는 모듈을 그냥 실행 (`application.yml` 기본 프로필이 `local`이라 별도 설정 불필요)
- 다른 모듈을 호출해야 하면 해당 모듈도 같은 방식으로 IntelliJ에서 함께 실행
- **Kafka 접속 포트(`local` 프로필 주의):** 호스트에서 직접 띄우는 모듈은 Kafka의 EXTERNAL 리스너인
  `localhost:29092`로 붙어야 한다(`docker-compose.yml`). 도커 네트워크 내부용 INTERNAL 리스너
  (`kafka:9092`)는 호스트에서 broker 주소가 resolve되지 않아 발행/구독이 실패한다. 각 모듈
  `application-local.yml`의 `spring.kafka.bootstrap-servers`는 `localhost:29092`로 맞춘다.

### `.env` 자동 연동 (Run Configuration에 환경변수 수동 입력 불필요)

`.env`는 docker compose만 자동으로 읽고 IntelliJ/Gradle로 직접 실행하는 JVM은 모르기 때문에,
`${DB_USER}` 같은 placeholder가 풀리지 않아 `password authentication failed for user "${DB_USER}"`
같은 에러가 날 수 있다. 이를 막기 위해 `springboot4-dotenv` 라이브러리(루트 `build.gradle.kts`)를
추가해서 **앱이 기동할 때 직접 `.env`를 읽도록** 했다.

- 각 모듈 `application.yml`에 `springdotenv.directory: ..` 설정 — 모듈 폴더(예: `member/`)가
  작업 디렉토리이므로 한 단계 위(repo 루트)의 `.env`를 찾아서 읽음
- IntelliJ Run/Debug Configuration에 환경변수를 따로 입력할 필요 없음 — 그냥 실행하면 됨
- 실제 OS 환경변수가 있으면 그게 우선되고, 없을 때만 `.env` 값을 씀 (운영 환경에서는 안전)

### AI 모듈 최초 기동

팀원이 이미 사용하는 루트 `.env`의 `DB_USER`·`DB_PASSWORD`를 그대로 사용한다. 아래 AI 런타임
설정만 팀 채널에서 공유받아 같은 `.env`에 추가하면 되고, `AI_READ_MODEL_*` 변수를 만들거나
`ai/scripts/apply-read-model.sh`를 직접 실행할 필요는 없다.

```dotenv
CHAT_INFERENCE_ENABLED=true
CHAT_INFERENCE_BASE_URL=<외부 fallback이 없는 것으로 확인된 OpenAI 호환 URL>
CHAT_INFERENCE_MODEL=<해당 경로의 모델 별칭>
CHAT_INFERENCE_LOCAL_ONLY_ROUTE=true
CHAT_INFERENCE_API_KEY=<팀 공유 추론 키>

CHAT_DATA_ENABLED=true
AI_QUERY_DB_URL=jdbc:postgresql://127.0.0.1:5432/openat
AI_QUERY_DB_USERNAME=ai_query_app
AI_QUERY_DB_PASSWORD=<팀 공유 로컬 조회 비밀번호>

CHAT_WEB_SEARCH_ENABLED=true
TAVILY_API_KEY=<팀 공유 Tavily 키>
```

- `local` 프로필은 `CHAT_DATA_ENABLED=true`이고 조회 DB 설정이 완성되면 기존 주 DB 관리자
  연결(`DB_USER`·`DB_PASSWORD`)로 `ai_read` view·함수와 `ai_query_app` 권한을 자동 구성한다.
- 이미 정확한 계약이 있으면 DDL을 다시 실행하지 않는다. 최초 실행 순서 때문에 원본 도메인 테이블이
  아직 없으면 AI 서버 기동을 막지 않고 15초 간격으로 다시 확인한다.
- `CHAT_DATA_ENABLED`는 관리자 챗봇의 내부 DB 조회 기능 토글이다. `false`여도 일반 챗봇과 외부
  도구는 기동할 수 있지만 주문·회원·정산 같은 내부 집계는 사용할 수 없다.
- Tavily 키가 없으면 서버 자체는 기동하지만 공개 웹 검색 도구만 사용할 수 없다.
- 원격 주소에서 `CHAT_INFERENCE_LOCAL_ONLY_ROUTE=true`는 단순 연결 옵션이 아니라 외부 provider
  폴백이 없다는 운영자 확인이다. 현재 `https://api.inferway.xyz/v1`의 `chat` 별칭은 외부 폴백
  가능 계약이므로 관리자 내부 데이터 질문에 그대로 사용하지 않는다. 인프라 담당자가 비폴백
  경로를 제공하기 전에는 이 값을 `true`로 두지 않는다.

## 2. 레거시 로컬 풀스택 (`compose` 프로필)

핵심 커머스 서비스와 search를 컨테이너로 띄우던 과거 실행 경로다. 직접 빌드하지 않고
GHCR 이미지를 받지만, 현재 queue·ai·운영 인프라 구성을 포함하지 않으므로 통합 테스트 기준으로
사용하지 않는다.

> ⚠️ **2026-07-10 레거시**: 아래 docker-compose 풀스택 실행은 k3s+ArgoCD 전환으로 더 이상 쓰지 않는다.
> `docker-compose.full.yml`/`docker-compose.dev.yml`은 `legacy/`로 이동했다(참고용). 현재 통합/배포는 k3s(`k8s/`)로 한다.
>
> 예전에는 별도의 `dev` 프로필(`application-dev.yml`)로 분리돼 있었지만,
> "GHCR 이미지를 받아 docker compose로 서비스들을 한 번에 띄운다"는 실행 방식 자체가
> 로컬(`legacy/docker-compose.full.yml`)과 EC2 배포(`legacy/docker-compose.dev.yml`)에서 동일해서
> `compose` 프로필 하나로 합쳤다. 차이는 값의 출처뿐: 로컬은 `.env`, EC2 배포는 GitHub Secrets.

```bash
# (레거시) 최신 이미지 받아서 전체 기동
docker login ghcr.io -u <github-id>   # PAT(read:packages) 필요
docker compose -f legacy/docker-compose.full.yml up
```

- 모든 서비스가 `image: ghcr.io/${GITHUB_REPOSITORY}/{module}:latest` + `pull_policy: always`로
  설정되어 있어 컴파일 없이 항상 최신 이미지를 받아 바로 실행됨.
- 특정 커밋 시점으로 고정해서 재현해야 할 때는 compose 파일의 태그를
  `:latest` → `:${commit-sha}`로 바꿔서 실행.
- `postgres`·`kafka`·`redis`·`elasticsearch`·`minio`는 공개 이미지를 사용한다.

## 3. 운영 배포 (`main` → k3s·ArgoCD)

`dev` push는 이미지 검증·게시까지만 수행하고 배포하지 않는다. `main`의 `ci-merge.yml`이 성공하면
`deploy.yml`이 변경 서비스 매트릭스를 받아 GHCR 이미지 SHA를 `deploy/state` 브랜치에 고정한다.
ArgoCD가 이 브랜치를 감시해 k3s에 auto-sync하고, 배포 워크플로는 대상 revision이
`Synced/Healthy`가 될 때까지 확인한다. 비밀값은 GitHub Secrets에서 Kubernetes Secret으로 만든다.

AI read-model은 전체 배포마다 실행하는 Sync Hook이 아니다. DDL·검증
스크립트와 `AI_QUERY_DB_PASSWORD` Secret revision으로 Job identity를 만들고, 둘 중 하나가
바뀔 때만 새 Job을 실행한다. 완료된 동일 identity Job은 그대로 재사용하며, 적용 Job(wave 1)이
성공한 뒤 AI Deployment(wave 2)가 진행된다. 운영 `ENV_SECRETS`에는
`AI_QUERY_DB_PASSWORD`, `CHAT_INFERENCE_API_KEY`, `TAVILY_API_KEY`가 모두 필요하다.

## 4. CI 빌드 (`ci-branch.yml`·`ci-merge.yml`)

- 기능 브랜치 push는 `ci-branch.yml`이 변경된 서비스만 `:모듈:build`하고 이미지는 게시하지 않는다.
- `dev`·`main` push는 `ci-merge.yml`이 변경된 서비스만 빌드하고 GHCR에 `latest`·commit SHA 태그를
  게시한다. `common/` 또는 루트 Gradle 설정이 바뀌면
  `apigateway/member/order/payment/product/settlement/search/queue/ai` 9개 서비스를 모두 빌드한다.
- `:모듈:build`에는 현재 존재하는 단위·슬라이스·통합 테스트가 포함된다. PostgreSQL·Redis가 필요한
  테스트는 Testcontainers가 컨테이너를 직접 기동하므로 Docker 사용이 가능해야 한다.
- Spotless는 현재 non-blocking이고, k8s·ArgoCD·observability 변경은 별도 렌더·정합성 검증을 거친다.
- `main` 성공 결과만 배포 메타데이터를 `deploy.yml`에 전달한다. `dev`는 운영 클러스터를 갱신하지 않는다.

CI 통과는 브라우저 기반 통합 테스트를 대체하지 않는다. 서비스 간 라우팅·Kafka 전파·PG·Redis·
Elasticsearch·SSE까지 연결된 흐름은 k3s 배포 뒤 실제 Gateway URL에서 별도로 검증한다.

## 5. PG(토스) 웹훅 로컬 연동 — ngrok

`local`/`compose` 프로필에서 Toss 웹훅(`/api/v1/payments/webhook`, `/api/v1/wallet/charge/webhook`,
`/api/v1/refunds/webhook`)을 로컬 환경에서 직접 받아보려면 외부에서 접근 가능한 URL이 필요하다
(로컬 포트만으로는 Toss 개발자센터에 웹훅 URL로 등록할 수 없음). 고정 서브도메인은 ngrok 유료 플랜이
필요해 지금 범위 밖이므로, 매번 무료 ngrok으로 터널을 새로 띄우고 그때그때 등록하는 방식으로 운용한다.

```bash
# 1) ngrok 설치 (최초 1회)
#    https://ngrok.com/download 참고, 설치 후 authtoken 등록
ngrok config add-authtoken <your-authtoken>

# 2) payment 모듈을 9130 포트로 기동한 상태에서 터널 오픈
ngrok http 9130
```

- 위 명령 실행 시 출력되는 `https://<random>.ngrok-free.app` 형태의 URL을 Toss 개발자센터의
  웹훅 등록 화면에서 결제/충전/환불 각 웹훅 엔드포인트(`/api/v1/payments/webhook` 등)에 등록한다.
- 무료 플랜은 ngrok을 재시작할 때마다 URL이 바뀌므로, **이 URL은 `.env`나 설정 파일에 저장하지
  않고** 그때그때 Toss 개발자센터에 수동으로 다시 등록하는 것으로 확정.

### PG 키 설정

- payment 백엔드는 `.env`의 `PG_SECRET_KEY`를 `pg.secret-key`로 사용한다. 테스트 키도 팀의
  비밀 채널로 공유하고 Git에는 올리지 않는다. local에서 값이 없으면 dummy 문자열로 기동은 되지만
  실제 토스 요청은 인증 실패한다.
- `PG_CLIENT_KEY`는 브라우저의 Toss SDK 초기화에 쓰는 FE 설정이다. 레거시 compose에 이름이
  남아 있어도 현재 payment 애플리케이션은 이 값을 읽지 않는다.
- `PAYMENT_FIELD_ENCRYPTION_KEY`는 `pgPaymentKey`/`pgRefundKey` 등 DB에 저장되는 PG 민감정보 컬럼을
  암호화(AES-GCM)하는 우리 쪽 자체 키다(토스가 준 키가 아님 — `PG_CLIENT_KEY`/`PG_SECRET_KEY`와는
  보호 대상이 다름). Base64로 인코딩된 32바이트 키 값을 같은 방식(Slack 등 별도 채널, 깃에 안 올림)으로
  공유받아 `.env`에 채운다.

## 참고

- 운영·레거시 compose는 `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`,
  `GITHUB_REPOSITORY`, `KAFKA_BROKER`, `REDIS_HOST`, `REDIS_PORT` 등을 참조한다. 로컬 인프라
  compose와 IntelliJ 실행은 각 프로필 설정에 필요한 값만 사용한다.
- 결제의 `PG_SECRET_KEY`·`PAYMENT_FIELD_ENCRYPTION_KEY`, 회원의
  `JWT_KEY_ID`·`JWT_PRIVATE_KEY`·`JWT_PUBLIC_KEY`는 비밀값이다. k3s에서는
  `k8s/bootstrap/create-secrets.sh`가 서비스별 Secret으로 나눠 주입한다.

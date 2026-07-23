# loadtest — drop → queue → order → PG payment (live EC2 대상)

k6가 apigateway를 통해 전체 플로우를 돌린다. 토스페이먼츠는 **WireMock**으로 대체하며,
그래서 브라우저/토스 SDK 단계가 필요 없다: `POST /api/v1/payments/confirm` 이 PG 결제의
단일 진입점이고, 클라이언트가 준 `paymentKey`를 PG에 그대로(opaque) 전달하므로 k6가 값을
지어낼 수 있다.

**대상은 EC2에 떠 있는 실 배포다.** 로컬 docker compose가 아니다.

| | |
|---|---|
| 런타임 | k3s 2노드. **server = `semi`** (Name `letsGpt-openAt-semi`, t3.large = 2 vCPU/8GiB, label `tier=hotpath`) 위에 앱 JVM 6개. **agent = `final`** (Name `letsGpt-openAt-final`, t3.medium, label `tier=observability`, taint `dedicated=observability:NoSchedule`) 위에 관측 스택 |
| 배포 | ArgoCD Application `openat` (branch `deploy/state`, path `k8s/overlay`, `automated{prune,selfHeal}`) |
| 진입점 | `https://openat.duckdns.org` → traefik → `apigateway:8000` |
| Grafana | `https://grafana.openat.duckdns.org` |
| 접속 | **AWS SSM Session Manager** (ssh 아님). region `ap-northeast-2` |
| kubeconfig | `/etc/rancher/k3s/k3s.yaml`, mode 0644 (`terraform/user_data.sh.tpl`) — `.github/workflows/deploy.yml` 의 `env.KUBECONFIG` 와 동일 |

부하가 실제로 압박하는 노드는 **`semi` 하나뿐**이다(t3.large, 2 vCPU). 앱 파드에는
nodeSelector가 없고 `final` 에는 taint가 걸려 있어 앱이 그쪽으로 새지 않는다. WireMock 목도
toleration이 없으므로 payment와 같은 `semi` 노드에 뜬다 — 의도한 것이다(PG 콜 RTT가 서비스
간 실제 경로와 같아진다). 아래 `kubectl` 명령은 전부 `semi` 노드의 SSM 셸에서 실행한다.

> ### ⚠️ 절대 잊지 말 것
> PG 목을 켜면 **이 클러스터의 모든 결제가 가짜 PG로 간다.** 승인·취소가 전부 허구이며,
> 그 상태로 방치하면 진짜 사용자의 결제도 조용히 가짜 PG로 흘러간다(에러가 안 난다 —
> 그래서 위험하다). 테스트가 끝나면 **반드시** `pg-mock-off.sh` 를 돌리고,
> ArgoCD auto-sync가 복원됐는지 눈으로 확인한다(§6).

> ### ⚠️ 시작 전 확인
> `PG_BASE_URL` 오버라이드(2026-07-20 미해결이었던 건)는 main/deploy/state에 반영 완료.
> 단, 배포된 payment 이미지가 **HTTP/1.1 고정 수정(TossClientConfig의
> `version(HTTP_1_1)`)까지 포함하는 버전**이어야 한다 — 이 수정 이전 이미지는 JDK
> HttpClient 기본값(HTTP/2)이 평문 WireMock에 h2c 업그레이드를 시도하다 스트림이 깨져
> (`RST_STREAM`·빈 body) 토스 서킷이 열려 고착되고, confirm이 전멸한다(2026-07-23 확진).
> 어느 쪽이든 아래 §4의 WireMock 저널 확인을 통과한 뒤에만 램프업할 것.

---

## 0. 왜 매니페스트가 `k8s/base` 에 없는가

WireMock 매니페스트는 `loadtest/k8s/wiremock.yaml` 에 있고 **수동 apply만** 한다.

- `k8s/base/` 나 `k8s/overlay/` 에 넣으면 `deploy/state` 의 desired-state가 되어 가짜 PG가
  상시 배포된다. 그건 사고다.
- ArgoCD의 `prune: true` 는 **ArgoCD가 tracking 중인** 리소스만 지운다. tracking 판정은
  기본 설정(`application.instanceLabelKey` 미지정)에서 `app.kubernetes.io/instance: openat`
  라벨로 한다. `loadtest/k8s/wiremock.yaml` 은 그 라벨을 절대 붙이지 않으므로 ArgoCD
  입장에서는 존재조차 모르는 orphan이고 prune 대상이 되지 않는다(AppProject에
  `orphanedResources` 설정도 없어 경고 표시조차 안 뜬다).
  → **`app.kubernetes.io/instance: openat` 를 실수로 붙이면 그 순간 입양당해 다음 sync에서 삭제된다.**
- 반면 `kubectl set env deployment/payment PG_BASE_URL=...` 은 ArgoCD가 tracking하는
  워크로드를 건드리는 것이라 **selfHeal이 되돌린다.** 그래서 스크립트가 auto-sync를 먼저 끈다.

mappings ConfigMap은 매니페스트에 임베드하지 않았다. `loadtest/wiremock/mappings/*.json` 원본을
유일한 진실로 두기 위해 `kubectl create configmap --from-file` 로 생성한다(임베드하면 원본과
이중 관리가 되어 반드시 어긋난다). 생성은 `pg-mock-on.sh` 가 한 줄로 처리한다:

```bash
kubectl -n openat create configmap wiremock-toss-mappings \
  --from-file=<mappings dir> --dry-run=client -o yaml | kubectl apply -f -
```

---

## 1. `semi` 노드 접속 (SSM Session Manager)

ssh가 아니다. 보안그룹에 22번이 열려 있지 않고, 접속은 전부 SSM으로 한다.
로컬에 **AWS CLI + session-manager-plugin** 이 필요하다
(`terraform/session-manager-plugin.deb` 가 리포에 들어 있다).

### 인스턴스 id 얻기 — 방법 A: terraform 출력

`terraform/outputs.tf` 가 `instance_ids` 와 `ssm_connect_commands` 를 이미 노출한다.
**로컬에 terraform state가 있을 때만** 쓸 수 있다.

```bash
cd terraform
terraform output -json instance_ids          # {"semi":"i-...","final":"i-..."}
terraform output -json ssm_connect_commands  # 접속 명령까지 통째로
```

### 인스턴스 id 얻기 — 방법 B: Name 태그로 조회 (terraform state 불필요, 권장)

```bash
export AWS_REGION=ap-northeast-2
SEMI_ID=$(aws ec2 describe-instances \
  --filters 'Name=tag:Name,Values=letsGpt-openAt-semi' 'Name=instance-state-name,Values=running' \
  --query 'Reservations[].Instances[].InstanceId' --output text)
echo "$SEMI_ID"

aws ssm start-session --target "$SEMI_ID"
```

> `semi` 가 k3s **server** 노드이고 kubeconfig(`/etc/rancher/k3s/k3s.yaml`, mode 0644)와
> 앱 파드가 전부 여기 있다. `final` 은 observability 전용 agent라 여기서 할 일이 없다.

### 셸에 붙은 뒤

SSM 세션의 기본 계정은 `ssm-user` 이고, `KUBECONFIG` export는 `/home/ubuntu/.bashrc` 에만
심겨 있다(`user_data.sh.tpl`). 그래서 둘 중 하나를 한다:

```bash
sudo su - ubuntu            # KUBECONFIG가 이미 export된 계정으로 전환 (권장)
# 또는
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml   # kubeconfig가 0644라 ssm-user도 읽을 수 있다
```

`loadtest/scripts/*.sh` 는 `KUBECONFIG` 를 스스로 `/etc/rancher/k3s/k3s.yaml` 로 기본
설정하므로, 스크립트만 돌릴 거면 위 export 없이도 동작한다.

### 스크립트를 노드에 올리는 법

`.github/workflows/deploy.yml` 은 `runs-on: [self-hosted, ec2-deploy]` + `actions/checkout@v4`
이므로 러너 작업 디렉터리
(`/home/deployer/actions-runner/_work/beadv6_6_Let-sGPT_BE/beadv6_6_Let-sGPT_BE`)에 체크아웃이
존재하는 것은 맞다. **하지만 그걸 쓰지 말 것:**

- 워크플로가 `git checkout deploy/state` 후 되돌리지 않아 그 트리는 `deploy/state` 브랜치에 있다
- CD가 한 번 더 돌면 그 트리에서 `git merge` / 강제 체크아웃이 일어나 우리가 편집한 내용이 날아간다
- 소유자가 `deployer` 이고 그 계정은 sudo 권한이 없다(`user_data.sh.tpl`)

**대신 `ubuntu` 홈에 별도 클론을 둔다** (public repo라 자격증명 불필요):

```bash
sudo su - ubuntu
git clone https://github.com/prgrms-be-adv-devcourse/beadv6_6_Let-sGPT_BE.git ~/loadtest-repo \
  || (cd ~/loadtest-repo && git fetch --all)
cd ~/loadtest-repo && git checkout main && git pull
```

> `loadtest/` 가 아직 커밋되지 않았다면 위 클론에는 이 디렉터리가 없다. 그럴 땐 커밋·푸시가
> 먼저다. 정말 급하면 SSM 세션에서 `cat > loadtest/scripts/pg-mock-on.sh <<'EOF' ... EOF`
> 로 붙여넣어도 되지만(SSM은 파일 전송을 못 한다), mapping JSON 4개까지 같이 붙여야 하므로
> 권장하지 않는다.

---

## 2. PG 목 켜기 (`semi` 노드에서)

```bash
cd ~/loadtest-repo
./loadtest/scripts/pg-mock-on.sh
# PG 지연 median을 바꾸려면 (기본 300ms):
# LT_PG_DELAY_MEDIAN_MS=800 ./loadtest/scripts/pg-mock-on.sh
```

스크립트가 하는 일(순서가 중요하다):

1. ArgoCD `openat` Application의 `syncPolicy.automated` 를 저장하고 **끈다**
   (안 끄면 selfHeal이 최대 3분 안에 2·3번을 되돌린다)
2. `wiremock-toss-mappings` ConfigMap 생성 → `loadtest/k8s/wiremock.yaml` apply → rollout 대기
3. `kubectl -n openat set env deployment/payment PG_BASE_URL=http://wiremock-toss.openat.svc.cluster.local:8080`
   → rollout 대기 (payment는 startupProbe 여유가 300s라 최대 7분까지 기다린다)
4. "지금부터 결제는 가짜다" 경고 출력

멱등이다. 여러 번 돌려도 결과가 같고, 원래 `automated` 값은 최초 실행 때만 저장한다
(재실행이 "이미 꺼진 값"으로 원본을 덮어쓰지 않도록).

---

## 3. 시딩 — 계정 + 판매자 + 상품 + 드롭 (노트북에서)

`seed.js` 하나가 부하테스트 대상 일체를 만든다. k6가 아니라 순수 Node(>=18)다 — k6 런타임은
파일을 못 쓴다. **노트북에서 공용 도메인으로 실행**하므로 SSM 세션이 필요 없다.

```bash
cd ~/projects/beadv6_6_Let-sGPT_BE
PROFILE=ramp USER_COUNT=60 node loadtest/k6/seed.js
# -> loadtest/k6/users.json   (VU별 계정)
# -> loadtest/k6/target.json  (dropId/openAt/totalQuantity/dropPrice/사이징 근거)
```

만드는 순서(전부 `scripts/smoke-drop-order.sh` 가 이미 검증한 체인이다):

1. 회원 N명 — `POST /api/v1/members` → `POST /api/v1/members/login`
2. 판매자 — `POST /api/v1/seller/me {businessNumber, storeName}`. 관리자 승인 없이 즉시
   `ROLE_SELLER` 로 승격된다. 재실행 시 `GET /api/v1/seller/me` 로 기존 건을 찾아 재사용한다.
3. scoped 토큰 — `POST /api/v1/seller/token {sellerInfoId}`
4. 상품 — `POST /api/v1/products` (201 + **Location 헤더**에 id, 바디에는 없다)
5. 드롭 — `POST /api/v1/drops` (201 + Location). 별도 publish 단계는 없다
6. `GET /api/v1/drops/{id}` 가 `OPEN` 이라고 말할 때까지 폴링한 뒤 `target.json` 을 쓴다

### scoped 토큰 (여기가 제일 잘 깨진다)

게이트웨이(`SecurityConfig:189-192`)는 `/api/v1/products`, `/api/v1/drops` 쓰기에
**scoped JWT**(`typ=scoped`, `aud` 에 `openat-product`)만 허용하고 일반 회원 access 토큰을
명시적으로 거부한다. 그리고 그 토큰의 TTL은 **120초**다
(`member/src/main/resources/application.yml:39` `jwt.scoped-token-expire-seconds: 120`).

시더는 발급 직후 상품·드롭을 연달아 만들고, 남은 수명이 30초 미만이면 알아서 재발급한다.
그래도 401/403이 나면 "만료였는지 권한이었는지"를 경과 초와 함께 알려준다 — 밋밋한 401로
끝나지 않는다.

### 드롭 사이징 (`totalQuantity`)

`totalQuantity` 는 큐가 통틀어 admit할 수 있는 총량이다. 이게 VU 수보다 작으면 대부분의
iteration이 `SOLD_OUT` 으로 끝나서 큐/주문/결제가 아니라 **매진 경합**만 측정된다.

시더는 선택한 프로파일의 stage 스펙을 사다리꼴 적분해 총 iteration 수를 추정하고,
`totalQuantity = ceil(추정 iteration × QTY × 1.3)` 으로 잡는다
(iteration당 `EST_ITER_SEC`, 기본 4초 가정):

| PROFILE | peak VUs | 추정 iteration | 기본 totalQuantity |
|---|---|---|---|
| `smoke` | 3 | ~42 | ~55 |
| `ramp` | 40 | ~2,813 | ~3,657 |
| `hold` (VU 20) | 20 | ~3,300 | ~4,290 |
| `stress` | 100 | ~2,625 | ~3,413 |

`DROP_TOTAL_QUANTITY` 로 덮어쓸 수 있다. 반대편 방어선은 `drop-flow.js` 의 `setup()` 인데,
**잔여 재고 < peakVU × QTY × 2** 면 VU를 띄우지 않고 거부한다.

> **`limitPerUser` 와의 상호작용**: 기본은 미지정(=무제한)이다. 값을 주면 admit 가능한
> 총량이 `(계정 수 × limitPerUser)` 로 잘려서 `totalQuantity` 를 아무리 키워도 그 벽에서
> 멈춘다. 예: 계정 60개 + `limitPerUser=2` ⇒ 최대 120개, 그 뒤는 전부 `SOLD_OUT`.
> 1인 한도 로직 자체를 시험할 때만 `DROP_LIMIT_PER_USER` 를 켤 것.

### 시계 오차와 `openAt`

`openAt` 은 `@Future` 로 검증되고 `OPEN` 판정도 **서버 시계**로 한다. 로컬이 앞서 있으면
"미래"로 보낸 값이 서버에겐 과거라 400이 나고, 뒤처져 있으면 k6를 띄울 때 아직 `REGISTERED`다.

눈감고 `sleep` 하는 대신 시더가 명시적으로 처리한다:

1. HTTP 응답의 `Date` 헤더와 로컬 시계를 비교해 오차를 잰다(해상도 ±1s). 5초를 넘으면 경고.
2. `openAt = 로컬 now + 오차 + DROP_OPEN_DELAY_S`(기본 60초)
3. 생성 후 `GET /api/v1/drops/{id}` 가 `OPEN` 이라고 답할 때까지 3초 간격으로 폴링한다.
   남은 오차는 여기서 흡수된다. 끝내 `OPEN` 이 안 되면 `DROP_OPEN_DELAY_S` 를 늘리라고 하며 실패한다.

### 그 밖에

- `BASE_URL` 기본값이 `https://openat.duckdns.org` 다.
- `SEED_CONCURRENCY` 기본 4. 원격에선 시딩 자체가 member 서비스에 대한 소형 부하이고,
  이제 판매자 등록 쓰기까지 얹힌다. 올릴 거면 의식적으로 올릴 것.
- `USER_COUNT` 는 **최대 VU 수 이상**으로. 계정이 부족하면 VU들이 계정을 공유하고, 게이트웨이의
  사용자별 confirm rate limit(replenish 2/s, burst 5)에 직렬화되어 `outcome_rate_limited_429`
  가 결과를 지배한다. `ramp` 는 peak 40이므로 60이면 충분(시더가 모자라면 경고한다).
- 멱등하다. 계정은 결정적 이메일이고, 판매자는 기존 건을 재사용하며, 드롭은 `target.json` 의
  것이 아직 `OPEN` + 재고 충분이면 그대로 다시 쓴다. 강제로 새로 만들려면 `FORCE_NEW_DROP=1`.
- `dropPrice` 기본값은 **10000** 이다 — WireMock `toss-query-payment.json` 스텁이 GET에
  body가 없어 `totalAmount` 를 10000으로 고정하고 있고 그 값이 reconcile 경로의 금액 비교에
  쓰인다. 두 값을 일부러 일치시켜 뒀다. **`DROP_PRICE` 를 바꾸면 스텁의
  `totalAmount` / `balanceAmount` / `cancelAmount` 를 같은 값으로 고쳐야 한다**
  (`loadtest/wiremock/mappings/toss-query-payment.json`). 시더와 `setup()` 둘 다 경고한다.
- 계정만 필요하면 `SEED_DROP=0`.

---

## 4. smoke 먼저 — 그리고 목이 진짜로 맞고 있는지 확인

**이 단계를 건너뛰고 램프업하면 실 토스를 때리고 있을 수 있다.**

```bash
# 노트북에서 — DROP_ID는 target.json에서 자동으로 읽는다
k6 run loadtest/k6/drop-flow.js                        # PROFILE=smoke 가 기본
k6 run -e DROP_ID=<uuid> loadtest/k6/drop-flow.js      # 손으로 만든 드롭을 쓸 때
```

`setup()` 이 먼저 다음을 검사하고, 하나라도 어긋나면 VU를 하나도 띄우지 않고 즉시 중단한다:

- `BASE_URL` 호스트가 허용목록(`openat.duckdns.org,localhost,127.0.0.1`)에 있는가
- `GET /api/v1/drops/{id}` 가 200이고 응답이 실제 `DropResponse` 스키마인가
  (k6에는 리졸버 API가 없어 IP를 직접 못 본다. "이 200이 정말 우리 API인가"는 스키마로 확인한다)
- **드롭 상태가 `OPEN` 인가** — 단순히 존재하는지가 아니라 `status === 'OPEN'` 을 본다.
  `REGISTERED`(아직 오픈 전) / `CLOSE` / `SOLD_OUT` 이면 거부하고, 어느 상태였는지와
  `openAt` 을 함께 알려준다. 닫힌 드롭에 30분을 태우는 사고를 여기서 끊는다.
- 잔여 재고가 peak 부하를 감당하는가 (`< peakVU × QTY × 2` 면 거부)
- `target.json` 의 사이징 프로파일과 지금 돌리는 `PROFILE` 이 다르면 경고한다
  (예: `smoke` 로 시딩해 놓고 `ramp` 를 돌리는 경우 — 재고 게이트가 곧바로 잡는다)

smoke가 끝나면 **WireMock 저널을 본다.** 여기가 유일하게 신뢰할 수 있는 확인 지점이다
(payment의 actuator는 `health,prometheus` 만 노출해 `/actuator/env` 로는 볼 수 없다):

**`semi` 노드의 SSM 셸에서** (port-forward 불필요 — Service의 ClusterIP는 노드 호스트에서
kube-proxy iptables를 그대로 타므로 평범한 `curl` 로 닿는다. SSM 셸에서 백그라운드 프로세스를
띄우고 관리하는 것보다 훨씬 낫다):

```bash
WM=$(kubectl -n openat get svc wiremock-toss -o jsonpath='{.spec.clusterIP}')
# 주의: GET /__admin/requests/count 는 없는 엔드포인트다("count is not a valid UUID" 에러
# — count는 POST 전용). 총 건수는 저널 조회의 meta.total로 읽는다.
curl -s "http://$WM:8080/__admin/requests?limit=1" | grep -o '"total" : [0-9]*'
curl -s "http://$WM:8080/__admin/requests?limit=5" | head -40
```

한 줄 게이트 — 0이면 즉시 중단하라고 소리친다:

```bash
WM=$(kubectl -n openat get svc wiremock-toss -o jsonpath='{.spec.clusterIP}')
N=$(curl -s "http://$WM:8080/__admin/requests?limit=1" | grep -o '"total" : [0-9]*' | grep -o '[0-9]\+')
[ "${N:-0}" -gt 0 ] && echo "OK: 목이 요청 $N건을 받았다" \
                    || echo "중단: 목에 요청이 0건 — 실 토스를 때리는 중일 수 있다"
```

노트북에서 보고 싶다면 kubeconfig를 로컬로 가져온 뒤에만 가능하다(기본적으로 없다).
그 경우에만 port-forward를 쓴다:

```bash
# 노트북 (kubeconfig가 로컬에 있을 때만)
kubectl -n openat port-forward svc/wiremock-toss 8089:8080 &
curl -s "localhost:8089/__admin/requests?limit=1" | grep -o '"total" : [0-9]*'
```

- `total` 이 smoke의 성공 건수만큼 올라갔다 → 목이 붙었다. 계속 진행.
- `total` 이 **0이다** → payment는 목을 부르지 않고 있다. 배포된 payment 이미지가
  `pg.base-url`/`PG_BASE_URL` 오버라이드를 지원하는 버전인지부터 확인할 것(맨 위 경고 박스).
  **이 상태로 램프업하면 실 토스에 부하를 거는 것이다. 즉시 중단.**

---

## 5. ramp — 무릎 찾기

```bash
# 시딩을 PROFILE=ramp 로 했다면 재고가 이미 맞춰져 있다
k6 run -e PROFILE=ramp loadtest/k6/drop-flow.js
```

### 프로파일

| PROFILE | stages | 용도 |
|---|---|---|
| `smoke` (기본) | `20s:3,40s:3,10s:0` | 흐름이 끝까지 도는지 증명. 부하 아님 |
| `ramp` | `1m:5,2m:10,2m:20,2m:30,2m:40,1m:0` | 무릎 탐색. 계단마다 2분 유지 |
| `hold` | `1m:N,10m:N,1m:0` (`-e HOLD_VUS=N`) | 무릎 지점에서 큐/아웃박스/정산이 밀리는지 |
| `stress` | `30s:20,1m:60,1m:100,1m:0` | 포화 시 어떻게 깨지는지. 용량 수치 아님 |

`-e STAGES=...` 또는 `-e VUS=.. -e DURATION=..` 을 주면 프로파일을 무시하고 그 값을 쓴다.

### 숫자 근거

- payment: `maximumPoolSize=2`, `connectionTimeout=5000ms`, `tomcat threads=50`, replicas 1
- WireMock confirm 스텁: lognormal median 300ms
- confirm 1건이 DB 커넥션을 잡은 채 PG 왕복 300ms를 기다린다고 보면
  **이론 상한 ≈ pool(2) / 0.3s ≈ 6~7 confirm/s**
- 1 iteration ≈ 3~5초(polling + think 1s) → VU 하나가 만드는 confirm ≈ 0.2~0.3/s
- ⇒ **무릎은 VU 25~35 부근.** `ramp` 는 5→10→20→30→40으로 그 앞뒤를 모두 통과한다.

이전 기본값이었던 200 VU는 무릎을 20배 넘겨 뛰어버리는 값이라, 측정이 아니라
Hikari 5초 타임아웃 벽(`connection is not available`)만 찍고 끝난다.
계단을 30s가 아니라 2m로 잡은 것도 같은 이유다 — 30s면 램프 과도구간만 보게 되고
p95가 안정되지 않는다.

### 커넥션 정책 (원격/TLS 대상이라 명시적으로 고정)

`noConnectionReuse: false`, `noVUConnectionReuse: false` (둘 다 k6 기본값이지만 의도를 남긴다).
원격 HTTPS에서 커넥션 재사용을 끄면 요청마다 TCP 3-way + TLS 핸드셰이크를 새로 하게 되어,
측정하려는 서버 병목 대신 노트북 CPU와 traefik의 핸드셰이크 비용을 측정하게 된다.
실제 브라우저도 keep-alive를 쓴다.

`insecureSkipTLSVerify: false` — 실 Let's Encrypt 인증서다. 검증을 끄면 엉뚱한 호스트에
붙어도 조용히 성공한다. `dns: {ttl:'5m'}` — 매 요청 DNS 조회를 피하되 IP 변경은 결국 따라간다.
`discardResponseBodies: false` — queue status/quantity, order id/amount, payment status를
전부 파싱해 다음 단계를 결정하므로 본문을 버릴 수 없다(본문은 전부 작은 JSON이라 비용은 무시 가능).

---

## 6. 목 끄기 (필수)

```bash
# semi 노드의 SSM 셸에서
cd ~/loadtest-repo
./loadtest/scripts/pg-mock-off.sh
```

역순으로 되돌린다: ① payment에서 `PG_BASE_URL` 제거 + rollout → ② WireMock
Deployment/Service/ConfigMap 삭제 → ③ ArgoCD auto-sync를 저장된 원본값으로 복원 + hard refresh.
앞 단계가 실패해도 뒤 단계는 계속 시도하고, 하나라도 실패하면 `exit 1` 로 시끄럽게 끝난다.

### auto-sync가 정말 돌아왔는지 확인

```bash
kubectl -n argocd get application openat -o jsonpath='{.spec.syncPolicy.automated}{"\n"}'
# 기대값: {"prune":true,"selfHeal":true}

kubectl -n argocd get application openat \
  -o jsonpath='{.status.sync.status}/{.status.health.status}{"\n"}'
# 기대값: Synced/Healthy

# payment에 PG_BASE_URL이 남아 있지 않은지
kubectl -n openat get deployment payment \
  -o jsonpath='{.spec.template.spec.containers[0].env[*].name}{"\n"}'
# 목록에 PG_BASE_URL 이 없어야 한다

# WireMock 잔재가 없는지
kubectl -n openat get all,configmap -l openat.dev/loadtest=true
# "No resources found" 여야 한다
```

`Synced/Healthy` 로 돌아오지 않으면 auto-sync를 끈 동안 `deploy/state` 에 배포가 쌓였을 수
있다. `kubectl -n argocd annotate application openat argocd.argoproj.io/refresh=hard --overwrite`
후 다시 본다.

---

## 7. 전체 명령 시퀀스 (한눈에)

```bash
# ============ 노트북: semi 노드에 SSM으로 접속 ============
export AWS_REGION=ap-northeast-2
SEMI_ID=$(aws ec2 describe-instances \
  --filters 'Name=tag:Name,Values=letsGpt-openAt-semi' 'Name=instance-state-name,Values=running' \
  --query 'Reservations[].Instances[].InstanceId' --output text)
aws ssm start-session --target "$SEMI_ID"

# ============ semi 노드(SSM 셸): PG 목 켜기 ============
sudo su - ubuntu
cd ~/loadtest-repo && git pull
./loadtest/scripts/pg-mock-on.sh

# ============ 노트북: 시딩 + smoke ============
cd ~/projects/beadv6_6_Let-sGPT_BE
PROFILE=ramp USER_COUNT=60 node loadtest/k6/seed.js     # 계정 + 판매자 + 상품 + 드롭
k6 run loadtest/k6/drop-flow.js                         # smoke (DROP_ID는 target.json에서)

# ============ semi 노드(SSM 셸): 목이 맞았는지 확인 — 0이면 즉시 중단 ============
WM=$(kubectl -n openat get svc wiremock-toss -o jsonpath='{.spec.clusterIP}')
N=$(curl -s "http://$WM:8080/__admin/requests?limit=1" | grep -o '"total" : [0-9]*' | grep -o '[0-9]\+')
[ "${N:-0}" -gt 0 ] && echo "OK: $N건" || echo "중단: 0건 — 실 토스일 수 있다"

# ============ 노트북: ramp ============
k6 run -e PROFILE=ramp loadtest/k6/drop-flow.js

# ============ semi 노드(SSM 셸): 반드시 ============
cd ~/loadtest-repo
./loadtest/scripts/pg-mock-off.sh
kubectl -n argocd get application openat -o jsonpath='{.spec.syncPolicy.automated}{"\n"}'
```

---

## 8. Grafana에서 볼 것

`https://grafana.openat.duckdns.org`. 아래 title/uid는 실제 배포된 대시보드에서 확인한 값이다.

### 8-1. 병목의 순서를 드러내는 패널 (가장 중요)

**대시보드: `인프라 — DB·파드 리소스` (uid `openat-infra`)** — 행 `커넥션풀 (HikariCP)`

| 패널 | 쿼리 | 왜 결정적인가 |
|---|---|---|
| `Active vs Max (서비스별)` | `sum by (app)(hikaricp_connections_active)` / `..._max` | payment의 max는 **2**다. active가 2에 붙어 평평해지는 순간이 곧 포화 시작이다 |
| `Pending threads (서비스별)` | `sum by (app)(hikaricp_connections_pending)` | **여기가 진짜 신호.** pending이 0 위로 계속 떠 있으면 요청들이 커넥션을 기다리는 중이다. pending이 오르기 시작하는 VU 수 = 용량 |
| `Timeouts (5m rate)` | `sum by (app)(rate(hikaricp_connections_timeout_total[5m]))` | 0에서 떨어지는 순간 = `connectionTimeout` 5초 초과 = 이미 벽을 넘었다. **이 지점 이후의 수치는 버린다** |
| `톰캣 busy 스레드` | `sum by (app)(tomcat_threads_busy_threads{...})` | max 50. busy가 50에 붙으면 스레드가 먼저 마른 것 |

**핵심 가설**: 스레드 50 : 커넥션 2 = **25:1** 이므로, 톰캣 스레드가 마르기 훨씬 전에 Hikari가
먼저 막힌다. `hikaricp_connections_pending` 이 `tomcat_threads_busy_threads` 보다 **먼저**
움직이면 가설대로다. 순서가 반대로 나오면 커넥션이 PG 콜 구간에서 반환되고 있다는 뜻이라
(= confirm 트랜잭션이 아웃바운드 호출을 감싸지 않는다) 가설 자체를 고쳐야 한다.

> `톰캣 busy 스레드` 패널에는 리포 상 "수집 여부 불확실"이라는 설명이 달려 있다. 시리즈가
> 비어 있으면 Explore에서 `tomcat_threads_busy_threads{app="payment"}` 를 직접 확인할 것.

### 8-2. 결제 경로

**대시보드: `결제 — payment` (uid `openat-payment`)**

- `PG call latency avg by operation` — `payment_pg_call_seconds_*`. WireMock의 lognormal
  median(기본 300ms)을 따라가야 한다. **이 값이 안 움직이면 PG_BASE_URL 오버라이드가 안 먹은
  것이고, 실 토스를 때리고 있을 가능성이 높다** (§4 저널 확인과 교차검증할 것).
- `PG call rate by outcome` — `sum by (outcome)(rate(payment_pg_call_seconds_count[1m]))`
- `Confirm result (1m rate)` — `sum by (result,reason)(rate(payment_confirm_result_total[1m]))`.
  포화 시 reason이 어떻게 갈리는지가 핵심.
- `Confirm 성공률`, `TTL expired (5m)`, `Reconcile discrepancy (5m)`, `Outbox pending`
- `Error Rate 5xx (req/s)`, `Latency avg / max (s)`, `Heap Used %`, `Process CPU`, `GC Pause (1m rate)`

### 8-3. 전체 흐름 / 상류

**대시보드: `E2E — 주문·결제·정산 흐름` (uid `openat-e2e`)**

- `주문 생성 (order.create, 1m rate)` vs `결제 확인 (payment.confirm, 1m rate)` — 두 rate의
  간격이 벌어지면 order는 되는데 payment가 못 따라가는 것
- `Outbox 발행 (order / payment)` — 부하 중 발행이 밀리면 비동기 후속이 지연되고 있다
- `보상/불일치 (saga comp / reconcile discrepancy)` — `FAULT_RATE_5XX` 주입 시 여기가 움직여야 한다
- `Gateway request rate by routeId`, `Gateway latency avg by routeId` — 게이트웨이 자체가
  병목인지 분리
- `Recent traces (TraceQL)` — 느린 1건을 골라 어느 구간에서 시간을 먹는지 본다

**대시보드: `도메인 — queue` (uid `openat-domain-queue`)** — `queue_waiting_size`,
`queue_outstanding`, `queue_admission_count_total`. 큐가 유입을 흡수해 payment를 보호하고
있는지(뒤가 막혀도 앞에서 대기가 쌓이는지) 확인.

**노드 여유**: `openat-infra` 의 `파드별 메모리 (working set, 스택) vs 노드 총량`,
`Limit 대비 사용률 (파드별)`, `CPU 스로틀링 비율 (파드별)`, `eviction 여유 (노드별)`.
2 vCPU라 **CPU 스로틀링이 Hikari보다 먼저 터지면 그건 앱 한계가 아니라 노드 한계**다 —
결론을 쓸 때 반드시 구분한다. WireMock 파드도 cAdvisor로 잡히므로 여기서 목 자체가
OOMKill되지 않았는지 확인할 수 있다(목이 죽으면 그 뒤 수치는 전부 무효).

---

## 9. 이 셋업으로 알 수 있는 것 / 없는 것

**정직하게 말하면 이건 용량 측정이 아니라 병목의 순서를 찾는 장치다.**

### 알 수 있는 것

- **무엇이 먼저 막히는가.** Hikari pool(2) → 톰캣 스레드(50) → CPU → 메모리 중 어느 것이
  먼저 벽이 되는지. 이건 노드 크기가 아니라 **구성값의 비율**이 결정하므로, 여기서 얻은
  순서는 더 큰 노드로 옮겨도 대체로 유지된다.
- **깨지는 방식.** 포화 시 429(rate limit)로 막히는지, 419(admission ticket TTL)로 새는지,
  Hikari 타임아웃 5xx로 터지는지, 큐가 흡수해 주는지.
- **회귀 감지.** 같은 프로파일을 반복해 이전 대비 나빠졌는지.
- **결함 주입 시 보상 경로가 실제로 도는가** (`FAULT_RATE_5XX` / `FAULT_RATE_4XX`).

### 알 수 없는 것

- **절대 처리량 숫자.** 앱 노드는 2 vCPU에 JVM 6개가 같이 산다. payment가 얻는 CPU는 다른
  서비스가 그 순간 무엇을 하느냐에 따라 매 실행 달라진다. "초당 N건"을 외부에 인용하지 말 것.
- **PG 자체의 실제 지연 특성.** WireMock의 lognormal은 우리가 정한 숫자다. 실 토스의 꼬리
  지연(p99), 커넥션 재사용 실패, TLS 재협상, 레이트리밋은 재현하지 않는다.
- **수평 확장 후의 거동.** replicas=1 고정이라 다중 인스턴스의 커넥션 총합, 락 경합,
  아웃박스 중복 처리는 이 셋업으로 전혀 관찰되지 않는다.
- **부하 생성기 한계와 앱 한계의 구분.** 노트북 1대 + 가정용 업링크 + 공용 인터넷이 경로에
  있다. VU가 늘수록 클라이언트 측 큐잉이 섞인다. k6 요약의 `http_req_blocked` /
  `http_req_connecting` 이 커지면 그건 서버가 아니라 우리 쪽 병목이다.
- **DB 자체의 한계.** postgres도 같은 노드에 있고 pool=2가 앞에서 막아주기 때문에 DB는
  사실상 압력을 받지 않는다. `openat-infra` 의 PostgreSQL 패널이 평온한 건 당연한 결과지
  "DB가 여유롭다"는 근거가 아니다.

결론은 **"VU N에서 hikaricp pending 상승 시작 / VU M에서 timeout 발생 시작"** 처럼
*이벤트가 일어난 지점*으로 쓰고, "우리 시스템은 N TPS를 처리한다"로 쓰지 않는다.

---

## 10. Env 노브

| var | default | meaning |
|---|---|---|
| `BASE_URL` | `https://openat.duckdns.org` | 진입점 |
| `DROP_ID` | (target.json) | 없으면 `target.json` 의 `dropId` 를 쓴다. 주면 그쪽이 이긴다 |
| `TARGET_FILE` | `./target.json` | `loadtest/k6/` 기준 상대경로 |
| `PROFILE` | `smoke` | `smoke` / `ramp` / `hold` / `stress` |
| `HOLD_VUS` | `20` | `hold` 프로파일의 VU 수 |
| `STAGES` | (PROFILE에서) | `<dur>:<target>,...` — 주면 PROFILE 무시 |
| `VUS` + `DURATION` | `10` + — | `DURATION`을 주면 `constant-vus` 로 전환 |
| `ALLOWED_HOSTS` | `openat.duckdns.org,localhost,127.0.0.1` | setup 호스트 허용목록 |
| `SKIP_STOCK_CHECK` | — | 재고 사전조건 검사 우회(권장하지 않음) |
| `QTY` | `1` | 큐 진입 시 요청 수량 |
| `MAX_WAIT_MS` / `MAX_POLLS` | `120000` / `120` | 폴링 상한 — 멈춘 VU가 영원히 돌지 않게 |
| `POLL_CEIL_MS` | `5000` | 서버 권고 `pollIntervalMs` 상한. 큐 heartbeat TTL 10s 아래 유지 |
| `DECISION_CHOICE` | `PARTIAL` | `DECISION_REQUIRED` 응답 |
| `FAULT_RATE_4XX` / `FAULT_RATE_5XX` | `0` / `0` | 결함 주입 비율 |
| `USERS_FILE` | `./users.json` | `loadtest/k6/` 기준 상대경로 |

시더(`seed.js`):

| var | default | meaning |
|---|---|---|
| `BASE_URL` | `https://openat.duckdns.org` | 진입점 |
| `USER_COUNT` | `50` | 계정 수. peak VU 이상으로 |
| `SEED_CONCURRENCY` | `4` | 시딩 병렬도. 이 자체가 member에 대한 부하다 |
| `PROFILE` / `STAGES` | `smoke` | 드롭 사이징 기준 |
| `SEED_DROP` | `1` | `0` 이면 계정만 만들고 드롭은 건너뛴다 |
| `FORCE_NEW_DROP` | — | `1` 이면 재사용 가능한 드롭이 있어도 새로 만든다 |
| `DROP_TOTAL_QUANTITY` | (프로파일에서 계산) | 총 수량 직접 지정 |
| `DROP_PRICE` | `10000` | WireMock 스텁의 고정 `totalAmount` 와 일치시킨 값 |
| `DROP_LIMIT_PER_USER` | — (무제한) | 켜면 admit 총량이 `계정수 × 값` 으로 잘린다 |
| `DROP_OPEN_DELAY_S` | `60` | `openAt` 까지의 여유(시계 오차 보정 후) |
| `DROP_CLOSE_AFTER_S` | — (무기한) | 켜면 테스트 도중 드롭이 닫힐 수 있다 |
| `EST_ITER_SEC` | `4` | 사이징용 iteration 소요 가정(초) |
| `QTY` | `1` | 사이징용 iteration당 수량 |
| `USER_PREFIX` / `USER_PASSWORD` | `lt` / — | 계정·판매자 식별자 접두사 |
| `USERS_FILE` / `TARGET_FILE` | `./users.json` / `./target.json` | 산출물 경로 |

스크립트(`pg-mock-*.sh`): `KUBECONFIG`(`/etc/rancher/k3s/k3s.yaml`), `LT_PG_DELAY_MEDIAN_MS`(300),
`LT_NAMESPACE`(openat), `LT_ARGOCD_NAMESPACE`(argocd), `LT_ARGOCD_APP`(openat),
`LT_MAPPINGS_DIR`, `LT_MANIFEST`, `LT_STATE_FILE`.

---

## 11. 결함 시나리오

WireMock은 **`paymentKey` 접두사**로 결함을 고른다. 백엔드가 PG에 보내는 헤더는
`Authorization` + `Idempotency-Key` 뿐이라 커스텀 헤더를 중계할 수 없고, `paymentKey`는
k6가 지어내 그대로 전달되므로 이것이 k6가 제어할 수 있는 유일한 셀렉터다.

| prefix | PG 응답 | 백엔드 경로 |
|---|---|---|
| `LT_` | 200 | 승인 |
| `LTFAIL4XX_` | 400 | `TossConfirmResult.rejected` — 비즈니스 실패, 재시도 안 함 |
| `LTFAIL5XX_` | 500 (median 1200ms) | `IllegalStateException` — 모호결과 보상 경로 |
| `LTTIMEOUT_` | 8초 후 200 | 5초 `RestClient` read timeout 초과 |

```bash
k6 run -e DROP_ID=<uuid> -e PROFILE=ramp -e FAULT_RATE_5XX=0.1 loadtest/k6/drop-flow.js
```

`X-Loadtest-Fault: reject|error` 헤더 셀렉터도 있지만 WireMock을 직접 curl할 때만 쓸 수 있다.

---

## 12. 커스텀 k6 메트릭

Trend: `queue_wait_ms`(entry→READY), `order_create_ms`, `payment_confirm_ms`,
`flow_duration_ms`, `queue_poll_count`.

Counter — 모든 iteration은 정확히 하나에 들어간다:
`outcome_success`, `outcome_sold_out`, `outcome_admission_419`, `outcome_quantity_400`,
`outcome_payment_rejected`, `outcome_payment_pending`, `outcome_poll_timeout`,
`outcome_gave_up`, `outcome_rate_limited_429`, `outcome_login_failed`,
`outcome_queue_error`, `outcome_order_error`, `outcome_payment_error`.

`outcome_quantity_400` 에는 `count==0` 임계값이 걸려 있다. admission ticket의 *값*이 곧
허가된 수량이므로 스크립트는 항상 `status.quantity`(서버가 허가한 값)로 주문하지, 원래
요청한 `QTY` 로 주문하지 않는다. 여기서 400이 나면 실제 계약 위반이다.

요약 JSON은 `loadtest-summary.json` 으로 떨어진다(`.gitignore` 됨).

---

## 13. 코드에서 찾은 함정

- 게이트웨이가 `POST /api/v1/payments/confirm` 을 **사용자별** `replenishRate 2 / burst 5`
  로 제한한다(`GW_CONFIRM_RL_REPLENISH` / `GW_CONFIRM_RL_BURST`). VU-iteration 당 confirm
  1건이면 안전하다. iteration rate를 올릴 거면 이 값을 같이 올리지 않는 한
  `outcome_rate_limited_429` 가 결과를 지배한다.
- 큐는 `queue.waiting.heartbeat-ttl-ms`(10s) 동안 폴링이 없으면 대기 슬롯을 회수한다.
  `POLL_CEIL_MS` 기본 5000은 그 아래를 유지하기 위한 값이다.
- `PARTIAL` 결정 후에는 허가 수량이 요청보다 작다. 원래 `QTY` 로 주문하면 티켓을 태우고 400이 난다.
- payment의 actuator는 `health,prometheus` 만 노출한다. `/actuator/env` 로 `pg.base-url`
  현재값을 확인할 수 없다 — WireMock 저널이 유일한 확인 수단이다(§4).
- 상품·드롭 생성은 **scoped 토큰 전용**이고 TTL이 **120초**다. 회원 access 토큰으로는
  게이트웨이에서 거부된다. 네트워크가 느려 시딩이 2분을 넘기면 그 지점에서 401이 난다
  (시더가 재발급을 시도하고, 그래도 실패하면 만료인지 권한인지 구분해 알려준다).
- 상품/드롭 생성 응답은 **201 + Location 헤더**다. 바디에 id가 없다.
- `SellerInfoResponse` 의 필드명은 `sellerInfoId` 가 아니라 **`id`** 다.
  `scripts/smoke-drop-order.sh` 의 `.sellerInfoId // .id` 는 사실상 `.id` 로만 동작한다.
- `businessNumber` 는 `@Size(max = 30)` 이고 계정당 결정적 값을 쓴다. 같은 `USER_PREFIX` 로
  다른 계정에 판매자를 또 만들려 하면 중복으로 막힐 수 있다 — `USER_PREFIX` 를 바꿀 것.
- SSM 세션의 기본 계정은 `ssm-user` 이고 `KUBECONFIG` export가 없다. `sudo su - ubuntu`
  로 전환하거나 직접 export한다(kubeconfig는 0644라 읽기 자체는 가능하다).
- 러너 체크아웃(`/home/deployer/actions-runner/_work/...`)을 작업 디렉터리로 쓰지 말 것.
  `deploy/state` 브랜치에 머물러 있고, 다음 CD 실행이 그 트리를 덮어쓴다.
- auto-sync를 꺼 둔 동안 CD(`deploy.yml`)가 돌면 `deploy/state` 에는 커밋이 쌓이지만
  클러스터에는 반영되지 않고, CD의 "Wait for ArgoCD convergence" 스텝은 10분 후 **실패**한다.
  부하테스트 창을 배포 창과 겹치지 말 것.
- `readOnlyRootFilesystem: true` 인 WireMock 파드가 `CrashLoopBackOff` + "Read-only file
  system" 으로 죽으면 `loadtest/k8s/wiremock.yaml` 의 emptyDir 마운트(`/tmp`,
  `/home/wiremock/__files`)를 먼저 의심할 것.

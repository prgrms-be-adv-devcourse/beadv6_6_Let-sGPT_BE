# 대기열 시연용 브라우저 자동화 (Playwright + TS)

`queue/demo/test/`는 대기열 시스템 시각화 시연을 위해, 격리된 브라우저 10개로 "정해진 재고에
여러 명이 동시에 몰리는" 상황을 재현하는 자동화 프로젝트다. `queue/demo/`가 통째로
gitignore 대상이라(산출물 디렉터리) 이 프로젝트도 저장소에는 커밋되지 않는다.

## 사전 준비

1. `queue/demo/setup-hot-drop.sh`를 **직접 수동으로** 실행해 데모 드롭을 만든다(이 프로젝트는
   자동으로 실행하지 않는다 — 여러 번 반복 시연하며 직접 관리하기 위함).
   ```bash
   cd ../..   # queue/demo 로 이동
   ./setup-hot-drop.sh 5 2
   ```
2. 출력된 `dropId`를 `src/config.ts`의 `DROP_ID` 값에 직접 넣거나, 실행 시
   `DEMO_DROP_ID` 환경변수로 넘긴다.
3. FE 서버(`../../beadv6_6_Let-sGPT_FE`, 기본 `http://localhost:5173`)와 게이트웨이/
   member/product/order/queue 모듈이 떠 있어야 한다.

## 설치 & 실행

```bash
cd queue/demo/test
npm install
npx playwright install chromium   # 최초 1회, 브라우저 바이너리 설치
npm run demo
```

`DEMO_BASE_URL` / `DEMO_DROP_ID` 환경변수로 값을 덮어쓸 수도 있다:

```bash
DEMO_BASE_URL=http://localhost:5173 DEMO_DROP_ID=<uuid> npm run demo
```

## 설정값 — 한 곳만 고치면 전부 반영

`src/config.ts`:

| 값 | 의미 |
|---|---|
| `BASE_URL` | FE 서버 주소 (기본 `http://localhost:5173`, `DEMO_BASE_URL` 환경변수로 덮어쓰기 가능) |
| `DROP_ID` | 시연할 드롭 ID — `setup-hot-drop.sh` 출력값을 직접 넣거나 `DEMO_DROP_ID` 환경변수 사용 |
| `USER_COUNT` | 동시에 띄울 가상 유저(브라우저) 수 (기본 10) |

## 시나리오 진행 순서

1. 격리된 브라우저 컨텍스트 10개 생성 — 컨텍스트마다 쿠키/localStorage/sessionStorage가
   완전히 분리되어 "브라우저 1개 = 유저 1명" 전제를 만족한다. Chromium 프로세스는 하나만
   띄우지만(리소스 절약), `headless:false`에서는 컨텍스트마다 별도의 OS 창으로 뜨므로
   화면에는 실제로 브라우저 10개가 보인다.
2. 전원 `BASE_URL` 접속 → 로그인(`test1@test.com`~`test10@test.com`, 비밀번호
   `12341234` — member 모듈 `init-data.sql` 시드 계정, 브라우저 순서대로 test1~10 할당) →
   `/drops/{DROP_ID}` 라우팅. **전원 페이지 로딩(드롭 OPEN 상태) 완료까지 대기**한 뒤 다음
   단계로 진행한다.
3. 각 브라우저가 독립적으로 수량 "+"(`aria-label="수량 증가"`) 버튼을 0~4회 랜덤 클릭.
4. **브라우저 순서대로(1번→10번, 완전 순차)** "주문하기" 클릭 → 결제(체크아웃) 화면으로
   바로 넘어갔는지 대기열 모달이 떴는지를 그 유저에서 확인한 뒤에야 다음 유저가 클릭한다
   (클릭만 빨리 연타하면 응답이 뒤섞여 도착해 "줄을 선 모습"으로 안 보이므로, 한 명씩
   결과까지 보고 넘어가도록 했다).

5. 전원의 주문 처리가 끝난 뒤, 창을 10번→1번 역순으로 한 번씩 앞으로 가져와 최종적으로
   1번 창이 맨 위, 나머지는 실행 순서대로 그 아래 쌓이도록 정렬한다(클릭 처리 도중
   실시간으로 포커스를 옮기는 방식은 Windows의 포커스 도용 방지 정책 때문에 신뢰할 수
   없어서, 모든 비동기 작업이 끝나 경쟁이 없어진 뒤 한 번에 정리하는 방식을 썼다).

시나리오가 끝나도 브라우저 창은 시연을 위해 그대로 열어둔다. 종료하려면 터미널에서
`Ctrl+C`.

## 브라우저 탭 vs 컨텍스트 — 왜 컨텍스트를 쓰는지

같은 브라우저 창(같은 `BrowserContext`)의 새 탭들은 쿠키/localStorage를 공유한다. FE의
인증 상태(`authStore.ts`)는 `zustand/persist`로 토큰을 localStorage에 저장하므로, 탭으로
띄우면 나중에 로그인한 유저의 토큰이 먼저 로그인한 탭의 localStorage까지 덮어써 세션이
섞인다. 그래서 유저마다 반드시 별도 `BrowserContext`(`browser.newContext()`)를 써야
"브라우저 1개 = 유저 1명"이 보장된다 — 이 프로젝트는 처음부터 그렇게 되어 있다.

## 참고

- 이 프로젝트는 `@playwright/test`(테스트 러너)가 아니라 `playwright` 라이브러리를 직접
  써서 하나의 시나리오 스크립트로 작성했다 — 대기열 특유의 "여러 브라우저를 동시에 띄우고
  순서를 조율하는" 시연 흐름이 assert 기반 테스트보다는 오케스트레이션 스크립트에 더
  가깝기 때문.
- FE 라우트/셀렉터(로그인 폼, `/drops/$id` 페이지의 수량·주문 버튼, 대기열 모달)는
  `../../../beadv6_6_Let-sGPT_FE` 코드를 참고해 작성했다. FE가 해당 UI 텍스트나
  `aria-label`을 바꾸면 이 스크립트도 함께 갱신해야 한다.

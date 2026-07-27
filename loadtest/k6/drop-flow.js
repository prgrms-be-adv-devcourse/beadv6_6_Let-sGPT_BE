/*
 * drop-flow.js — login -> queue entry -> poll -> order -> PG payment confirm.
 *
 * 대상: EC2의 live k3s 배포(https://openat.duckdns.org). 로컬 compose가 아니다.
 * 요청은 공용 인터넷 -> TLS -> traefik -> apigateway 를 지난다.
 *
 * The Toss PG is replaced by WireMock (loadtest/k8s/wiremock.yaml), so the browser/Toss-SDK
 * step is unnecessary: POST /api/v1/payments/confirm is the single entry point for PG payment
 * and forwards the client-supplied paymentKey to the PG as an opaque string.
 *
 * Run:
 *   k6 run -e DROP_ID=<uuid> loadtest/k6/drop-flow.js                 # PROFILE=smoke (기본)
 *   k6 run -e DROP_ID=<uuid> -e PROFILE=ramp loadtest/k6/drop-flow.js
 *   k6 run -e PROFILE=stress loadtest/k6/drop-flow.js                 # 열린 모델(도착률 고정)
 *   k6 run --out csv=results/raw.csv -e PROFILE=ramp loadtest/k6/drop-flow.js   # 요청 단위 원본
 *
 * Everything is __ENV-driven; see the CFG block below for names and defaults.
 */
import http from 'k6/http';
import { group, sleep, check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Counter, Gauge } from 'k6/metrics';
import exec from 'k6/execution';

// ---------------------------------------------------------------------------
// 부하 프로파일 (-e PROFILE=<name>)
// ---------------------------------------------------------------------------
// 숫자 근거 — 이 배포의 하드 제약에서 역산했다.
//   * payment: Hikari maximumPoolSize=2, connectionTimeout=5000ms, tomcat threads=50,
//     replicas=1, memory 700Mi (k8s/base/24-payment.yaml)
//   * WireMock confirm 스텁: lognormal median 300ms
//   * 앱 노드: 2 vCPU에 JVM 6개 상주
//   * 게이트웨이: /payments/confirm 사용자별 replenish 2/s, burst 5
//
// confirm 1건이 DB 커넥션을 잡은 채 PG 왕복 300ms를 기다린다고 보면 payment의 이론
// 처리량 상한은 pool(2) / 0.3s ≈ 6~7 confirm/s. 한 iteration은 login(캐시됨) +
// queue entry + poll 몇 번 + order + confirm + think 1s ≈ 3~5초이므로 VU 하나가 만드는
// confirm은 대략 0.2~0.3/s. 즉 6~7 confirm/s에 닿는 지점이 VU 25~35 부근 = "무릎"이다.
// 램프는 그 근처를 촘촘히 지나가야 한다.
//
// 이전 기본값(200 VU)은 무릎을 20배 넘겨 뛰어버리는 값이라 측정이 아니라 Hikari 5s
// 타임아웃 벽(connection is not available)만 찍고 끝난다.

// ---------------------------------------------------------------------------
// 열린 모델(stress) — 도착률을 고정해 용량 천장을 잰다
// ---------------------------------------------------------------------------
// smoke/ramp/hold 는 전부 ramping-vus(닫힌 모델)다. VU는 응답을 받아야 다음 요청을 보내므로
// 지연이 늘면 제시 부하가 저절로 줄어든다. 그래서 "처리량이 늘었다"가 시스템이 더 받아낸
// 것인지, 부하가 알아서 줄어든 것인지 분리되지 않는다. 용량 천장을 재려면 서버 상태와
// 무관하게 도착률이 고정돼야 한다 = ramping-arrival-rate(열린 모델).
//
// 계단 값은 실측으로 확정된 천장 앞뒤를 훑도록 잡았다. 지금까지 라운드에서 병목은
// 게이트웨이 유량제한(사용자별 2/s) -> 결제 커넥션풀(pool 2) -> 2코어 노드 CPU 순서로
// 드러났고, 이 클러스터의 실질 천장은 **결제 확인 초당 약 8건**이다. 그래서
// 2(천장의 1/4) -> 4(1/2) -> 8(천장) -> 12(1.5배) -> 16(2배) 로 훑는다. 천장 아래에서
// 도착률과 처리량이 같이 오르다가, 8 부근에서 처리량이 평평해지고 지연이 꺾이는 지점이
// 곧 용량이다.
//
// target 하나마다 [전환 30s] + [평탄 3m] 두 stage를 넣는다. k6는 stage 안에서 도착률을
// 직전 값에서 target까지 **선형 보간**하므로, 같은 target을 두 번 쓰지 않으면 평탄 구간이
// 아예 생기지 않는다. "도착률 8/s 구간의 p95" 처럼 구간을 인용하려면 그 구간의 도착률이
// 실제로 고정돼 있어야 한다(닫힌 모델의 2분 계단과 같은 이유다).
//
// 전부 __ENV로 덮을 수 있고, 이름을 RATE_* 로 따로 뒀다 — 기존 STAGES/VUS/DURATION 규약은
// VU 단위라서 같은 이름을 나눠 쓰면 "이 숫자가 VU인가 도착률인가"가 섞인다.
const RATE = {
  // 초당 iteration 수 계단. 단계 수도 여기서 바뀐다.
  targets: (__ENV.RATE_TARGETS || '2,4,8,12,16')
    .split(',').map((t) => parseFloat(t.trim())).filter((t) => t > 0),
  holdDur: __ENV.RATE_STAGE_DUR || '3m',   // 평탄 구간 — 실제로 인용할 구간
  rampDur: __ENV.RATE_RAMP_DUR || '30s',   // 계단 사이 전환 구간
  tailDur: __ENV.RATE_TAIL_DUR || '1m',    // 마지막 0으로 내리는 구간
  startRate: parseFloat(__ENV.RATE_START || '0'),
  // VU 사이징용 iteration 소요 가정(초). 포화 시 iteration이 8초를 넘긴 실측이 있어
  // 4초(seed.js의 EST_ITER_SEC)가 아니라 8초를 쓴다 — 적게 잡으면 VU가 모자라 도착이 버려진다.
  iterSec: parseFloat(__ENV.RATE_ITER_SEC || '8'),
};

// 계단 스펙 문자열을 만든다. 표기는 닫힌 모델과 같은 '<dur>:<target>' 이지만 target의
// 단위가 VU가 아니라 초당 iteration 이다. RATE_STAGES를 주면 통째로 대체된다.
function buildRateSpec() {
  if (__ENV.RATE_STAGES) return __ENV.RATE_STAGES;
  const parts = [];
  for (const t of RATE.targets) {
    parts.push(`${RATE.rampDur}:${t}`);
    parts.push(`${RATE.holdDur}:${t}`);
  }
  parts.push(`${RATE.tailDur}:0`);
  return parts.join(',');
}

const PROFILES = {
  // 흐름이 끝까지 도는지만 증명. 부하 아님. 항상 이걸 먼저 돌린다.
  // 3 VU면 confirm ~1/s — pool 2로도 여유롭고 사용자별 rate limit(2/s)에도 안 걸린다.
  smoke: '20s:3,40s:3,10s:0',

  // 무릎 탐색. 각 계단을 2분 유지해 p95가 안정된 표본을 만든다(30s 계단은 램프 과도구간만
  // 보게 된다). 5 -> 40까지 올리며 이론 무릎(25~35) 앞뒤를 모두 통과. 총 10분 + ramp-down.
  ramp: '1m:5,2m:10,2m:20,2m:30,2m:40,1m:0',

  // 무릎을 찾은 뒤 그 지점에서 오래 눌러 큐/아웃박스/정산이 밀리는지 본다. -e HOLD_VUS 로 조절.
  hold: `1m:${__ENV.HOLD_VUS || '20'},10m:${__ENV.HOLD_VUS || '20'},1m:0`,

  // 열린 모델. 여기 숫자만 단위가 VU가 아니라 **초당 iteration(도착률)** 이다.
  // 예전 stress('30s:20,1m:60,1m:100,1m:0')는 닫힌 모델이라 100 VU가 만드는 실제 부하가
  // 서버 지연에 따라 저절로 줄어들어, 벽을 봐도 그게 몇 건/초에서 생긴 벽인지 말할 수 없었다.
  stress: buildRateSpec(),
};

const PROFILE = __ENV.PROFILE || 'smoke';   // 기본은 안전한 쪽
if (!(PROFILE in PROFILES) && !__ENV.STAGES && !__ENV.DURATION) {
  throw new Error(`unknown PROFILE=${PROFILE}; one of: ${Object.keys(PROFILES).join(', ')}`);
}

// 열린 모델로 도는가. STAGES/DURATION 을 명시하면 그건 VU 단위 규약이므로 닫힌 모델이
// 이긴다 — 즉 smoke/ramp/hold 와 기존 -e STAGES/-e VUS 경로는 이 플래그가 절대 안 켜진다.
const OPEN_MODEL = PROFILE === 'stress' && !__ENV.STAGES && !__ENV.DURATION;

// ---------------------------------------------------------------------------
// 시딩 산출물 — seed.js 가 만든 대상 정보
// ---------------------------------------------------------------------------
// seed.js 가 판매자·상품·드롭까지 만들고 target.json 에 dropId를 적어 둔다. 그래서 보통은
// UUID를 손으로 붙여넣을 필요가 없다. -e DROP_ID=... 를 주면 언제나 그쪽이 이긴다.
// open()은 init 컨텍스트에서만 쓸 수 있고 파일이 없으면 throw하므로 감싼다.
const TARGET_FILE = __ENV.TARGET_FILE || './target.json';
function readTarget() {
  try {
    return JSON.parse(open(TARGET_FILE));
  } catch (e) {
    return null;
  }
}
const TARGET = readTarget();

// ---------------------------------------------------------------------------
// config
// ---------------------------------------------------------------------------
const CFG = {
  // live EC2 배포. 로컬로 돌리려면 -e BASE_URL=http://localhost:8000.
  baseUrl: (__ENV.BASE_URL || 'https://openat.duckdns.org').replace(/\/$/, ''),
  dropId: __ENV.DROP_ID || (TARGET && TARGET.dropId) || '',
  quantity: parseInt(__ENV.QTY || '1', 10),
  usersFile: __ENV.USERS_FILE || './users.json',

  // 엉뚱한 호스트(다른 팀 환경, 오타난 도메인)를 때리는 걸 막는 허용목록.
  // 새 환경을 겨냥하려면 -e ALLOWED_HOSTS=... 로 명시적으로 넓혀야 한다.
  allowedHosts: (__ENV.ALLOWED_HOSTS || 'openat.duckdns.org,localhost,127.0.0.1')
    .split(',').map((h) => h.trim()).filter(Boolean),

  // load shape
  vus: parseInt(__ENV.VUS || '10', 10),
  duration: __ENV.DURATION || '',            // set this to use a constant-VU run
  stages: __ENV.STAGES || PROFILES[PROFILE] || PROFILES.smoke,

  // poll cap — a stuck VU must never spin forever
  maxWaitMs: parseInt(__ENV.MAX_WAIT_MS || '120000', 10),
  maxPolls: parseInt(__ENV.MAX_POLLS || '120', 10),
  pollFloorMs: parseInt(__ENV.POLL_FLOOR_MS || '200', 10),  // never poll faster than this
  pollCeilMs: parseInt(__ENV.POLL_CEIL_MS || '5000', 10),   // queue heartbeat TTL is 10s — stay under it

  // DECISION_REQUIRED answer: PARTIAL | WAIT | GIVE_UP
  decisionChoice: __ENV.DECISION_CHOICE || 'PARTIAL',

  // opt-in PG fault injection (fraction of iterations, 0..1). Driven purely by the
  // paymentKey prefix, which WireMock matches on (see wiremock/mappings/toss-confirm-faults.json).
  faultRate4xx: parseFloat(__ENV.FAULT_RATE_4XX || '0'),
  faultRate5xx: parseFloat(__ENV.FAULT_RATE_5XX || '0'),

  thinkTimeMs: parseInt(__ENV.THINK_TIME_MS || '1000', 10),
};

if (!CFG.dropId) {
  throw new Error(
    `대상 드롭을 찾을 수 없다. 둘 중 하나:\n` +
    `  1) node loadtest/k6/seed.js  (판매자·상품·드롭까지 만들고 ${TARGET_FILE} 를 남긴다)\n` +
    `  2) k6 run -e DROP_ID=<uuid> loadtest/k6/drop-flow.js`,
  );
}

// One user per VU, loaded once into shared memory (not once per VU).
const users = new SharedArray('loadtest users', () => {
  const parsed = JSON.parse(open(CFG.usersFile));
  const list = Array.isArray(parsed) ? parsed : parsed.users;
  if (!list || list.length === 0) {
    throw new Error(`${CFG.usersFile} has no users — run "node loadtest/k6/seed.js" first`);
  }
  return list;
});

// ---------------------------------------------------------------------------
// metrics — the things the default k6 summary cannot show
// ---------------------------------------------------------------------------
const queueWaitMs = new Trend('queue_wait_ms', true);        // entry -> READY
const orderCreateMs = new Trend('order_create_ms', true);
const paymentConfirmMs = new Trend('payment_confirm_ms', true);
const flowDurationMs = new Trend('flow_duration_ms', true);  // end to end
const queuePollCount = new Trend('queue_poll_count');

// 지연 트렌드 분리 — 게이트웨이가 짧게 끊어버린 응답을 백엔드 지연에 섞으면 안 된다.
// 유량제한 429는 요청이 payment 서비스에도 PG에도 닿지 않고 수십 ms에 되돌아온다. 이걸
// payment_confirm_ms 에 넣으면 중앙값이 PG 스텁 median 300ms 아래로 내려가는 허위 수치가
// 된다(지난 램프 라운드: confirm 8123건 중 429가 2667건, 중앙값 60ms).
const paymentConfirm429Ms = new Trend('payment_confirm_429_ms', true);
// 같은 이유로 주문 생성도 분리한다. 419(입장권 없음)/429(유량제한)는 게이트웨이 필터에서
// 끊기므로 order 서비스의 재고 차감 왕복이 아예 없다. 409(재고 소진)는 실제 백엔드 왕복이
// 일어난 응답이라 order_create_ms 에 그대로 남긴다.
const orderCreateRejectMs = new Trend('order_create_gateway_reject_ms', true);

// terminal outcomes — every iteration increments exactly one of these
const outSuccess = new Counter('outcome_success');
const outSoldOut = new Counter('outcome_sold_out');
const outAdmission419 = new Counter('outcome_admission_419');
const outQuantity400 = new Counter('outcome_quantity_400');
const outPaymentRejected = new Counter('outcome_payment_rejected');
const outPaymentPending = new Counter('outcome_payment_pending');
const outPollTimeout = new Counter('outcome_poll_timeout');
const outLoginFailed = new Counter('outcome_login_failed');
const outQueueError = new Counter('outcome_queue_error');
const outOrderError = new Counter('outcome_order_error');
const outPaymentError = new Counter('outcome_payment_error');
const outRateLimited = new Counter('outcome_rate_limited_429');
const outGaveUp = new Counter('outcome_gave_up');

// 재고 검산용 게이지. setup()이 시작 잔여를, teardown()이 종료 잔여를 여기에 싣는다.
// 게이지를 쓰는 이유는 handleSummary 로 값을 넘길 수 있는 유일한 경로이기 때문이다:
// teardown 은 지표를 읽을 수 없고(k6가 안 넘겨준다), handleSummary 는 HTTP 조회를 하는
// 자리가 아니다. 대신 setup/teardown 이 emit한 샘플은 요약 데이터에 그대로 들어온다.
const stockRemainingStart = new Gauge('stock_remaining_start');
const stockRemainingEnd = new Gauge('stock_remaining_end');

// ---------------------------------------------------------------------------
// options
// ---------------------------------------------------------------------------
function parseStages(spec) {
  return spec.split(',').map((part) => {
    const [duration, target] = part.split(':');
    return { duration: duration.trim(), target: parseInt(target, 10) };
  });
}

/** k6 표기('30s','3m','1h')를 초로. 열린 모델의 도착 수 추정에만 쓴다. */
function durationSeconds(spec) {
  const m = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(String(spec).trim());
  if (!m) throw new Error(`stage duration을 파싱할 수 없다: '${spec}'`);
  return parseFloat(m[1]) * { ms: 0.001, s: 1, m: 60, h: 3600 }[m[2]];
}

/**
 * 열린 모델에서 이 계단 스펙이 만들어 낼 총 iteration 수. 도착률을 시간으로 적분한 값이라
 * 사다리꼴 합이면 된다(k6가 stage 안에서 선형 보간하므로 정확히 사다리꼴이다).
 * 재고 사전조건 판정에 쓴다 — 열린 모델은 서버가 느려져도 도착이 안 줄어들기 때문에
 * "VU 수 × 2" 같은 닫힌 모델식 근사가 통하지 않는다.
 */
function estimateArrivals(spec, startRate) {
  let prev = startRate;
  let total = 0;
  for (const s of parseStages(spec)) {
    total += ((prev + s.target) / 2) * durationSeconds(s.duration);
    prev = s.target;
  }
  return Math.ceil(total);
}

// 열린 모델 VU 사이징 — Little의 법칙: 필요한 동시 VU = 도착률 × iteration 소요시간.
// iteration 하나가 login(캐시됨) + queue entry + poll 몇 번 + order + confirm + think 1s 를
// 돌고, 포화 구간에서 8초를 넘긴 실측이 있다(RATE_ITER_SEC 기본 8).
//   preAllocatedVUs = ceil(peak 16/s × 8s) = 128 — 최고 계단에서도 도착을 놓치지 않는 값.
//                     미리 만들어 두는 이유는 계단이 오를 때 VU를 새로 띄우느라 도착이
//                     밀리면 그 지연이 서버 지연으로 오해되기 때문이다.
//   maxVUs          = 그 2배 = 256 — 포화로 iteration이 16초까지 늘어나도 흡수한다.
// 이 벽에 닿으면 k6는 도착을 버리고 dropped_iterations 를 올린다. 그 값이 0이 아닌 라운드의
// 처리량은 "서버가 받아낸 양"이 아니라 "부하 생성기가 만들어낸 양"이라 용량 근거가 못 된다.
// 그래서 요약 출력에 항상 찍는다(textSummary 참조).
const peakArrivalRate = OPEN_MODEL
  ? parseStages(CFG.stages).reduce((max, s) => Math.max(max, s.target), RATE.startRate)
  : 0;
const RATE_VUS = {
  pre: parseInt(__ENV.RATE_PRE_VUS || String(Math.max(10, Math.ceil(peakArrivalRate * RATE.iterSec))), 10),
  max: parseInt(__ENV.RATE_MAX_VUS || String(Math.max(20, Math.ceil(peakArrivalRate * RATE.iterSec * 2))), 10),
};

function buildScenario() {
  // -e DURATION 은 예나 지금이나 constant-vus 로 간다(프로파일보다 우선).
  if (CFG.duration) {
    return { executor: 'constant-vus', vus: CFG.vus, duration: CFG.duration };
  }
  if (OPEN_MODEL) {
    return {
      executor: 'ramping-arrival-rate',
      startRate: RATE.startRate,
      timeUnit: '1s',                    // stage target의 단위 = 초당 iteration
      stages: parseStages(CFG.stages),
      preAllocatedVUs: RATE_VUS.pre,
      maxVUs: RATE_VUS.max,
      // 마지막 계단이 끝난 뒤 진행 중이던 iteration을 끝낼 여유. 여기서 끊으면 confirm이
      // 응답 전에 죽어 재고를 문 채로 라운드가 끝난다(= 유령 매진처럼 보인다).
      gracefulStop: '30s',
    };
  }
  return { executor: 'ramping-vus', startVUs: 0, stages: parseStages(CFG.stages), gracefulRampDown: '30s' };
}

const scenario = buildScenario();

export const options = {
  scenarios: { drop_flow: scenario },
  // Thresholds that actually fail a bad run rather than decorate the summary.
  thresholds: {
    // Hard failures: the harness itself is broken, or the service is erroring out.
    'outcome_login_failed': ['count==0'],
    'outcome_queue_error': ['count==0'],
    // 재고 소진(409 SOLD_OUT)이 outcome_sold_out 으로 빠졌으므로 여기 남는 건 진짜 오류뿐이다.
    // 이전에는 매진이 전부 여기로 떨어져(지난 라운드 4691건) 임계가 무의미했다.
    'outcome_order_error': ['count<10'],
    'outcome_payment_error': ['count<10'],
    // A run where nobody ever completes the flow is not a load test.
    'outcome_success': ['count>0'],
    // The two contract violations we specifically want to catch. 419 is expected in
    // small numbers under heavy contention (ticket TTL), so allow a trickle, not a flood.
    'outcome_admission_419': ['count<50'],
    'outcome_quantity_400': ['count==0'],
    // 지연 예산. 원격 대상이라 노트북->EC2 RTT + TLS가 모든 수치에 더해진다(수십 ms).
    // order는 2000 -> 3000으로 완화. payment는 Hikari 5s 타임아웃이 실질 상한이라
    // 5000을 넘으면 그건 지연이 아니라 커넥션 고갈이므로 임계값을 그대로 둔다.
    // 419/429는 order_create_gateway_reject_ms 로 빠졌으므로 order 서비스에 실제로 닿은
    // 응답(201/409 등)만 이 예산으로 평가한다.
    'order_create_ms': ['p(95)<3000'],
    // 유량제한 429 응답 시간이 payment_confirm_429_ms 로 빠졌으므로 실제 PG 왕복만 남는다.
    'payment_confirm_ms': ['p(95)<5000'],   // PG stub median 300ms + backend work
    // 아래 setResponseCallback 이 409/419/429를 성공으로 분류하므로, 이 비율에 남는 건
    // 5xx·연결 실패 같은 진짜 장애다. 이전에는 매진·유량제한이 전부 실패로 세어져
    // 지난 라운드 0.373 으로 필연 초과했다.
    'http_req_failed': ['rate<0.05'],
    // 매진(409)을 단정 실패로 잡지 않게 고쳤으므로(createOrder 참조) 이 비율은 다시
    // 계약 위반만 잡는다. 지난 라운드 0.737 은 매진 4691건이 단정 실패로 세어진 결과다.
    'checks': ['rate>0.95'],
    // 관측 전용(실패 임계 없음): outcome_sold_out, outcome_rate_limited_429.
    // 드롭 경합에서 매진과 유량제한은 정상 종단 결과라서 개수로 실패를 판정할 수 없다.
    // 재고가 언제 말랐는지·유량제한이 어디서 걸리는지는 카운터 값으로만 읽는다.
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],

  // -- 원격/TLS 대상에서의 커넥션 정책 (전부 의도적으로 명시) -------------------
  // noConnectionReuse=false(k6 기본): keep-alive 유지. 원격 HTTPS 대상에서 이걸 켜면
  // 요청마다 TCP 3-way + TLS 핸드셰이크를 새로 하게 되어, 측정하려는 서버 병목 대신
  // 노트북 CPU와 traefik의 핸드셰이크 비용을 측정하게 된다. 실제 브라우저도 재사용한다.
  noConnectionReuse: false,
  // noVUConnectionReuse=false(k6 기본): iteration 경계에서도 VU가 커넥션을 유지한다.
  // true면 iteration마다 재핸드셰이크 — 위와 같은 이유로 끄지 않는다.
  noVUConnectionReuse: false,
  // 실 Let's Encrypt 인증서다. 검증을 끄면 잘못된 호스트에 붙어도 조용히 성공한다.
  insecureSkipTLSVerify: false,
  // duckdns A 레코드는 안정적이다. 매 요청 DNS 조회를 피하되(측정 노이즈) 5분마다
  // 갱신해 재배포/IP 변경도 결국 따라간다.
  dns: { ttl: '5m', select: 'first', policy: 'preferIPv4' },
  // 응답 본문을 버릴 수 없다: queue status/quantity, order id/amount, payment status를
  // 전부 파싱해서 다음 단계를 결정한다. 본문이 전부 작은 JSON이라 비용도 무시 가능.
  // (본문 파싱이 필요 없는 스크립트라면 여기서 true가 맞다.)
  discardResponseBodies: false,
  // dropId / paymentKey live in URLs and bodies; every request below carries an
  // explicit `name` tag so k6 groups them instead of exploding metric cardinality.
};

// 업무상 정상인 상태코드를 http_req_failed 에서 실패로 세지 않게 한다.
// k6 기본 콜백은 200~399만 성공으로 보는데, 이 시나리오에서 409(재고 소진) /
// 429(게이트웨이 유량제한) / 419(입장권 만료)는 서비스 장애가 아니라 설계된 종단 결과다.
// 이 셋을 실패로 세면 http_req_failed 가 "서버가 얼마나 깨졌나" 대신 "재고가 얼마나 빨리
// 말랐나"를 재게 된다. 세 상태코드의 발생량은 outcome_* 카운터로 따로 관측한다.
// init 컨텍스트에서 한 번 설정하면 이후 모든 VU의 요청에 적용된다.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 409, 419, 429));

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------
const JSON_HEADERS = { 'Content-Type': 'application/json' };

/**
 * A check whose failure cannot be swallowed: it feeds the built-in `checks` metric
 * (thresholded above) AND returns the boolean, so every caller is forced to branch
 * into an explicit outcome counter rather than falling through.
 */
function assert(name, ok) {
  check(ok, { [name]: (v) => v === true });
  return ok;
}

function bearer(token) {
  return { ...JSON_HEADERS, Authorization: `Bearer ${token}` };
}

function clampPoll(ms) {
  const n = typeof ms === 'number' && ms > 0 ? ms : 2000;
  return Math.min(Math.max(n, CFG.pollFloorMs), CFG.pollCeilMs);
}

function safeJson(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

function uuidish() {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * Fabricated paymentKey. The backend forwards it to the PG verbatim, and the WireMock
 * stubs select faults purely by prefix — so this is how k6 drives the fault scenarios.
 */
function makePaymentKey() {
  const r = Math.random();
  if (r < CFG.faultRate5xx) return `LTFAIL5XX_${uuidish()}`;
  if (r < CFG.faultRate5xx + CFG.faultRate4xx) return `LTFAIL4XX_${uuidish()}`;
  return `LT_${uuidish()}`;
}

// Token cache. Each VU gets its own JS runtime, so this is already per-VU; it is keyed
// by VU id anyway to make that explicit. Logging in every iteration would measure the
// auth service instead of the drop flow, but the token does expire (`expiresIn`), so the
// cache is TTL-aware — otherwise a long run degrades into a wall of 401s.
const tokenCache = {};

function login() {
  const vu = exec.vu.idInTest;
  const cached = tokenCache[vu];
  if (cached && cached.expiresAt > Date.now()) return cached.token;

  const user = users[(vu - 1) % users.length];
  const res = http.post(
    `${CFG.baseUrl}/api/v1/members/login`,
    JSON.stringify({ email: user.email, password: user.password }),
    { headers: JSON_HEADERS, tags: { name: 'POST /api/v1/members/login' } },
  );
  const body = safeJson(res);
  const ok = res.status === 200 && !!(body && body.accessToken);
  assert('login 200 + accessToken', ok);
  if (!ok) return null;

  // expiresIn is seconds; renew a minute early. Fall back to 5 min if absent.
  const ttlMs = (typeof body.expiresIn === 'number' && body.expiresIn > 60)
    ? (body.expiresIn - 60) * 1000
    : 5 * 60 * 1000;
  tokenCache[vu] = { token: body.accessToken, expiresAt: Date.now() + ttlMs };
  return body.accessToken;
}

// ---------------------------------------------------------------------------
// scenario
// ---------------------------------------------------------------------------
/**
 * 사전조건 게이트. 여기서 throw하면 k6는 VU를 하나도 띄우지 않고 즉시 중단한다.
 *
 * 이 프로젝트에서 가장 흔한 낭비가 "드롭이 닫혀 있었다 / 재고가 0이었다 / BASE_URL 오타"로
 * 30분을 버리는 것이라, 부하를 걸기 전에 전부 확인한다.
 *
 * 호스트 확인에 대해: k6 JS 런타임에는 리졸버 API가 없어서 "IP가 기대값인가"는 직접
 * 검사할 수 없다. 대신 (a) 호스트명 허용목록 + (b) 응답이 실제 우리 DropResponse
 * 스키마인가 로 신원을 확인한다. 캡티브 포털·다른 팀 환경·주차된 도메인은 (b)에서 걸린다.
 */
export function setup() {
  const peak = CFG.duration
    ? CFG.vus
    : parseStages(CFG.stages).reduce((max, s) => Math.max(max, s.target), 0);
  // 열린 모델에서는 위 peak의 단위가 VU가 아니라 초당 도착률이다. 아래 재고 게이트와
  // 계정 수 경고가 둘 다 "동시에 몇 명이 붙는가"를 묻기 때문에 여기서 환산해 둔다.
  const estArrivals = OPEN_MODEL ? estimateArrivals(CFG.stages, RATE.startRate) : 0;
  const estConcurrency = OPEN_MODEL ? Math.ceil(peak * RATE.iterSec) : peak;

  const dropSrc = __ENV.DROP_ID ? '-e DROP_ID' : `${TARGET_FILE} (seed.js)`;
  console.log(
    OPEN_MODEL
      ? `drop-flow: profile=${PROFILE}(열린 모델) base=${CFG.baseUrl} drop=${CFG.dropId} [${dropSrc}] ` +
        `qty=${CFG.quantity} users=${users.length} peakRate=${peak}/s 추정도착=${estArrivals}건 ` +
        `preAllocatedVUs=${RATE_VUS.pre} maxVUs=${RATE_VUS.max} stages=${CFG.stages}`
      : `drop-flow: profile=${PROFILE} base=${CFG.baseUrl} drop=${CFG.dropId} [${dropSrc}] ` +
        `qty=${CFG.quantity} users=${users.length} peakVUs=${peak} stages=${CFG.stages}`,
  );
  // seed.js가 다른 프로파일 기준으로 사이징했으면 재고가 모자랄 수 있다. 아래 재고 게이트가
  // 어차피 잡아내지만, 원인을 먼저 알려주는 편이 낫다.
  if (TARGET && TARGET.sizedFor && !__ENV.DROP_ID && TARGET.sizedFor.profile !== PROFILE) {
    console.warn(
      `⚠ target.json 은 PROFILE=${TARGET.sizedFor.profile}(peak ${TARGET.sizedFor.peakVUs}) 기준으로 ` +
      `사이징됐는데 지금은 PROFILE=${PROFILE}(peak ${peak}) 로 돌린다. ` +
      `재고가 모자라면 PROFILE=${PROFILE} node loadtest/k6/seed.js 로 다시 시딩할 것.`,
    );
  }

  // (a) 호스트 허용목록 --------------------------------------------------------
  const hostMatch = /^https?:\/\/([^/:]+)/.exec(CFG.baseUrl);
  if (!hostMatch) {
    throw new Error(`BASE_URL을 파싱할 수 없다: ${CFG.baseUrl}`);
  }
  const host = hostMatch[1];
  if (CFG.allowedHosts.indexOf(host) === -1) {
    throw new Error(
      `대상 호스트 '${host}' 는 허용목록에 없다 (${CFG.allowedHosts.join(', ')}).\n` +
      `의도한 대상이 맞다면 -e ALLOWED_HOSTS=${host} 로 명시할 것. ` +
      `오타난 도메인에 부하를 거는 사고를 막기 위한 게이트다.`,
    );
  }
  if (CFG.baseUrl.indexOf('tosspayments.com') !== -1) {
    throw new Error('BASE_URL이 토스페이먼츠를 가리킨다. 절대 안 된다.');
  }

  // (b) 대상이 정말 우리 API인가 + 드롭 사전조건 -------------------------------
  // GET /api/v1/drops/{id} 는 게이트웨이에서 permitAll 이라 토큰 없이 조회 가능하다
  // (apigateway SecurityConfig: GET /api/v1/drops/** permitAll).
  const probe = http.get(`${CFG.baseUrl}/api/v1/drops/${CFG.dropId}`, {
    tags: { name: 'setup GET /api/v1/drops/{dropId}' },
    timeout: '15s',
  });

  if (probe.status === 0) {
    throw new Error(
      `${CFG.baseUrl} 에 접속 자체가 안 된다 (TLS/DNS/네트워크). error=${probe.error || 'n/a'}`,
    );
  }
  if (probe.status === 404) {
    throw new Error(`DROP_ID=${CFG.dropId} 가 존재하지 않는다 (404). 드롭을 먼저 만들 것.`);
  }
  if (probe.status !== 200) {
    throw new Error(`드롭 조회 실패: HTTP ${probe.status}. 게이트웨이/product 서비스 상태 확인.`);
  }

  const drop = safeJson(probe);
  // 스키마 확인 = 신원 확인. 200을 주는 아무 페이지나에 부하를 걸지 않기 위한 것.
  if (!drop || typeof drop.status !== 'string' || typeof drop.remainingQuantity !== 'number') {
    throw new Error(
      `응답이 DropResponse 스키마가 아니다. ${CFG.baseUrl} 가 정말 openat API인가?\n` +
      `body(앞 200자): ${String(probe.body).slice(0, 200)}`,
    );
  }

  if (drop.status !== 'OPEN') {
    throw new Error(
      `드롭 상태가 ${drop.status} 다 (OPEN이어야 한다). ` +
      `REGISTERED=아직 오픈 전(openAt=${drop.openAt}), CLOSE=종료, SOLD_OUT=매진. ` +
      `닫힌 드롭에 30분을 태우지 않도록 여기서 멈춘다.`,
    );
  }

  // 재고 게이트: 재고가 금방 마르면 대부분의 iteration이 SOLD_OUT으로 끝나 부하가 아니라
  // "매진 경합"만 측정하게 된다. peak VU 수의 최소 2배는 있어야 계단 하나를 버틴다.
  //
  // 열린 모델은 기준이 다르다. 닫힌 모델에서는 재고가 마르면 iteration이 빨리 끝나고 VU가
  // 곧바로 다음 iteration을 도는 정도지만, 열린 모델은 서버 상태와 무관하게 도착이 계속
  // 들어오므로 재고가 마른 시점 이후는 통째로 매진 측정이 된다. 그래서 추정 도착 수 전량이
  // 주문에 성공한다고 보고 그만큼을 요구한다(429·매진으로 실제 소모는 더 적지만, 여기서
  // 인색하게 잡아 라운드 후반을 통째로 버리는 쪽이 훨씬 비싸다).
  const needed = OPEN_MODEL ? estArrivals * CFG.quantity : peak * CFG.quantity * 2;
  if (!__ENV.SKIP_STOCK_CHECK && drop.remainingQuantity < needed) {
    throw new Error(
      OPEN_MODEL
        ? `잔여 재고 ${drop.remainingQuantity} 개로는 peakRate=${peak}/s(추정 도착 ${estArrivals}건, ` +
          `qty=${CFG.quantity}) 를 감당 못 한다. 최소 ${needed} 개 필요.\n` +
          `  seed.js 의 stress 사이징은 아직 닫힌 모델 기준이라 이 값을 못 맞춘다. 명시적으로 줄 것:\n` +
          `  PROFILE=stress DROP_TOTAL_QUANTITY=${needed} USER_COUNT=${estConcurrency} node loadtest/k6/seed.js\n` +
          `  계단을 낮추려면 -e RATE_TARGETS=2,4,8 처럼 줄이고, 확인 후 강행하려면 -e SKIP_STOCK_CHECK=1.`
        : `잔여 재고 ${drop.remainingQuantity} 개로는 peakVUs=${peak}(qty=${CFG.quantity}) 를 감당 못 한다. ` +
          `최소 ${needed} 개 필요. 재고를 늘린 드롭을 새로 만들거나 프로파일을 낮출 것 ` +
          `(-e PROFILE=smoke). 확인 후에도 강행하려면 -e SKIP_STOCK_CHECK=1.`,
    );
  }

  // seed.js의 DROP_PRICE 기본값이 10000이라 정상 경로에서는 여기 걸리지 않는다.
  // 손으로 만든 드롭이나 DROP_PRICE를 바꾼 경우에만 경고한다 — WireMock의
  // toss-query-payment.json 은 GET에 body가 없어 totalAmount를 10000으로 고정하고 있고,
  // 그 값이 reconcile 경로의 금액 비교에 쓰인다.
  if (drop.dropPrice !== 10000) {
    console.warn(
      `드롭 가격 ${drop.dropPrice} != WireMock toss-query-payment 스텁의 고정 totalAmount 10000. ` +
      `reconcile 경로를 볼 계획이면 스텁의 totalAmount/balanceAmount/cancelAmount를 맞출 것.`,
    );
  }

  // 열린 모델에서는 동시 VU가 도착률 × iteration 소요로 정해지므로(Little), 계정 부족 판정도
  // peak 도착률이 아니라 그 환산값으로 해야 한다. 계정이 모자라면 VU들이 계정을 공유하고
  // 사용자별 confirm 유량제한(2/s)에 직렬화되어, 측정하려던 천장 대신 유량제한을 다시 재게 된다.
  if (estConcurrency > users.length) {
    console.warn(
      `only ${users.length} seeded users for ${estConcurrency} VUs — accounts will be shared, ` +
      `which serialises them behind the per-user confirm rate limit. ` +
      `Re-run the seeder with USER_COUNT=${estConcurrency}.`,
    );
  }

  console.log(
    `precondition OK: drop status=${drop.status} remaining=${drop.remainingQuantity} price=${drop.dropPrice}`,
  );
  // 시작 잔여를 게이지로도 실어 둔다. teardown 은 지표를 못 읽고 handleSummary 는 setup 의
  // 반환값을 못 받으므로, 재고 검산 산술을 한곳(handleSummary)에서 끝내려면 이 경로가 필요하다.
  stockRemainingStart.add(drop.remainingQuantity);
  return { dropPrice: drop.dropPrice, remainingAtStart: drop.remainingQuantity };
}

export default function () {
  const flowStart = Date.now();

  const token = group('auth', login);
  if (!token) {
    outLoginFailed.add(1);
    sleep(1);
    return;
  }
  const headers = bearer(token);

  // -- queue -----------------------------------------------------------------
  // granted quantity is decided by the server, not by us: the admission ticket's
  // VALUE is the granted quantity and the gateway compares it against the order
  // body's quantity (400 QUEUE_QUANTITY_MISMATCH otherwise). After a PARTIAL
  // decision the granted quantity is smaller than what we asked for, so the order
  // MUST use the quantity the status response reports.
  const queueResult = group('queue', () => runQueue(headers));

  if (queueResult.outcome !== 'READY') {
    switch (queueResult.outcome) {
      case 'SOLD_OUT': outSoldOut.add(1); break;
      case 'POLL_TIMEOUT': outPollTimeout.add(1); break;
      case 'GAVE_UP': outGaveUp.add(1); break;
      case 'RATE_LIMITED': outRateLimited.add(1); break;
      default: outQueueError.add(1); break;
    }
    sleep(CFG.thinkTimeMs / 1000);
    return;
  }
  queueWaitMs.add(queueResult.waitMs);
  queuePollCount.add(queueResult.polls);

  // -- order -----------------------------------------------------------------
  const orderResult = group('order', () => createOrder(headers, queueResult.grantedQuantity));

  if (!orderResult.ok) {
    switch (orderResult.outcome) {
      // 재고 소진은 드롭의 정상 종단 결과다 — 큐 경로(SOLD_OUT 상태)와 같은 카운터로 센다.
      case 'SOLD_OUT': outSoldOut.add(1); break;
      case 'ADMISSION_419': outAdmission419.add(1); break;
      case 'QUANTITY_400': outQuantity400.add(1); break;
      case 'RATE_LIMITED': outRateLimited.add(1); break;
      default: outOrderError.add(1); break;
    }
    sleep(CFG.thinkTimeMs / 1000);
    return;
  }

  // -- payment ---------------------------------------------------------------
  const payResult = group('payment', () => confirmPayment(headers, orderResult));

  switch (payResult.outcome) {
    case 'APPROVED': outSuccess.add(1); break;
    case 'FAILED': outPaymentRejected.add(1); break;
    case 'PAYMENT_PENDING': outPaymentPending.add(1); break;
    case 'RATE_LIMITED': outRateLimited.add(1); break;
    default: outPaymentError.add(1); break;
  }

  flowDurationMs.add(Date.now() - flowStart);
  sleep(CFG.thinkTimeMs / 1000);
}

/**
 * POST entry, then poll status honoring the server-advised pollIntervalMs, capped by
 * MAX_WAIT_MS / MAX_POLLS. Answers DECISION_REQUIRED and keeps polling.
 */
function runQueue(headers) {
  const start = Date.now();

  const entryRes = http.post(
    `${CFG.baseUrl}/api/v1/queues/${CFG.dropId}/entry`,
    JSON.stringify({ quantity: CFG.quantity }),
    { headers, tags: { name: 'POST /api/v1/queues/{dropId}/entry' } },
  );
  const entryOk = entryRes.status === 200;
  assert('queue entry 200', entryOk);
  if (!entryOk) {
    return { outcome: entryRes.status === 429 ? 'RATE_LIMITED' : 'ENTRY_ERROR', polls: 0 };
  }

  let state = safeJson(entryRes);
  let polls = 0;
  let decisionsAnswered = 0;

  for (;;) {
    if (!state || !state.status) {
      assert('queue status body parseable', false);
      return { outcome: 'STATUS_ERROR', polls };
    }

    switch (state.status) {
      case 'READY':
        return {
          outcome: 'READY',
          waitMs: Date.now() - start,
          polls,
          // server-granted quantity; fall back to what we asked for if absent
          grantedQuantity: typeof state.quantity === 'number' ? state.quantity : CFG.quantity,
        };
      case 'SOLD_OUT':
        return { outcome: 'SOLD_OUT', polls };
      case 'NOT_IN_QUEUE':
        // We just entered — seeing this means the slot was reclaimed (heartbeat TTL)
        // or the drop is gone. Terminal, and worth counting as an error not a sell-out.
        assert('queue slot not dropped (NOT_IN_QUEUE)', false);
        return { outcome: 'NOT_IN_QUEUE', polls };
      case 'DECISION_REQUIRED': {
        if (CFG.decisionChoice === 'GIVE_UP') {
          postDecision(headers, 'GIVE_UP');
          return { outcome: 'GAVE_UP', polls };
        }
        // Answer once per DECISION_REQUIRED occurrence; the response carries the new state.
        decisionsAnswered += 1;
        // WAIT can legitimately re-ask; guard against an answer loop.
        if (decisionsAnswered > CFG.maxPolls || Date.now() - start >= CFG.maxWaitMs) {
          return { outcome: 'POLL_TIMEOUT', polls };
        }
        const decided = postDecision(headers, CFG.decisionChoice);
        if (!decided.ok) return { outcome: 'DECISION_ERROR', polls };
        state = decided.body;
        // If the server still wants a decision, back off before answering again so
        // this never becomes a tight loop.
        if (state && state.status === 'DECISION_REQUIRED') {
          sleep(clampPoll(state.pollIntervalMs) / 1000);
        }
        continue;
      }
      case 'WAITING':
        break;
      default:
        assert(`known queue status (got ${state.status})`, false);
        return { outcome: 'STATUS_ERROR', polls };
    }

    // still WAITING — cap first, then honor the server-advised interval
    if (polls >= CFG.maxPolls || Date.now() - start >= CFG.maxWaitMs) {
      return { outcome: 'POLL_TIMEOUT', polls };
    }
    sleep(clampPoll(state.pollIntervalMs) / 1000);
    polls += 1;

    const statusRes = http.get(`${CFG.baseUrl}/api/v1/queues/${CFG.dropId}/status`, {
      headers,
      tags: { name: 'GET /api/v1/queues/{dropId}/status' },
    });
    const statusOk = statusRes.status === 200;
    assert('queue status 200', statusOk);
    if (!statusOk) {
      return { outcome: statusRes.status === 429 ? 'RATE_LIMITED' : 'STATUS_ERROR', polls };
    }
    state = safeJson(statusRes);
  }
}

function postDecision(headers, choice) {
  const res = http.post(
    `${CFG.baseUrl}/api/v1/queues/${CFG.dropId}/decision`,
    JSON.stringify({ choice }),
    { headers, tags: { name: 'POST /api/v1/queues/{dropId}/decision' } },
  );
  const ok = res.status === 200;
  assert('queue decision 200', ok);
  return { ok, body: ok ? safeJson(res) : null };
}

/**
 * idempotencyKey is a BODY field and must be unique per logical order attempt
 * (the same value is reused only when retrying the very same attempt).
 */
function createOrder(headers, quantity) {
  const idempotencyKey = `lt-${exec.vu.idInTest}-${exec.scenario.iterationInTest}-${uuidish()}`;
  const started = Date.now();
  const res = http.post(
    `${CFG.baseUrl}/api/v1/orders`,
    JSON.stringify({
      dropId: CFG.dropId,
      quantity,
      idempotencyKey,
      orderName: 'loadtest drop item',
    }),
    { headers, tags: { name: 'POST /api/v1/orders' } },
  );
  const elapsed = Date.now() - started;

  // 419 and 400 are distinct, expected outcomes from the gateway AdmissionCheck filter,
  // not generic failures — they are counted separately by the caller.
  // 419/429는 게이트웨이에서 끊겨 order 서비스에 닿지 않으므로 order_create_ms 대신
  // order_create_gateway_reject_ms 로 보낸다(400은 계약 위반이라 count==0 임계로 잡힌다).
  if (res.status === 419) {
    orderCreateRejectMs.add(elapsed);
    assert('order not rejected for missing admission ticket (419)', false);
    return { ok: false, outcome: 'ADMISSION_419' };
  }
  if (res.status === 429) {
    orderCreateRejectMs.add(elapsed);
    return { ok: false, outcome: 'RATE_LIMITED' };
  }
  orderCreateMs.add(elapsed);

  if (res.status === 400) {
    assert('order quantity matches granted admission quantity (400)', false);
    return { ok: false, outcome: 'QUANTITY_400' };
  }

  // 409 = 재고 소진. 드롭에서 재고가 마르는 것은 장애가 아니라 정상 종단 결과이므로
  // 단정(assert) 실패로 잡지 않고 outcome_sold_out 으로만 센다.
  //
  // 서버 공통 에러 포맷은 {"error":"CODE","message":"..."} 이고(common/.../ErrorResponse.java),
  // 재고 소진 코드는 order 가 SOLD_OUT, product 가 DROP_SOLD_OUT 이다. order 는 product 의
  // DROP_SOLD_OUT/SOLD_OUT 을 자기 SOLD_OUT(409)으로 다시 매핑한다(ProductIntegrationClient).
  // 일부 내부 응답이 code 필드를 쓰기도 해서(ProductErrorResponse) 둘 다 읽는다.
  // 본문이 비었거나 파싱이 깨져도 409면 매진으로 분류한다 — 매진이 오류로 뭉쳐지는 쪽이
  // 훨씬 비싼 오판이기 때문이다.
  if (res.status === 409) {
    const conflict = safeJson(res);
    const code = conflict && (conflict.error || conflict.code);
    if (!code || code === 'SOLD_OUT' || code === 'DROP_SOLD_OUT') {
      return { ok: false, outcome: 'SOLD_OUT' };
    }
    // 매진이 아닌 409(멱등키 충돌, 주문 상태 위반, 결제 진행 중 등)는 진짜 오류다.
    assert(`order conflict is stock exhaustion (got ${code})`, false);
    return { ok: false, outcome: 'ORDER_ERROR' };
  }

  const body = safeJson(res);
  const ok = (res.status === 201 || res.status === 200) && !!(body && body.orderId);
  assert('order created (200/201 + orderId)', ok);
  if (!ok) return { ok: false, outcome: 'ORDER_ERROR' };

  return { ok: true, orderId: body.orderId, amount: body.amount, idempotencyKey };
}

function confirmPayment(headers, order) {
  const paymentKey = makePaymentKey();
  const started = Date.now();
  const res = http.post(
    `${CFG.baseUrl}/api/v1/payments/confirm`,
    JSON.stringify({
      orderId: order.orderId,
      amount: order.amount,
      paymentKey,
    }),
    {
      // Idempotency-Key is not read by the confirm controller (see README discrepancies),
      // but sending it is harmless and mirrors what the real client does.
      headers: { ...headers, 'Idempotency-Key': `pay-${order.idempotencyKey}` },
      tags: { name: 'POST /api/v1/payments/confirm' },
    },
  );
  const elapsed = Date.now() - started;

  // The gateway rate-limits this route per user (replenish 2/s, burst 5).
  // 429는 payment 서비스도 PG도 거치지 않고 게이트웨이에서 즉시 되돌아온다. 이걸
  // payment_confirm_ms 에 넣으면 "PG 왕복 지연" 트렌드가 유량제한 응답으로 희석돼
  // 중앙값이 PG 스텁 median 300ms 아래로 내려간다. 별도 트렌드로 분리한다.
  if (res.status === 429) {
    paymentConfirm429Ms.add(elapsed);
    return { outcome: 'RATE_LIMITED' };
  }
  // status 0 = 응답을 아예 못 받았다(연결 실패·클라이언트 타임아웃). 왕복 지연이 아니라
  // 실패이므로 지연 트렌드에 넣지 않는다. 서버가 5xx로라도 응답했다면(예: Hikari 5s
  // 커넥션 고갈) 그건 실제 왕복 시간이므로 아래에서 트렌드에 반영한다.
  if (res.status === 0) {
    assert('payment confirm got a response (not a transport error)', false);
    return { outcome: 'PAYMENT_ERROR' };
  }
  paymentConfirmMs.add(elapsed);

  const body = safeJson(res);
  const http200 = res.status === 200 && !!(body && body.status);
  assert('payment confirm 200 + status', http200);
  if (!http200) return { outcome: 'PAYMENT_ERROR' };

  // Approved / rejected / race are all HTTP 200 — the body's status is the real outcome.
  const approved = body.status === 'APPROVED';
  // Only assert approval when we did not deliberately inject a fault.
  if (!paymentKey.startsWith('LTFAIL') && !paymentKey.startsWith('LTTIMEOUT')) {
    assert('payment APPROVED', approved);
  }
  return { outcome: body.status };
}

/**
 * 라운드 종료 검산 — 재고가 실제로 얼마나 줄었나.
 *
 * 라운드마다 사람이 손으로 하던 계산이다. 기대식은 "시작 잔여 − 성공 건수 = 종료 잔여" 인데
 * 실제로는 실패한 결제가 재고를 물고 만료 보상 때까지 돌려주지 않아 그만큼 덜 남는다
 * (지난 램프 라운드: 시작 14745 − 성공 2520 = 12225 여야 하는데 종료가 12087, 차이 138이
 * 실패 138건과 정확히 일치했다 = 유령 매진).
 *
 * ── 왜 teardown 과 handleSummary 로 쪼갰나 ────────────────────────────────────
 * 검산에 필요한 숫자가 셋인데 k6에서 셋을 한 자리에서 볼 수 없다.
 *   - 시작 잔여: setup() 만 안다. 반환값으로 teardown 에, 게이지로 handleSummary 에 간다.
 *   - 종료 잔여: HTTP 조회가 있어야 안다. 그래서 teardown 몫이다(handleSummary 는 테스트가
 *     끝난 뒤 요약을 만드는 자리지 요청을 보내는 자리가 아니다).
 *   - 성공 건수: 커스텀 지표다. **k6는 teardown 에 지표를 넘겨주지 않는다.** handleSummary 만 안다.
 * 그래서 teardown 은 조회해서 원본 사실(종료 잔여·드롭 상태)을 시끄럽게 찍고 그 값을 게이지에
 * 실어 보내는 데까지만 하고, 세 숫자를 합치는 산술은 handleSummary 가 끝낸다. 억지로 teardown
 * 에 몰면 성공 건수를 몰라 반쪽 검산이 되고, 반대로 몰면 종료 잔여를 알 방법이 없다.
 *
 * WireMock 요청 저널은 여기서 안 본다 — ClusterIP라 노트북에서 닿지 않는다(README 4절 참조).
 */
export function teardown(data) {
  const startRemaining = data && typeof data.remainingAtStart === 'number' ? data.remainingAtStart : null;

  const res = http.get(`${CFG.baseUrl}/api/v1/drops/${CFG.dropId}`, {
    tags: { name: 'teardown GET /api/v1/drops/{dropId}' },
    timeout: '15s',
  });
  const drop = res.status === 200 ? safeJson(res) : null;

  if (!drop || typeof drop.remainingQuantity !== 'number') {
    // 조용히 넘기면 라운드 결과에 "검산 못 함"이 안 남는다. 시끄럽게 남긴다.
    console.error(
      `⚠ 재고 검산 실패: 종료 시점 드롭 조회가 안 됐다 (HTTP ${res.status}, error=${res.error || 'n/a'}). ` +
      `시작 잔여=${startRemaining === null ? '알 수 없음' : startRemaining}. ` +
      `GET ${CFG.baseUrl}/api/v1/drops/${CFG.dropId} 로 직접 확인할 것.`,
    );
    return;
  }

  stockRemainingEnd.add(drop.remainingQuantity);

  const decrease = startRemaining === null ? null : startRemaining - drop.remainingQuantity;
  console.log(
    `재고(teardown): 시작=${startRemaining === null ? 'n/a' : startRemaining} ` +
    `종료=${drop.remainingQuantity} 감소=${decrease === null ? 'n/a' : decrease} ` +
    `드롭 상태=${drop.status}. 성공 건수 대비 검산은 아래 요약의 "재고 검산" 절에 찍힌다.`,
  );
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data),
    'loadtest-summary.json': JSON.stringify(data, null, 2),
  };
}

/** 지표가 한 번도 안 찍혔으면 k6 요약에 아예 안 들어온다. 없는 건 0으로 본다. */
function counterCount(data, name) {
  const m = data.metrics[name];
  return m && m.values && typeof m.values.count === 'number' ? m.values.count : 0;
}

function gaugeValue(data, name) {
  const m = data.metrics[name];
  return m && m.values && typeof m.values.value === 'number' ? m.values.value : null;
}

// Minimal text summary so handleSummary does not need an external module (jslib.k6.io
// is unavailable when k6 runs without network access).
function textSummary(data) {
  const lines = ['', '=== drop-flow outcomes ==='];
  const counters = Object.keys(data.metrics)
    .filter((k) => k.startsWith('outcome_'))
    .sort();
  for (const k of counters) {
    lines.push(`  ${k.padEnd(30)} ${data.metrics[k].values.count}`);
  }
  lines.push('=== phase latencies (ms) ===');
  // 게이트웨이가 끊은 응답(419/429)은 앞의 두 트렌드에 섞지 않고 따로 찍는다 —
  // payment_confirm_ms 를 PG 지연 근거로 인용할 수 있게 하기 위한 분리다.
  const latencies = [
    'queue_wait_ms',
    'order_create_ms',
    'order_create_gateway_reject_ms',
    'payment_confirm_ms',
    'payment_confirm_429_ms',
    'flow_duration_ms',
  ];
  for (const k of latencies) {
    const m = data.metrics[k];
    if (!m || !m.values || m.values.med === undefined) {
      lines.push(`  ${k.padEnd(30)} (no samples)`);
      continue;
    }
    const v = m.values;
    lines.push(`  ${k.padEnd(30)} med=${v.med.toFixed(0)} p95=${v['p(95)'].toFixed(0)} max=${v.max.toFixed(0)}`);
  }

  lines.push(...loadGeneratorLines(data));
  lines.push(...stockReconciliationLines(data));

  lines.push('');
  return lines.join('\n');
}

/**
 * 부하 생성기가 실제로 계획한 부하를 만들었는가.
 *
 * 열린 모델(ramping-arrival-rate)에서 VU가 모자라면 k6는 도착을 그냥 버리고
 * dropped_iterations 를 올린다. 이 값이 0이 아니면 "서버가 못 받아낸 것"과 "우리가 못 만든
 * 것"이 결과에 섞여 있다는 뜻이고, 그 라운드의 처리량은 용량 근거로 쓸 수 없다
 * (RATE_PRE_VUS / RATE_MAX_VUS 를 올리고 다시 돌려야 한다). 기본 요약에는 안 나오므로 여기서 찍는다.
 * 닫힌 모델에는 이 지표가 아예 없다 — VU가 알아서 기다리기 때문이다.
 */
function loadGeneratorLines(data) {
  const lines = ['=== 부하 생성기 ==='];
  const iters = data.metrics.iterations;
  if (iters && iters.values) {
    lines.push(
      `  ${'iterations'.padEnd(30)} ${iters.values.count} (${(iters.values.rate || 0).toFixed(2)}/s)`,
    );
  }
  const dropped = data.metrics.dropped_iterations;
  if (!dropped || !dropped.values) {
    lines.push(`  ${'dropped_iterations'.padEnd(30)} (없음 — 닫힌 모델이라 도착을 버릴 일이 없다)`);
    return lines;
  }
  const n = dropped.values.count;
  lines.push(`  ${'dropped_iterations'.padEnd(30)} ${n} (${(dropped.values.rate || 0).toFixed(2)}/s)`);
  if (n > 0) {
    lines.push(
      `  ⚠ 도착 ${n}건을 VU 부족으로 만들지 못했다. 이 라운드의 처리량은 서버 용량이 아니라 ` +
      `부하 생성기 한계를 포함한다 — RATE_MAX_VUS/RATE_PRE_VUS 를 올려 다시 돌릴 것.`,
    );
  }
  return lines;
}

/**
 * 재고 검산. 세 숫자의 출처는 teardown 주석 참조(시작=setup 게이지, 종료=teardown 게이지,
 * 성공=이 요약의 카운터). 기대식은 "감소량 = 성공 건수" 이고, 남는 차이가 곧 실패한 결제가
 * 물고 있는 미복원 재고다 = 유령 매진.
 */
function stockReconciliationLines(data) {
  const lines = ['=== 재고 검산 ==='];
  const start = gaugeValue(data, 'stock_remaining_start');
  const end = gaugeValue(data, 'stock_remaining_end');
  const success = counterCount(data, 'outcome_success');

  if (start === null || end === null) {
    lines.push(
      `  ⚠ 검산 불가 — ${start === null ? '시작' : '종료'} 잔여를 못 읽었다 ` +
      `(setup/teardown 로그 확인). 성공 건수만: ${success}`,
    );
    return lines;
  }

  const decrease = start - end;
  const ghost = decrease - success;
  lines.push(`  ${'시작 잔여'.padEnd(26)} ${start}`);
  lines.push(`  ${'종료 잔여'.padEnd(26)} ${end}`);
  lines.push(`  ${'실제 감소량'.padEnd(25)} ${decrease}`);
  lines.push(`  ${'성공(outcome_success)'.padEnd(22)} ${success}`);
  lines.push(`  ${'차이(미복원 재고)'.padEnd(23)} ${ghost}`);

  if (ghost > 0) {
    // 결제 단계까지 갔다가 실패한 iteration = 주문이 재고를 잡은 뒤 결제가 깨진 건들.
    // 이 합이 차이와 같으면 미복원 재고의 출처가 확정된다(지난 램프 라운드에서 138=138).
    const payFailures = counterCount(data, 'outcome_payment_rejected')
      + counterCount(data, 'outcome_payment_pending')
      + counterCount(data, 'outcome_payment_error');
    lines.push(
      `  ⚠ 유령 매진 후보 ${ghost}건 — 실패한 결제가 재고를 물고 있다(만료 보상 전까지 안 돌아온다).`,
    );
    lines.push(
      `    결제 단계 실패 합 ${payFailures}건(rejected/pending/error). 두 값이 같으면 출처가 확정된다.`,
    );
  } else if (ghost < 0) {
    lines.push(
      `  ⚠ 감소량이 성공 건수보다 ${-ghost}건 적다. 만료 보상이 라운드 중에 재고를 돌려줬거나 ` +
      `이 드롭에 다른 트래픽이 섞였다 — 이 라운드의 재고 수치는 그대로 인용하지 말 것.`,
    );
  } else {
    lines.push('  미복원 재고 없음 (감소량 = 성공 건수).');
  }
  return lines;
}

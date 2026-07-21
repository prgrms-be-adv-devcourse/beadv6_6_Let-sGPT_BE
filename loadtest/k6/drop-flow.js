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
 *
 * Everything is __ENV-driven; see the CFG block below for names and defaults.
 */
import http from 'k6/http';
import { group, sleep, check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Counter } from 'k6/metrics';
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
const PROFILES = {
  // 흐름이 끝까지 도는지만 증명. 부하 아님. 항상 이걸 먼저 돌린다.
  // 3 VU면 confirm ~1/s — pool 2로도 여유롭고 사용자별 rate limit(2/s)에도 안 걸린다.
  smoke: '20s:3,40s:3,10s:0',

  // 무릎 탐색. 각 계단을 2분 유지해 p95가 안정된 표본을 만든다(30s 계단은 램프 과도구간만
  // 보게 된다). 5 -> 40까지 올리며 이론 무릎(25~35) 앞뒤를 모두 통과. 총 10분 + ramp-down.
  ramp: '1m:5,2m:10,2m:20,2m:30,2m:40,1m:0',

  // 무릎을 찾은 뒤 그 지점에서 오래 눌러 큐/아웃박스/정산이 밀리는지 본다. -e HOLD_VUS 로 조절.
  hold: `1m:${__ENV.HOLD_VUS || '20'},10m:${__ENV.HOLD_VUS || '20'},1m:0`,

  // 벽을 일부러 보고 싶을 때만. 여기서 나오는 숫자는 "용량"이 아니라 "포화 시 어떻게 깨지는가".
  stress: '30s:20,1m:60,1m:100,1m:0',
};

const PROFILE = __ENV.PROFILE || 'smoke';   // 기본은 안전한 쪽
if (!(PROFILE in PROFILES) && !__ENV.STAGES && !__ENV.DURATION) {
  throw new Error(`unknown PROFILE=${PROFILE}; one of: ${Object.keys(PROFILES).join(', ')}`);
}

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

// ---------------------------------------------------------------------------
// options
// ---------------------------------------------------------------------------
function parseStages(spec) {
  return spec.split(',').map((part) => {
    const [duration, target] = part.split(':');
    return { duration: duration.trim(), target: parseInt(target, 10) };
  });
}

const scenario = CFG.duration
  ? { executor: 'constant-vus', vus: CFG.vus, duration: CFG.duration }
  : { executor: 'ramping-vus', startVUs: 0, stages: parseStages(CFG.stages), gracefulRampDown: '30s' };

export const options = {
  scenarios: { drop_flow: scenario },
  // Thresholds that actually fail a bad run rather than decorate the summary.
  thresholds: {
    // Hard failures: the harness itself is broken, or the service is erroring out.
    'outcome_login_failed': ['count==0'],
    'outcome_queue_error': ['count==0'],
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
    'order_create_ms': ['p(95)<3000'],
    'payment_confirm_ms': ['p(95)<5000'],   // PG stub median 300ms + backend work
    'http_req_failed': ['rate<0.05'],
    'checks': ['rate>0.95'],
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
  const dropSrc = __ENV.DROP_ID ? '-e DROP_ID' : `${TARGET_FILE} (seed.js)`;
  console.log(
    `drop-flow: profile=${PROFILE} base=${CFG.baseUrl} drop=${CFG.dropId} [${dropSrc}] ` +
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
  const needed = peak * CFG.quantity * 2;
  if (!__ENV.SKIP_STOCK_CHECK && drop.remainingQuantity < needed) {
    throw new Error(
      `잔여 재고 ${drop.remainingQuantity} 개로는 peakVUs=${peak}(qty=${CFG.quantity}) 를 감당 못 한다. ` +
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

  if (peak > users.length) {
    console.warn(
      `only ${users.length} seeded users for ${peak} VUs — accounts will be shared, ` +
      `which serialises them behind the per-user confirm rate limit. ` +
      `Re-run the seeder with USER_COUNT=${peak}.`,
    );
  }

  console.log(
    `precondition OK: drop status=${drop.status} remaining=${drop.remainingQuantity} price=${drop.dropPrice}`,
  );
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
  orderCreateMs.add(Date.now() - started);

  // 419 and 400 are distinct, expected outcomes from the gateway AdmissionCheck filter,
  // not generic failures — they are counted separately by the caller.
  if (res.status === 419) {
    assert('order not rejected for missing admission ticket (419)', false);
    return { ok: false, outcome: 'ADMISSION_419' };
  }
  if (res.status === 400) {
    assert('order quantity matches granted admission quantity (400)', false);
    return { ok: false, outcome: 'QUANTITY_400' };
  }
  if (res.status === 429) {
    return { ok: false, outcome: 'RATE_LIMITED' };
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
  paymentConfirmMs.add(Date.now() - started);

  // The gateway rate-limits this route per user (replenish 2/s, burst 5).
  if (res.status === 429) {
    return { outcome: 'RATE_LIMITED' };
  }

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

export function handleSummary(data) {
  return {
    stdout: textSummary(data),
    'loadtest-summary.json': JSON.stringify(data, null, 2),
  };
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
  for (const k of ['queue_wait_ms', 'order_create_ms', 'payment_confirm_ms', 'flow_duration_ms']) {
    const m = data.metrics[k];
    if (!m || !m.values || m.values.med === undefined) {
      lines.push(`  ${k.padEnd(20)} (no samples)`);
      continue;
    }
    const v = m.values;
    lines.push(`  ${k.padEnd(20)} med=${v.med.toFixed(0)} p95=${v['p(95)'].toFixed(0)} max=${v.max.toFixed(0)}`);
  }
  lines.push('');
  return lines.join('\n');
}

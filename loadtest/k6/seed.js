#!/usr/bin/env node
/*
 * seed.js — 부하테스트 대상 일체를 만든다: 회원 N명 + 판매자 + 상품 + 드롭.
 *
 * 산출물 2개 (둘 다 loadtest/k6/ 에, .gitignore 됨):
 *   users.json   [{email, password}, ...]   — drop-flow.js 가 VU별 계정으로 쓴다
 *   target.json  {dropId, openAt, totalQuantity, dropPrice, ...}
 *                                           — drop-flow.js 가 DROP_ID 없이 대상을 찾는다
 *
 * 순수 Node(>=18, global fetch). k6가 아니다 — k6 런타임은 파일을 못 쓴다. 무의존.
 *
 * 기본 대상은 live EC2 배포(https://openat.duckdns.org). 로컬을 겨냥하려면 BASE_URL을 준다.
 *
 * SEED_CONCURRENCY 기본값은 4다(10에서 낮춤). 원격에선 시딩 자체가 member 서비스에 대한
 * 소형 부하테스트이고, 이제 판매자 등록 쓰기까지 얹히기 때문에 더더욱 얌전해야 한다.
 * 시딩이 앱을 넘어뜨리면 본 테스트를 시작조차 못 한다.
 *
 * 멱등:
 *   - 회원: 이메일/닉네임이 인덱스에서 결정되고, "이미 존재" 실패는 무시하며, 실제 로그인이
 *     성공한 계정만 users.json에 쓴다. 재실행은 재검증에 불과하다.
 *   - 판매자: GET /api/v1/seller/me 로 기존 건을 먼저 찾고 없을 때만 등록한다.
 *   - 드롭: 기존 target.json의 드롭이 아직 OPEN이고 재고가 충분하면 그대로 재사용한다
 *     (드롭 생성 자체는 멱등하지 않으므로). 강제로 새로 만들려면 FORCE_NEW_DROP=1.
 *
 * Usage:
 *   node loadtest/k6/seed.js                           # PROFILE=smoke 기준 사이징
 *   PROFILE=ramp USER_COUNT=60 node loadtest/k6/seed.js
 *   SEED_DROP=0 node loadtest/k6/seed.js               # 계정만
 *   FORCE_NEW_DROP=1 PROFILE=ramp node loadtest/k6/seed.js
 *   BASE_URL=http://localhost:8000 node loadtest/k6/seed.js
 */
const fs = require('fs');
const path = require('path');

const BASE_URL = (process.env.BASE_URL || 'https://openat.duckdns.org').replace(/\/$/, '');
const USER_COUNT = parseInt(process.env.USER_COUNT || '50', 10);
const PREFIX = process.env.USER_PREFIX || 'lt';
// >= 8 chars: SignUpRequest enforces @Size(min = 8, max = 64).
const PASSWORD = process.env.USER_PASSWORD || 'loadtestPass1234';
const OUT_FILE = process.env.USERS_FILE || path.join(__dirname, 'users.json');
const TARGET_FILE = process.env.TARGET_FILE || path.join(__dirname, 'target.json');
const CONCURRENCY = parseInt(process.env.SEED_CONCURRENCY || '4', 10);

const SEED_DROP = process.env.SEED_DROP !== '0';
const FORCE_NEW_DROP = process.env.FORCE_NEW_DROP === '1';

// ---------------------------------------------------------------------------
// 드롭 사이징 — 프로파일에서 역산
// ---------------------------------------------------------------------------
// ⚠ 이 표는 drop-flow.js 의 PROFILES 와 같은 값을 들고 있어야 한다. 중복이지만 k6 스크립트는
//   k6 전용 API(open/__ENV)를 쓰기 때문에 Node에서 require할 수 없다. 대신 어긋나도 조용히
//   넘어가지 않는다: drop-flow.js 의 setup()이 실제 peak VU 기준으로 잔여 재고를 다시 검사하고
//   모자라면 실행을 거부한다. 즉 드리프트는 런타임에 시끄럽게 터진다.
const PROFILE_STAGES = {
  smoke: '20s:3,40s:3,10s:0',
  ramp: '1m:5,2m:10,2m:20,2m:30,2m:40,1m:0',
  hold: `1m:${process.env.HOLD_VUS || '20'},10m:${process.env.HOLD_VUS || '20'},1m:0`,
  stress: '30s:20,1m:60,1m:100,1m:0',
};
const PROFILE = process.env.PROFILE || 'smoke';
const STAGES = process.env.STAGES || PROFILE_STAGES[PROFILE];
if (!STAGES) {
  console.error(`unknown PROFILE=${PROFILE}; one of: ${Object.keys(PROFILE_STAGES).join(', ')}`);
  process.exit(1);
}

// 1 iteration의 대략적 소요(초). login(캐시됨) + queue entry + poll 몇 번 + order +
// confirm(PG 300ms) + think 1s ≈ 3~5초. 재고 산정에만 쓰는 추정치라 보수적으로 4를 쓴다.
const EST_ITER_SEC = parseFloat(process.env.EST_ITER_SEC || '4');
const QTY = parseInt(process.env.QTY || '1', 10);

// 기본 10000 — WireMock의 toss-query-payment.json 스텁이 GET에 body가 없어 totalAmount를
// 10000으로 고정하고 있고, 이 값이 reconcile 경로의 금액 비교에 쓰인다. 두 값을 일치시켜
// "가격이 다르면 경고" 상태를 아예 없앤다.
const DROP_PRICE = parseInt(process.env.DROP_PRICE || '10000', 10);
// limitPerUser는 기본 미지정(=무제한)이다. 값을 주면 큐가 admit할 수 있는 총량이
// (계정 수 × limitPerUser)로 잘려서, totalQuantity를 아무리 크게 잡아도 그 벽에서 멈춘다.
// 예: 계정 60개 + limitPerUser 2 => 최대 120개. 그 뒤 iteration은 전부 SOLD_OUT이 된다.
// 1인 한도 로직 자체를 시험할 때만 켤 것.
const DROP_LIMIT_PER_USER = process.env.DROP_LIMIT_PER_USER
  ? parseInt(process.env.DROP_LIMIT_PER_USER, 10)
  : null;
// 오픈까지의 여유. 시딩이 끝나고 사람이 k6를 띄우기까지의 시간 + 시계 오차를 흡수한다.
const OPEN_DELAY_S = parseInt(process.env.DROP_OPEN_DELAY_S || '60', 10);
// 기본은 closeAt 미지정(무기한). 테스트 도중 드롭이 닫혀 결과가 반토막 나는 걸 막는다.
const CLOSE_AFTER_S = process.env.DROP_CLOSE_AFTER_S
  ? parseInt(process.env.DROP_CLOSE_AFTER_S, 10)
  : null;

const JSON_HEADERS = { 'Content-Type': 'application/json' };

/** "<dur>:<target>,..." 를 파싱한다. dur은 k6 표기(30s, 2m, 1h). */
function parseStages(spec) {
  return spec.split(',').map((part) => {
    const [d, t] = part.split(':');
    const m = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(d.trim());
    if (!m) throw new Error(`stage duration을 파싱할 수 없다: '${d}'`);
    const unit = { ms: 0.001, s: 1, m: 60, h: 3600 }[m[2]];
    return { seconds: parseFloat(m[1]) * unit, target: parseInt(t, 10) };
  });
}

/**
 * 램프 구간을 사다리꼴로 적분해 총 iteration 수를 추정한다.
 * ramping-vus는 startVUs=0에서 각 stage의 target까지 선형으로 움직이므로,
 * 한 stage의 VU-초 = (이전 target + 현재 target)/2 × 지속시간.
 */
function estimateIterations(spec) {
  const stages = parseStages(spec);
  let prev = 0;
  let vuSeconds = 0;
  for (const s of stages) {
    vuSeconds += ((prev + s.target) / 2) * s.seconds;
    prev = s.target;
  }
  return Math.ceil(vuSeconds / EST_ITER_SEC);
}

function peakVUs(spec) {
  return parseStages(spec).reduce((max, s) => Math.max(max, s.target), 0);
}

const PEAK = peakVUs(STAGES);
const EST_ITERS = estimateIterations(STAGES);
// totalQuantity는 큐가 통틀어 admit할 수 있는 총량이다. 이게 VU 수보다 작으면 대부분의
// iteration이 SOLD_OUT으로 끝나서, 큐/주문/결제가 아니라 매진 경합만 측정된다.
// 추정 iteration 전부가 주문에 성공한다고 보고 30% 여유를 더한다. 여기서 인색하면 램프
// 후반이 통째로 SOLD_OUT 측정이 되는데, totalQuantity는 그냥 정수 필드라 크게 잡는 비용이 없다.
const TOTAL_QUANTITY = parseInt(
  process.env.DROP_TOTAL_QUANTITY || String(Math.ceil(EST_ITERS * QTY * 1.3)),
  10,
);

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------
function userFor(i) {
  return {
    email: `${PREFIX}-user-${i}@loadtest.local`,
    password: PASSWORD,
    // @Size(max = 30)
    nickname: `${PREFIX}u${i}`,
  };
}

async function request(method, pathname, { body, token } = {}) {
  const headers = { ...JSON_HEADERS };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`${BASE_URL}${pathname}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let parsed = null;
  const text = await res.text();
  if (text) {
    try {
      parsed = JSON.parse(text);
    } catch (_) {
      parsed = { raw: text };
    }
  }
  return { status: res.status, body: parsed, headers: res.headers };
}

async function postJson(pathname, body) {
  return request('POST', pathname, { body });
}

/** 201 Location 헤더의 마지막 세그먼트가 곧 생성된 리소스 id다 (바디에는 없다). */
function locationId(res) {
  const loc = res.headers.get('location');
  if (!loc) return null;
  const seg = loc.split('/').filter(Boolean).pop();
  return seg || null;
}

function fail(msg) {
  console.error(`\n[seed] ERROR: ${msg}\n`);
  process.exit(1);
}

// ---------------------------------------------------------------------------
// 1. 회원
// ---------------------------------------------------------------------------
/** Sign up, tolerating "already exists" in whatever shape the backend reports it. */
async function ensureSignedUp(user) {
  const res = await postJson('/api/v1/members', user);
  if (res.status === 200 || res.status === 201) return 'created';
  // MEMBER_DUPLICATE_EMAIL / MEMBER_DUPLICATE_NICKNAME etc. — the account is already
  // there, which is exactly what we want on a re-run. Login below is the real check.
  return 'existing';
}

async function login(user) {
  const res = await postJson('/api/v1/members/login', {
    email: user.email,
    password: user.password,
  });
  if (res.status === 200 && res.body && res.body.accessToken) return res.body.accessToken;
  return null;
}

async function seedOne(i) {
  const user = userFor(i);
  const signup = await ensureSignedUp(user);
  const token = await login(user);
  return { user, signup, ok: !!token };
}

async function runPool(indices, worker, concurrency) {
  const results = new Array(indices.length);
  let cursor = 0;
  const runners = Array.from({ length: Math.min(concurrency, indices.length) }, async () => {
    for (;;) {
      const slot = cursor++;
      if (slot >= indices.length) return;
      results[slot] = await worker(indices[slot]);
    }
  });
  await Promise.all(runners);
  return results;
}

// ---------------------------------------------------------------------------
// 2. 시계 오차 측정
// ---------------------------------------------------------------------------
/**
 * openAt은 서버가 @Future로 검증하고, 드롭의 OPEN 판정도 서버 시계로 한다. 우리 시계가
 * 앞서 있으면 "미래"라고 보낸 값이 서버에겐 과거라 400이 나고, 뒤처져 있으면 k6가 시작할
 * 때 아직 REGISTERED다. 눈감고 sleep하는 대신 실제 오차를 재서 openAt 계산에 반영한다.
 *
 * 측정: HTTP 응답의 Date 헤더(RFC 7231, 초 단위)를 요청 왕복의 중점과 비교한다.
 * skewMs > 0 이면 서버가 우리보다 앞서 있다. 해상도는 헤더가 초 단위라 ±1s가 한계.
 */
async function measureClockSkewMs() {
  const t0 = Date.now();
  const res = await request('GET', '/api/v1/drops?page=0&size=1');
  const t1 = Date.now();
  const dateHeader = res.headers.get('date');
  if (!dateHeader) return { skewMs: 0, measured: false };
  const serverMs = new Date(dateHeader).getTime();
  if (!Number.isFinite(serverMs)) return { skewMs: 0, measured: false };
  return { skewMs: serverMs - (t0 + t1) / 2, measured: true };
}

// ---------------------------------------------------------------------------
// 3. 판매자 + 상품 + 드롭
// ---------------------------------------------------------------------------
/**
 * 판매자 등록은 멱등하지 않다(businessNumber가 겹친다). 그래서 먼저 GET /api/v1/seller/me 로
 * 기존 건을 찾고, 없을 때만 POST 한다. 등록 성공 시 role이 즉시 ROLE_SELLER로 승격된다
 * (관리자 승인 단계는 없다).
 *
 * 응답 레코드는 SellerInfoResponse{id, businessNumber, storeName, active} — 필드명이
 * sellerInfoId가 아니라 id 다. scripts/smoke-drop-order.sh 가 `.sellerInfoId // .id` 로
 * 폴백을 두고 있는데, 실제로 값이 있는 쪽은 .id 다.
 */
async function ensureSeller(token) {
  const list = await request('GET', '/api/v1/seller/me', { token });
  if (list.status === 200 && Array.isArray(list.body) && list.body.length > 0) {
    const active = list.body.find((s) => s.active !== false) || list.body[0];
    const id = active.id || active.sellerInfoId;
    if (id) return { sellerInfoId: id, created: false };
  }

  const res = await request('POST', '/api/v1/seller/me', {
    token,
    body: {
      // @NotBlank @Size(max = 30). 계정과 1:1이라 결정적 값으로 둔다(재실행 시 위 GET에 잡힌다).
      businessNumber: `LT-${PREFIX}-000001`,
      storeName: `${PREFIX}-loadtest-store`,
    },
  });
  if (res.status !== 200 && res.status !== 201) {
    fail(
      `판매자 등록 실패: HTTP ${res.status} ${JSON.stringify(res.body)}\n` +
      `  businessNumber 중복이면 USER_PREFIX를 바꾸거나 SEED_DROP=0 으로 계정만 시딩할 것.`,
    );
  }
  const id = (res.body && (res.body.id || res.body.sellerInfoId)) || null;
  if (!id) fail(`판매자 등록 응답에 id가 없다: ${JSON.stringify(res.body)}`);
  return { sellerInfoId: id, created: true };
}

/**
 * scoped 토큰 TTL. member/src/main/resources/application.yml 의
 * jwt.scoped-token-expire-seconds = 120 (RFC 8693 단명 토큰).
 * 게이트웨이(SecurityConfig:189-192)는 /api/v1/products·/api/v1/drops 쓰기에
 * typ=scoped + aud=openat-product 토큰만 허용하고 일반 access 토큰은 명시적으로 거부한다.
 * 발급 직후 상품·드롭을 연달아 만들어야 한다.
 */
const SCOPED_TTL_S = 120;

async function issueScopedToken(token, sellerInfoId) {
  const res = await request('POST', '/api/v1/seller/token', {
    token,
    body: { sellerInfoId },
  });
  if (res.status !== 200 || !res.body || !res.body.accessToken) {
    fail(`scoped 토큰 발급 실패: HTTP ${res.status} ${JSON.stringify(res.body)}`);
  }
  return { token: res.body.accessToken, issuedAt: Date.now() };
}

/** 만료가 가까우면 재발급한다. 만료된 토큰의 401을 일반 인증오류로 오인하지 않게 하는 게 목적. */
async function freshScoped(scoped, token, sellerInfoId) {
  const ageS = (Date.now() - scoped.issuedAt) / 1000;
  if (ageS < SCOPED_TTL_S - 30) return scoped;
  console.log(`  scoped 토큰 ${Math.round(ageS)}s 경과 — 재발급`);
  return issueScopedToken(token, sellerInfoId);
}

/** 401/403이면 "만료냐 권한이냐"를 구분해서 알려준다. 일반적인 401 메시지로 끝내지 않는다. */
function assertNotScopedAuthFailure(res, what, scoped) {
  if (res.status !== 401 && res.status !== 403) return;
  const ageS = Math.round((Date.now() - scoped.issuedAt) / 1000);
  fail(
    `${what}이(가) HTTP ${res.status} 로 거부됐다. scoped 토큰 발급 후 ${ageS}초 경과.\n` +
    `  scoped 토큰 TTL은 ${SCOPED_TTL_S}초다 (member/src/main/resources/application.yml:39 ` +
    `jwt.scoped-token-expire-seconds).\n` +
    `  ${ageS >= SCOPED_TTL_S
      ? '=> 만료가 원인이다. 시딩을 다시 실행할 것(네트워크가 느리면 문제가 재발한다).'
      : '=> 만료는 아니다. typ=scoped / aud=openat-product 요건이나 판매자 권한을 확인할 것.'}`,
  );
}

async function createProduct(scoped, token, sellerInfoId) {
  const s = await freshScoped(scoped, token, sellerInfoId);
  const res = await request('POST', '/api/v1/products', {
    token: s.token,
    // 이미지 필드(thumbnailKey/imageKeys)는 전부 optional이다. S3는 안 엮여 있으므로 건드리지 않는다.
    body: { name: `${PREFIX} loadtest item`, description: 'loadtest', price: DROP_PRICE },
  });
  assertNotScopedAuthFailure(res, '상품 생성', s);
  if (res.status !== 201) {
    fail(`상품 생성 실패: HTTP ${res.status} ${JSON.stringify(res.body)}`);
  }
  const id = locationId(res);   // 201 + Location 헤더. 바디에 id가 없다.
  if (!id) fail('상품 생성이 201인데 Location 헤더가 없다.');
  return { productId: id, scoped: s };
}

async function createDrop(scoped, token, sellerInfoId, productId, skewMs) {
  const s = await freshScoped(scoped, token, sellerInfoId);
  // 서버 시계 기준으로 미래여야 한다(@Future). 로컬 now + 측정한 오차 + 여유.
  const openAtMs = Date.now() + skewMs + OPEN_DELAY_S * 1000;
  const openAt = new Date(openAtMs).toISOString();
  const body = { productId, dropPrice: DROP_PRICE, totalQuantity: TOTAL_QUANTITY, openAt };
  if (DROP_LIMIT_PER_USER) body.limitPerUser = DROP_LIMIT_PER_USER;
  if (CLOSE_AFTER_S) body.closeAt = new Date(openAtMs + CLOSE_AFTER_S * 1000).toISOString();

  const res = await request('POST', '/api/v1/drops', { token: s.token, body });
  assertNotScopedAuthFailure(res, '드롭 생성', s);
  if (res.status !== 201) {
    fail(
      `드롭 생성 실패: HTTP ${res.status} ${JSON.stringify(res.body)}\n` +
      `  openAt=${openAt} (시계 오차 보정 ${Math.round(skewMs)}ms 적용). ` +
      `@Future 위반이면 DROP_OPEN_DELAY_S를 늘릴 것.`,
    );
  }
  const id = locationId(res);
  if (!id) fail('드롭 생성이 201인데 Location 헤더가 없다.');
  return { dropId: id, openAt, scoped: s };
}

async function getDrop(dropId) {
  // GET /api/v1/drops/** 는 게이트웨이에서 permitAll — 토큰 불필요.
  const res = await request('GET', `/api/v1/drops/${dropId}`);
  return res.status === 200 ? res.body : null;
}

/**
 * OPEN이 될 때까지 폴링한다. 눈감은 sleep 대신 서버가 실제로 OPEN이라고 말할 때까지 기다리므로
 * 보정 후 남은 시계 오차도 여기서 흡수된다. 드롭 status는 별도 publish 단계 없이
 * 서버 시계 vs openAt/closeAt + 잔여 재고에서 파생된다.
 */
async function waitUntilOpen(dropId, budgetMs) {
  const deadline = Date.now() + budgetMs;
  let last = null;
  for (;;) {
    last = await getDrop(dropId);
    if (last && last.status === 'OPEN') return last;
    if (Date.now() >= deadline) return last;
    process.stdout.write('.');
    await new Promise((r) => setTimeout(r, 3000));
  }
}

/** 기존 target.json 이 아직 쓸 만한가 — 드롭 생성만 멱등하지 않으므로 여기서 멱등성을 만든다. */
async function reusableTarget() {
  if (FORCE_NEW_DROP || !fs.existsSync(TARGET_FILE)) return null;
  let prev;
  try {
    prev = JSON.parse(fs.readFileSync(TARGET_FILE, 'utf8'));
  } catch (_) {
    return null;
  }
  if (!prev || !prev.dropId) return null;
  if (prev.baseUrl && prev.baseUrl !== BASE_URL) return null;   // 대상이 바뀌었다
  const live = await getDrop(prev.dropId);
  if (!live) return null;
  if (live.status !== 'OPEN' && live.status !== 'REGISTERED') return null;
  // drop-flow.js setup()의 재고 기준(peak × QTY × 2)을 못 넘기면 새로 만드는 게 낫다.
  if (live.remainingQuantity < PEAK * QTY * 2) return null;
  return { prev, live };
}

async function seedDrop() {
  const reuse = await reusableTarget();
  if (reuse) {
    console.log(
      `기존 드롭 재사용: ${reuse.prev.dropId} (status=${reuse.live.status} ` +
      `remaining=${reuse.live.remainingQuantity}). 새로 만들려면 FORCE_NEW_DROP=1.`,
    );
    const target = { ...reuse.prev, reused: true, remainingAtSeed: reuse.live.remainingQuantity };
    fs.writeFileSync(TARGET_FILE, JSON.stringify(target, null, 2) + '\n');
    return target;
  }

  const seller = {
    email: `${PREFIX}-seller@loadtest.local`,
    password: PASSWORD,
    nickname: `${PREFIX}seller`,
  };
  await ensureSignedUp(seller);
  const token = await login(seller);
  if (!token) fail(`판매자 계정 로그인 실패: ${seller.email}`);

  const { sellerInfoId, created } = await ensureSeller(token);
  console.log(`판매자 ${created ? '등록' : '재사용'}: sellerInfoId=${sellerInfoId}`);

  const { skewMs, measured } = await measureClockSkewMs();
  if (measured) {
    const s = Math.round(skewMs);
    console.log(`서버 시계 오차: ${s > 0 ? '+' : ''}${s}ms (Date 헤더 기준, 해상도 ±1s)`);
    if (Math.abs(skewMs) > 5000) {
      console.warn('  ⚠ 오차가 5초를 넘는다. openAt 계산에 반영은 하지만 로컬 시계를 점검할 것.');
    }
  } else {
    console.warn('서버 Date 헤더를 못 읽어 시계 오차 보정 없이 진행한다.');
  }

  // scoped 토큰은 TTL 120초 — 여기서 발급해 상품·드롭을 즉시 연달아 만든다.
  let scoped = await issueScopedToken(token, sellerInfoId);
  const p = await createProduct(scoped, token, sellerInfoId);
  scoped = p.scoped;
  const d = await createDrop(scoped, token, sellerInfoId, p.productId, measured ? skewMs : 0);

  console.log(
    `드롭 생성: ${d.dropId} (openAt=${d.openAt}, totalQuantity=${TOTAL_QUANTITY}, ` +
    `dropPrice=${DROP_PRICE}${DROP_LIMIT_PER_USER ? `, limitPerUser=${DROP_LIMIT_PER_USER}` : ''})`,
  );

  process.stdout.write(`OPEN 대기(최대 ${OPEN_DELAY_S + 30}s)`);
  const live = await waitUntilOpen(d.dropId, (OPEN_DELAY_S + 30) * 1000);
  console.log('');
  if (!live || live.status !== 'OPEN') {
    fail(
      `드롭이 OPEN이 되지 않았다 (status=${live ? live.status : 'unknown'}).\n` +
      `  서버 시계가 로컬보다 많이 뒤처져 있으면 DROP_OPEN_DELAY_S를 늘릴 것.`,
    );
  }
  console.log(`드롭 OPEN 확인 (remaining=${live.remainingQuantity})`);

  const target = {
    baseUrl: BASE_URL,
    dropId: d.dropId,
    productId: p.productId,
    sellerInfoId,
    openAt: d.openAt,
    closeAt: CLOSE_AFTER_S
      ? new Date(Date.parse(d.openAt) + CLOSE_AFTER_S * 1000).toISOString()
      : null,
    totalQuantity: TOTAL_QUANTITY,
    dropPrice: DROP_PRICE,
    limitPerUser: DROP_LIMIT_PER_USER,
    // 사이징 근거를 남긴다 — 나중에 "왜 이 수량이었나"를 재구성할 수 있게.
    sizedFor: { profile: PROFILE, stages: STAGES, peakVUs: PEAK, estIterations: EST_ITERS, qty: QTY },
    remainingAtSeed: live.remainingQuantity,
    createdAt: new Date().toISOString(),
    reused: false,
  };
  fs.writeFileSync(TARGET_FILE, JSON.stringify(target, null, 2) + '\n');
  return target;
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------
(async () => {
  console.log(`seeding ${USER_COUNT} users against ${BASE_URL} (concurrency ${CONCURRENCY})`);
  if (SEED_DROP) {
    console.log(
      `드롭 사이징: profile=${PROFILE} peakVUs=${PEAK} 추정 iteration=${EST_ITERS} ` +
      `-> totalQuantity=${TOTAL_QUANTITY} (= ceil(iters × QTY(${QTY}) × 1.3), ` +
      `iteration당 ${EST_ITER_SEC}s 가정)`,
    );
  }

  const indices = Array.from({ length: USER_COUNT }, (_, i) => i);
  let results;
  try {
    results = await runPool(indices, seedOne, CONCURRENCY);
  } catch (e) {
    console.error(`seed failed: ${e.message}`);
    console.error('is the apigateway up on ' + BASE_URL + ' ?');
    process.exit(1);
  }

  const usable = results.filter((r) => r.ok).map((r) => ({
    email: r.user.email,
    password: r.user.password,
  }));
  const created = results.filter((r) => r.ok && r.signup === 'created').length;
  const failed = results.filter((r) => !r.ok);

  if (usable.length === 0) {
    console.error('no usable accounts — nothing written. Check the member service.');
    process.exit(1);
  }

  fs.writeFileSync(OUT_FILE, JSON.stringify(usable, null, 2) + '\n');
  console.log(`created=${created} reused=${usable.length - created} unusable=${failed.length}`);
  if (failed.length) {
    console.warn(`  first unusable: ${failed[0].user.email} (signup=${failed[0].signup}, login failed)`);
  }
  console.log(`wrote ${usable.length} users -> ${OUT_FILE}`);

  if (usable.length < PEAK) {
    console.warn(
      `⚠ 계정 ${usable.length}개 < peakVUs ${PEAK}. VU들이 계정을 공유하면 게이트웨이의 ` +
      `사용자별 confirm rate limit(replenish 2/s, burst 5)에 직렬화된다. ` +
      `USER_COUNT=${PEAK} 이상으로 다시 돌릴 것.`,
    );
  }

  if (!SEED_DROP) {
    console.log('SEED_DROP=0 — 드롭 시딩 생략. drop-flow.js 에 -e DROP_ID=<uuid> 를 직접 줄 것.');
    return;
  }

  await seedDrop();
  console.log(`wrote target -> ${TARGET_FILE}`);
  if (DROP_PRICE !== 10000) {
    console.warn(
      `⚠ DROP_PRICE=${DROP_PRICE} 가 WireMock toss-query-payment.json 스텁의 고정 ` +
      `totalAmount(10000)와 다르다. reconcile 경로에서 금액 불일치가 관측된다. ` +
      `스텁의 totalAmount/balanceAmount/cancelAmount를 ${DROP_PRICE}로 맞출 것.`,
    );
  }
  console.log(
    `\n다음 단계:\n  k6 run${PROFILE !== 'smoke' ? ` -e PROFILE=${PROFILE}` : ''} ` +
    `loadtest/k6/drop-flow.js\n  (DROP_ID는 target.json에서 자동으로 읽는다)\n`,
  );
})();

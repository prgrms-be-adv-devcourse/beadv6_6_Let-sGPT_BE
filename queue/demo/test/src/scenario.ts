/**
 * 대기열(queue) 시연용 브라우저 자동화 시나리오.
 *
 * 진행 순서:
 *   1) 격리된 브라우저 컨텍스트 10개 생성 (context당 유저 1명, 쿠키/localStorage 공유 없음)
 *   2) 전원 로그인 → "/" → "/drops/{DROP_ID}" 라우팅, 전원 로딩 완료까지 대기 (병렬)
 *   3) 각자 독립적으로 수량 "+"(aria-label="수량 증가")를 0~4회 랜덤 클릭 (병렬)
 *   4) 브라우저 순서대로(1번→10번) "주문하기" 클릭 → 그 유저의 결과(결제 화면 전환 또는
 *      대기열 모달 표시)를 확인한 뒤에야 다음 유저가 클릭 (완전 순차 — 줄을 선 것처럼 진행)
 *   5) 전원 처리가 끝난 뒤, 창을 10번→1번 역순으로 한 번씩 bringToFront() 해서 최종 화면
 *      정렬을 맞춘다(1번이 맨 마지막에 호출되어 맨 위로 남음) — 처리 도중 실시간으로 포커스를
 *      옮기는 방식은 OS의 포커스 도용 방지 정책 때문에 신뢰할 수 없어서, 모든 비동기 작업이
 *      끝난 뒤 한 번에 정리하는 방식으로 바꿨다.
 *
 * 실행 전 준비: queue/demo/setup-hot-drop.sh 를 수동으로 실행해 dropId를 발급받고,
 * src/config.ts의 DROP_ID(또는 DEMO_DROP_ID 환경변수)에 넣어둘 것.
 */
import { type Browser, type BrowserContext, chromium, type Page } from "playwright";
import { BASE_URL, DROP_ID, PASSWORD, USER_COUNT, assertConfigured, emailFor } from "./config.js";

type DemoUser = {
  index: number; // 0-based (브라우저/등장 순서)
  email: string;
  context: BrowserContext;
  page: Page;
};

function tag(user: DemoUser): string {
  return `[user${user.index + 1}:${user.email}]`;
}

function log(message: string): void {
  const time = new Date().toISOString().slice(11, 23);
  console.log(`${time} ${message}`);
}

/**
 * 유저 수만큼 격리된 BrowserContext를 만든다. Browser 프로세스는 하나만 띄우지만
 * newContext()는 매번 쿠키/localStorage/sessionStorage를 공유하지 않는 완전히 새 프로필이라
 * "브라우저 1명 = 유저 1명" 전제를 만족한다. headless:false 에서는 컨텍스트마다 별도의 OS
 * 창으로 뜨므로, 화면상으로도 브라우저 10개가 그대로 보인다.
 *
 * 창 쌓임(z-order) 정리는 여기서 하지 않는다 — 생성 도중 실시간으로 정리하려 해봤지만 OS의
 * 포커스 도용 방지 정책 때문에 신뢰할 수 없었다. 대신 시나리오 마지막(sortWindowsFront)에서
 * 한 번에 정리한다.
 */
async function launchUsers(browser: Browser): Promise<DemoUser[]> {
  const users: DemoUser[] = [];
  for (let index = 0; index < USER_COUNT; index += 1) {
    const context = await browser.newContext();
    const page = await context.newPage();
    users.push({ index, email: emailFor(index), context, page });
  }
  return users;
}

/**
 * 전원의 처리가 끝난 뒤, 창을 실행 순서(1번→10번)대로 다시 쌓는다.
 * 도중에 실시간으로(클릭 직전마다) bringToFront()를 거는 방식은 Windows의 포커스 도용 방지
 * 정책 때문에 신뢰할 수 없었다 — 그래서 모든 비동기 작업이 끝나 경쟁이 없어진 뒤, 가장
 * 나중(10번)부터 역순으로 하나씩 bringToFront()를 건다. 각 호출이 그 시점의 최상단이 되므로,
 * 맨 마지막에 호출되는 1번이 결국 맨 위에 남고 나머지는 실행 순서대로 그 아래 쌓인다.
 */
async function sortWindowsFront(users: DemoUser[]): Promise<void> {
  for (let i = users.length - 1; i >= 0; i -= 1) {
    await users[i]?.page.bringToFront();
  }
}

/** baseUrl 접속 → 로그인 버튼 → 이메일/비밀번호 입력 → 로그인 → "/drops/{DROP_ID}" 라우팅까지. */
async function loginAndOpenDrop(user: DemoUser): Promise<void> {
  const { page } = user;
  await page.goto(BASE_URL);
  await page.getByRole("link", { name: "로그인" }).click();
  await page.getByLabel("이메일").fill(user.email);
  await page.getByLabel("비밀번호").fill(PASSWORD);
  await page.getByRole("button", { name: "로그인" }).click();
  // 로그인 성공 시 LoginForm이 "/"로 navigate() 한다 — /login을 벗어난 것으로 성공을 확인한다.
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 15_000 });
  await page.goto(`${BASE_URL}/drops/${DROP_ID}`);
  // "수량 증가" 버튼은 드롭이 OPEN 상태일 때만 렌더링되므로, 이게 보이는 것으로
  // "페이지 로딩 완료 + 드롭 오픈 상태"를 한 번에 확인한다.
  await page
    .getByRole("button", { name: "수량 증가" })
    .waitFor({ state: "visible", timeout: 30_000 });
  log(`${tag(user)} 로그인 + 드롭 페이지 로딩 완료`);
}

/**
 * 수량 "+" 버튼을 0~4회 랜덤 클릭한다(수량 기본값 1 + 클릭 횟수).
 * "+"는 수량이 최대 구매 가능 수량(재고에 따라 5개 미만일 수 있음)에 도달하면 disabled
 * 되므로, 반복 시연으로 재고가 줄어든 상태에서 랜덤 횟수가 그 한도를 넘으면 클릭 전에
 * 멈춘다(그러지 않으면 Playwright가 버튼이 활성화되길 계속 기다리다 타임아웃난다).
 */
async function randomizeQuantity(user: DemoUser): Promise<void> {
  const clicks = Math.floor(Math.random() * 5); // 0, 1, 2, 3, 4
  const plusButton = user.page.getByRole("button", { name: "수량 증가" });
  let actualClicks = 0;
  for (let i = 0; i < clicks; i += 1) {
    if (await plusButton.isDisabled()) break;
    await plusButton.click();
    actualClicks += 1;
    await user.page.waitForTimeout(150 + Math.floor(Math.random() * 150));
  }
  log(`${tag(user)} 수량 +버튼 ${actualClicks}회 클릭 완료${actualClicks < clicks ? ` (재고 한도로 ${clicks}회 중 중단)` : ""}`);
}

async function submitOrder(user: DemoUser): Promise<void> {
  await user.page.getByRole("button", { name: "주문하기" }).click();
  log(`${tag(user)} 주문하기 클릭`);
}

/** 주문하기 클릭 후 결제(체크아웃) 화면으로 넘어갔는지, 대기열 모달이 떴는지 확인해 기록한다. */
async function observeOutcome(user: DemoUser): Promise<void> {
  const { page } = user;
  try {
    await Promise.race([
      page.waitForURL(/\/checkout\//, { timeout: 20_000 }),
      page.getByRole("dialog").waitFor({ state: "visible", timeout: 20_000 }),
    ]);
  } catch {
    log(`${tag(user)} 결제 화면/대기열 모달 어느 쪽도 20초 안에 확인되지 않음 — 수동 확인 필요`);
    return;
  }

  if (/\/checkout\//.test(page.url())) {
    log(`${tag(user)} → 결제 화면으로 즉시 이동 (${page.url()})`);
    return;
  }
  const dialogTitle = await page.getByRole("dialog").getByRole("heading").first().textContent();
  log(`${tag(user)} → 대기열 모달 진입 ("${dialogTitle?.trim() ?? "?"}")`);
}

async function main(): Promise<void> {
  assertConfigured();
  log(`데모 시작 — BASE_URL=${BASE_URL}, DROP_ID=${DROP_ID}, USER_COUNT=${USER_COUNT}`);

  const browser = await chromium.launch({ headless: false });
  process.on("SIGINT", async () => {
    log("Ctrl+C 감지 — 브라우저를 정리하고 종료합니다.");
    await browser.close();
    process.exit(0);
  });
  // 창을 전부(브라우저 X 버튼으로) 닫으면 Chromium 프로세스 자체가 종료되면서 이 이벤트가
  // 뜬다 — 그 시점에 스크립트도 알아서 끝낸다(안 하면 Node 프로세스만 멍하니 남아있음).
  browser.on("disconnected", () => {
    log("모든 브라우저 창이 닫혀 스크립트를 종료합니다.");
    process.exit(0);
  });

  const users = await launchUsers(browser);

  // 1) 전원 로그인 + 드롭 페이지 라우팅 — 병렬 진행, 전원 로딩 완료까지 대기.
  const loginResults = await Promise.allSettled(users.map((user) => loginAndOpenDrop(user)));
  const ready = users.filter((user, i) => {
    const result = loginResults[i];
    if (result?.status === "rejected") {
      log(`${tag(user)} 로그인/라우팅 실패: ${String(result.reason)}`);
      return false;
    }
    return true;
  });
  if (ready.length === 0) {
    log("로그인에 성공한 유저가 없어 데모를 중단합니다.");
    await browser.close();
    return;
  }

  // 2) 각자 독립적으로 수량 랜덤 클릭 (병렬).
  await Promise.allSettled(ready.map((user) => randomizeQuantity(user)));

  // 3) 브라우저 순서대로(순차) 주문하기 클릭 — 한 유저의 결과(결제 화면 전환 또는 대기열
  //    모달 표시)가 확인된 뒤에야 다음 유저가 클릭한다. 클릭만 빠르게 연타하면 "순서대로
  //    클릭"은 맞아도 응답이 뒤섞여 도착해 마치 줄을 선 것처럼 안 보이므로, 실제 대기열처럼
  //    한 명씩 순서대로 처리되는 모습을 보여주기 위해 응답까지 기다렸다가 다음으로 넘어간다.
  for (const user of ready) {
    await submitOrder(user);
    await observeOutcome(user);
  }

  // 4) 전원 처리 완료 — 이제야 창을 실행 순서대로 정렬한다(경쟁 없는 상태에서 한 번에).
  await sortWindowsFront(ready);

  log("시나리오 완료 — 브라우저 창은 시연을 위해 그대로 열어둡니다. 종료하려면 Ctrl+C.");
  // 발표자가 화면을 보여줄 시간을 벌기 위해 프로세스를 계속 살려둔다.
  await new Promise<void>(() => {});
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});

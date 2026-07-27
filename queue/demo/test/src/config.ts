/**
 * 데모 전역 설정 — 이 파일의 값만 바꾸면 시나리오 전체(로그인/라우팅/시뮬레이션)에 반영된다.
 *
 * BASE_URL과 DROP_ID는 환경변수로도 덮어쓸 수 있다(값을 코드에 직접 넣고 싶지 않을 때):
 *   DEMO_BASE_URL=http://localhost:5173 DEMO_DROP_ID=<uuid> npm run demo
 */

/** 프론트엔드(FE) 서버 주소. 바뀌면 여기(또는 DEMO_BASE_URL 환경변수)만 고치면 전체에 반영된다. */
export const BASE_URL = process.env.DEMO_BASE_URL ?? "http://localhost:5173";

/**
 * 대기열 시연용 드롭 ID.
 *
 * queue/demo/setup-hot-drop.sh 는 수동으로(여러 번, 직접 관리하며) 실행하는 것을 전제로 하므로
 * 여기서 자동 실행하지 않는다 — 스크립트를 실행해 나온 dropId를 아래 값에 직접 넣거나,
 * DEMO_DROP_ID 환경변수로 넘겨준다. 둘 다 없으면 시작하자마자 에러로 알려준다(자세한
 * 안내는 아래 assertConfigured 참고).
 */
export const DROP_ID = process.env.DEMO_DROP_ID ?? "ac112001-9f88-1747-819f-88a8864c0021";

/** 동시에 띄울 가상 유저(격리된 브라우저) 수 — "정해진 재고에 여러 명이 몰린다"는 대기열 시연 전제. */
export const USER_COUNT = 10;

/** member 모듈 init-data.sql 시드 계정 공통 비밀번호(test1~10@test.com 전용, queue 부하테스트용 시드). */
export const PASSWORD = "12341234";

/** userIndex(0-based, 브라우저 순서)에 대응하는 시드 계정 이메일 — 1번 브라우저가 test1, 2번이 test2 순. */
export function emailFor(userIndex: number): string {
  return `test${userIndex + 1}@test.com`;
}

/** DROP_ID를 안 채우고 실행하면 대기열이 아닌 엉뚱한(또는 존재하지 않는) 드롭을 열게 되므로, 시작 전에 막는다. */
export function assertConfigured(): void {
  if (!DROP_ID || DROP_ID === "REPLACE_WITH_DROP_ID") {
    throw new Error(
      "[config] DROP_ID가 설정되지 않았습니다. queue/demo/setup-hot-drop.sh 실행 후 출력된 " +
        "dropId를 src/config.ts의 DROP_ID 값에 직접 넣거나, DEMO_DROP_ID 환경변수로 넘겨주세요.",
    );
  }
}

# FE_API_REQUESTS — 도메인별 API 구현 필요 항목

> FE 화면 구현에 필요하나 BE 에 **아직 구현이 남은 API** 만 정리. 응답 shape 계약은 [`FE_CONTRACT.md`](./FE_CONTRACT.md).
> 게이트웨이 라우팅·보안(`/api/v1`)은 반영 완료. 갱신: 2026-07-15.

| 도메인 | 메서드·경로 | 인증 | 응답 | 용도 |
|---|---|---|---|---|
| payment | `GET /api/v1/wallet` | 회원 JWT | `{ balance: long }` | 마이페이지 잔액 표기 |

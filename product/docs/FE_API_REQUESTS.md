# FE_API_REQUESTS — 도메인별 API 구현 필요 항목

> FE 화면 구현에 필요하지만 BE에 아직 없는 API만 기록한다. 응답 shape 계약은
> [`FE_CONTRACT.md`](./FE_CONTRACT.md)를 따른다.
> 갱신 기준: 2026-07-27 `dev`.

현재 코드와 Gateway 라우팅을 기준으로 확인된 미구현 API는 없다.

기존 요청이었던 `GET /api/v1/wallet`은 `WalletController`에 구현됐으며, 회원 JWT 기준으로
`{ "balance": long }`을 반환한다. 이후 새 누락이 확인되면 이 문서에 추가한다.

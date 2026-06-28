# FE_API_REQUESTS — 프론트가 필요로 하는 BE API 현황 (전 도메인)

> openAt FE(React 19, MSW provisional)가 화면 구현에 필요로 하는 **조회·발급·필드** 요청을 도메인별로 추적.
> 응답 shape 계약은 [`FE_CONTRACT.md`](./FE_CONTRACT.md) — 이 문서는 "무엇이 구현됐고 / 무엇이 남았는지".
> 각 미구현 항목은 관련 컨트롤러/ApiSpec 에 `// TODO(fe-api)` 마커로도 표시. **갱신: 2026-06-28(BE 코드 기준).**

## ✅ 구현 완료

| 도메인 | METHOD 경로 | 응답 | 위치 |
|---|---|---|---|
| product | `GET /api/v1/drops?status&categoryId&keyword&sort&page&size` | `PageResponse<DropResponse>` | `DropController.searchDrops` |
| product | `GET /api/v1/drops/{dropId}` | `DropResponse` | `DropController.getDrop` |
| product | `GET /api/v1/drops/me?...` | `PageResponse<DropResponse>`(본인 스토어) | `DropController.searchMyDrops` |
| product | `GET /api/v1/products/me?categoryId&keyword&sort&page&size` | `PageResponse<ProductResponse>`(본인 스토어) | `ProductController.searchMyProducts` |
| product | `GET /api/v1/categories` | `List<CategoryResponse>{ id, name }`(이름순) | `CategoryController.getCategories` |
| product | `ProductResponse`·`DropResponse` 에 **`sellerName`**(판매자 표시명, null=미연동) | 카탈로그/상세 벤더 표기 | `ProductResponse`·`DropResponse` |
| product | 상품 다중 이미지 **`imageKeys`**(갤러리) + **이미지 업로드/조회** | `ProductCreate/Update/Response.imageKeys`; `POST /api/v1/products/images`(멀티파트 `file` → `{key,url}`), `GET /api/v1/products/images/{key}` | `ProductImageController` (로컬 저장, 파이널 S3) |

## ⏳ 미구현 — FE 가 필요로 함

| 도메인 | 요청 | 응답(FE 기대) | 마커 위치 | 화면 |
|---|---|---|---|---|
| **member** | `POST /api/v1/auth/seller-token`(회원 JWT 인증) | `{ tokenType, accessToken, expiresIn }` — 스토어(`sellerInfoId`) 범위 판매자 JWT | `member/.../TokenExchangeController` | 12·13·14 |
| **payment** | `GET /api/v1/wallet`(인증 회원) | `{ balance: long }` | `payment/.../WalletChargeController` | 10 |

## 🔄 인증 모델 변경 (`sellerId` = 스토어 `sellerInfoId`)

FE 판매자 인증이 **회원 단위 → 스토어(`sellerInfoId`) 단위**로 바뀜(회원 1:N 스토어, 활성 스토어 전환 시 판매자 토큰 재발급). 영향:

- **member**: (구) RFC8693 `POST /auth/token` form 교환 → (신) `POST /api/v1/auth/seller-token { sellerInfoId } → { tokenType, accessToken, expiresIn }` 신설. 짧은 수명 OK. (구) 교환 폐기 여부는 BE 판단.
- **gateway**: 판매자 토큰의 스토어 스코프를 검증해 product 도메인에 `@CurrentUser = sellerInfoId` 로 주입.
- **product**: `@CurrentUser UUID sellerId`(현재 회원 X-User-Id 스텁)를 활성 스토어 `sellerInfoId` 기준으로 — 상품/드롭 write(create/update/delete)·`/me` 소유·필터, `ProductResponse.sellerId == sellerInfoId`.
- 배경 상세: FE `docs/auth.md`. 마커: `ProductApiSpec`·`DropApiSpec` 의 `NOTE(auth)`.

## 🧩 미해결 결정

- `thumbnailKey`/`imageKeys` URL 전략: **세미 단계 결정됨** — BE 가 객체 키 + `GET /api/v1/products/images/{key}` 조회 경로 제공, FE 는 키를 `{API_BASE}/api/v1/products/images/{key}` 로 렌더(`resolveImageSrc`). 파이널 배포 시 S3/CDN URL 로 전환(`// TODO(final)`).

## 📍 마커 위치 요약

| 도메인 | 파일 | 마커 |
|---|---|---|
| member | `member/.../presentation/controller/TokenExchangeController.java` | seller-token 재발급(미구현) |
| payment | `payment/.../presentation/controller/WalletChargeController.java` | wallet 잔액 조회(미구현) |
| product | `product/.../product/.../ProductApiSpec.java` | 인증 스코프 `NOTE(auth)` (storeName·갤러리는 구현 완료) |
| product | `product/.../drop/.../DropApiSpec.java` | 인증 스코프 `NOTE(auth)` |

> 출처 목 데이터: FE `src/mocks/data/*`, provisional 호출부: FE 각 `features/*/api/*`.

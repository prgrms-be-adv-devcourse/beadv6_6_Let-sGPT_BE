# FE_CONTRACT — FE가 기대하는 응답 계약 (BE 참고용)

> openAt FE(React 19, MSW provisional)의 디자인·구현이 어느 정도 완료된 시점 기준으로,
> **BE가 응답에서 맞춰줘야 할 계약(필드 shape)**을 정리한 참고 문서.
> source of truth는 FE 레포의 `docs/be-api-contract.md` + 각 feature의 zod 스키마(`*/model/*.schema.ts`)다.
> 미구현 API 목록은 [`FE_API_REQUESTS.md`](./FE_API_REQUESTS.md)에 별도 정리(이 문서는 "어떤 모양으로 줄지", 그쪽은 "무엇이 없는지").
> 담당 도메인(**product/drop/category**)은 필드 단위 상세, 그 외 도메인은 §4 참고용 요약.

---

## 0. 공통 규약

| 항목 | 계약 |
|---|---|
| 게이트웨이 / prefix | `http://localhost:8000`, 경로는 `/api/v1/{도메인복수}` |
| 페이지 응답 | `PageResponse { content[], page, size, totalElements, totalPages }` — FE `pageResponseSchema`와 1:1 일치 (BE `PageResponse.of`와 동일) |
| 페이지/정렬 파라미터 | `page`, `size`, `sort` 쿼리 (Spring `Pageable`). FE가 `sort`도 전달 |
| 날짜·시각 | ISO-8601 문자열 ↔ `Instant` (예: `2026-06-27T03:00:00Z`) |
| 인증 헤더 | 보호 엔드포인트에 `Authorization: Bearer <accessToken>` (FE가 자동 주입) |
| 멱등 헤더 | 쓰기(주문·결제·환불·충전)에 `Idempotency-Key` |
| 판매자 토큰 | 상품/드롭 **쓰기**는 **스토어(`sellerInfoId`) 범위 판매자 토큰**(회원 토큰과 별도) — 발급: member 도메인 `POST /api/v1/auth/seller-token { sellerInfoId } → { tokenType, accessToken, expiresIn }`, 활성 스토어 전환 시 재발급. ((구) RFC8693 `/auth/token` 교환 폐기) 상세 FE `docs/auth.md` |
| 에러 | FE는 `ApiError { status, code, message }`로 파싱 — BE `ErrorResponse { code, message }` + HTTP status와 매핑 |

---

## 1. PRODUCT (담당)

### 1.1 기대 응답 — `ProductResponse` (목록·상세 동일 shape)

| 필드 | 타입 | nullable | BE 현재(`ProductResponse`) | 비고 |
|---|---|---|---|---|
| `id` | UUID | N | ✅ | |
| `sellerId` | UUID | N | ✅ | 판매자 인증 변경 후 **스토어 `sellerInfoId`** 의미(§5·5) → `sellerName` 출처와 정합 |
| `sellerName` | string | Y | ❌ **없음** | 카탈로그/상세 벤더 표기. FE는 `nullish`로 두고 MSW로 임시 채움 → BE 추가 시 N+1 회피(조인/배치) |
| `name` | string | N | ✅ | |
| `description` | string | N | ✅ | 카드엔 미표시, 상세·폼에서 사용 |
| `categoryId` | UUID | Y | ✅ | null = 미분류 |
| `categoryName` | string | Y | ✅ | 카드 표기는 이름만 사용 |
| `price` | long | Y | ✅ | null = "가격 미정" |
| `thumbnailKey` | string | Y | ✅ | ⚠ FE는 **이미지 URL로 직접 사용**(mock = picsum URL). key→URL 노출 전략 합의 필요 |
| `createdAt` | Instant | N | ✅ | 기본 정렬(최신순) 근거 |

**Divergence 2건**: ① `sellerName` 부재, ② `thumbnailKey`를 FE가 URL로 직접 렌더 → 둘 다 §5 액션.

### 1.2 목록/검색 — `GET /api/v1/products?categoryId&keyword&sort&page&size`

- BE `ProductSearchRequest` = `categoryId`, `keyword` (+ `Pageable`). FE 쿼리와 정합. `sort`는 `Pageable`로 흡수.

### 1.3 쓰기 바디 (FE → BE)

```
ProductWriteBody { name, description?, categoryId?, price?, thumbnailKey? }
```

### 1.4 mock 응답 예시 (FE 기준)

```json
{
  "id": "p1",
  "sellerId": "11111111-1111-1111-1111-111111111111",
  "sellerName": "오픈앳 스튜디오",
  "name": "오버사이즈 후디 차콜",
  "description": "한정 수량으로 만나는 openAt 단독 상품.",
  "categoryId": "c-apparel",
  "categoryName": "의류",
  "price": 39000,
  "thumbnailKey": "https://picsum.photos/seed/openat-1/640/800",
  "createdAt": "2026-06-20T09:00:00Z"
}
```

---

## 2. DROP (담당)

### 2.1 기대 응답 — `DropResponse` (목록 카드·상세 동일, FE `dropCardSchema`)

| 필드 | 타입 | nullable | 비고 |
|---|---|---|---|
| `id` | UUID | N | ⚠ FE는 **`id`** (`dropId` 아님) |
| `productId` | UUID | N | |
| `productName` | string | N | product에서 끌어옴 |
| `sellerName` | string | Y | ❌ BE 없음 (§5) |
| `categoryId` | UUID | Y | product 기준 |
| `categoryName` | string | Y | product 기준 |
| `thumbnailKey` | string | Y | product 기준, URL 직접 사용 |
| `dropPrice` | long | N | |
| `totalQuantity` | int | N | |
| `remainingQuantity` | int | N | ⚠ FE는 **`remainingQuantity`** (`remaining` 아님). Redis 게이트키퍼 파생 |
| `status` | enum | N | `REGISTERED \| OPEN \| CLOSE \| SOLD_OUT` — **OPEN/SOLD_OUT은 런타임 파생** |
| `openAt` | Instant | N | |
| `closeAt` | Instant | Y | |

> `limitPerUser`는 **FE 응답 스키마에 없음**(생성 바디에만 존재). 상세에서도 미사용 → 응답에 넣으려면 별도 합의.

### 2.2 status 표시 매핑 (FE UI)

| status | 라벨 | 표시 | 동작 |
|---|---|---|---|
| `REGISTERED` | 오픈 예정 | `openAt` 카운트다운, 재고바 없음 | 구매 불가 |
| `OPEN` | 진행중(라이브 점) | `remainingQuantity/totalQuantity` 재고바 | 구매 가능 |
| `CLOSE` | 종료 | 재고바(회색) | 구매 불가 |
| `SOLD_OUT` | 매진 | 재고바(회색) | 구매 불가 |

> BE는 `REGISTERED`/`CLOSE`만 영속, `OPEN`/`SOLD_OUT`은 `openAt`/현재시각 + `remainingQuantity`로 파생해 응답한다는 전제(FE도 동일 가정).

### 2.3 엔드포인트

- `GET /api/v1/drops?status&categoryId&keyword&sort&page&size` → `PageResponse<DropResponse>` (**구현 완료** — `DropController.searchDrops`)
- `GET /api/v1/drops/{id}` → `DropResponse` (**구현 완료** — `DropController.getDrop`)
- `GET /api/v1/drops/me?status&categoryId&keyword&sort&page&size` → `PageResponse<DropResponse>` (**구현 완료** — `DropController.searchMyDrops`, 판매자 콘솔)
- 쓰기 바디: `DropCreateBody { productId, dropPrice, totalQuantity, limitPerUser?, openAt, closeAt? }`

### 2.4 mock 응답 예시

```json
{
  "id": "d1",
  "productId": "p12",
  "productName": "한정판 러너 SS26",
  "sellerName": "노드 아틀리에",
  "categoryId": "c-apparel",
  "categoryName": "의류",
  "thumbnailKey": "https://picsum.photos/seed/openat-12/640/800",
  "dropPrice": 219000,
  "totalQuantity": 100,
  "remainingQuantity": 37,
  "status": "OPEN",
  "openAt": "2026-06-27T03:00:00Z",
  "closeAt": null
}
```

---

## 3. CATEGORY (담당)

- `GET /api/v1/categories` → `List<CategoryResponse> { id, name }` (**구현 완료** — `CategoryController.getCategories`, 이름순)
- 쓰기는 ADMIN: `POST`(201+Location), `PATCH /{id}`(204), `DELETE /{id}`(204).
- FE seed 예시: `의류·액세서리·문구·전자기기·피규어·기타` (`id` = `c-apparel` 등 slug, 단 BE는 UUID 발급 — FE는 id를 불투명 문자열로만 사용하므로 무방).

```json
{ "id": "c-apparel", "name": "의류" }
```

---

## 4. 타 도메인 요약 (참고용 — 담당 팀원 확인 필요)

> 아래는 FE가 기대하는 응답의 **필드명 요약**일 뿐, source of truth는 FE `docs/be-api-contract.md`다.
> 해당 도메인 담당이 직접 검증해야 한다. (필드 타입·nullable 디테일은 원문 참조)

**Order** (`/api/v1/orders`)
- 생성 응답: `{ orderId, orderNumber, status(PAYMENT_PENDING), amount, orderName, paymentExpiresAt }`
- 상세: `{ orderId, orderNumber, dropId, productId, productName, quantity, totalPrice, status, paymentId?, paymentExpiresAt, failCode?, createdAt }`
- 목록: `PageResponse<OrderSummary>` (상세에서 `paymentId/paymentExpiresAt/failCode` 제외)
- 생성 바디에 `idempotencyKey` 포함, `status` 8종 / `failCode` 다수

**Payment / Refund / Wallet** (`/api/v1/payments`, `/refunds`, `/wallet`)
- `PaymentResponse { paymentId, status, paymentKey? }`
- `RefundResponse { refundId, paymentId, amount, status }`
- `WalletChargeResponse { chargeId, status }`, `GET /wallet → { balance }` (**미구현**)
- 모든 쓰기에 `Idempotency-Key` 헤더

**Settlement** (`/api/v1/settlements/{seller|admin}/...`)
- `GET .../orders` → `PageResponse<SettlementOrderSummary>` (paymentId, orderId, sellerId, buyerId, productId, settlementMonth(yyyyMM), 금액 필드들, status)
- `GET .../sellers` → `PageResponse<SellerSettlementSummary>` (월·판매자 집계)
- `POST admin/retry-failed → { retriedCount }`

**Member / Seller** (`/api/v1/members`, `/api/v1/seller/me`)
- `Member { id, email, nickname, role, platformType }`, 로그인 `TokenResponse { tokenType, accessToken, refreshToken, expiresIn }`
- `SellerInfo { id, businessNumber, storeName, active }` — **product/drop의 `sellerName`은 여기 `storeName`이 출처**

---

## 5. BE 액션 (담당 도메인 정리)

1. **`DropResponse` 신설 시 FE 필드명 그대로** — `id`(not `dropId`), `remainingQuantity`(not `remaining`). 안 맞추면 codegen 후 FE rename 발생.
2. **`sellerName` 노출 여부 결정** — product/drop 응답에 추가할지. 추가 시 member `SellerInfo.storeName`을 어떻게 끌어올지(도메인 경계·N+1) 합의.
3. **`thumbnailKey` URL 전략 합의** — 응답을 key로 줄지 / presigned·CDN URL로 줄지 (product·drop 공통).
4. **조회 API 현황** — `/drops`·`/drops/{id}`·`/drops/me`·`/products/me`·`/categories` **구현 완료**. 남은 미구현은 `/wallet`(결제 도메인)뿐 → [`FE_API_REQUESTS.md`](./FE_API_REQUESTS.md).
5. **`sellerId` = 스토어 `sellerInfoId`** — 판매자 인증이 스토어 단위로 변경(회원 1:N 스토어). 상품/드롭 write·`/me`의 소유·필터와 `ProductResponse.sellerId`를 활성 스토어 `sellerInfoId` 기준으로(게이트웨이가 판매자 토큰 스코프 주입). [`FE_API_REQUESTS.md` 인증 모델 변경 절]

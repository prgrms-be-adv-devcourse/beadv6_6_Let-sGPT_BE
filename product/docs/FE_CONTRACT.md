# FE_CONTRACT — FE가 기대하는 응답 계약 (BE 참고용)

> openAt FE(React 19, MSW provisional)의 디자인·구현이 어느 정도 완료된 시점 기준으로,
> **BE가 응답에서 맞춰줘야 할 계약(필드 shape)**을 정리한 참고 문서.
> source of truth는 FE 레포의 `docs/be-api-contract.md` + 각 feature의 zod 스키마(`*/model/*.schema.ts`)다.
> BE 구현 현황은 2026-07-27 `dev` 기준이다. 현재 확인된 미구현 API는
> [`FE_API_REQUESTS.md`](./FE_API_REQUESTS.md)에 없다.
> 담당 도메인(**product/drop/category**)은 필드 단위 상세, 그 외 도메인은 §4 참고용 요약.

---

## 0. 공통 규약

| 항목 | 계약 |
|---|---|
| 게이트웨이 / prefix | `http://localhost:8000`, 경로는 `/api/v1/{도메인복수}` |
| 페이지 응답 | `PageResponse { content[], page, size, totalElements, totalPages }` — FE `pageResponseSchema`와 1:1 일치 (BE `PageResponse.of`와 동일) |
| 페이지/정렬 파라미터 | `page`, `size`, `sort` 쿼리를 Spring `Pageable`이 파싱한다. 다만 현재 product/drop 조회 어댑터는 요청 정렬을 적용하지 않고 각각 고정 순서를 사용한다 |
| 날짜·시각 | ISO-8601 문자열 ↔ `Instant` (예: `2026-06-27T03:00:00Z`) |
| 인증 헤더 | 보호 엔드포인트에 `Authorization: Bearer <accessToken>` (FE가 자동 주입) |
| 멱등 계약 | 주문 생성은 body `idempotencyKey`; 지갑 결제·환불·충전은 `Idempotency-Key` 헤더. PG 결제 confirm은 `orderId` 유니크 예약으로 멱등 처리 |
| 판매자 토큰 | 상품/드롭 **쓰기**는 **스토어(`sellerInfoId`) 범위 판매자 토큰**(회원 토큰과 별도) — 발급: member 도메인 `POST /api/v1/seller/token { sellerInfoId } → { tokenType, accessToken, expiresIn }`, 활성 스토어 전환 시 재발급. 상세 FE `docs/auth.md` |
| 에러 | BE는 `ErrorResponse { error, message }`를 반환. FE `ApiError.code`는 BE의 `error` 필드, `status`는 HTTP 상태와 매핑 |

---

## 1. PRODUCT (담당)

### 1.1 기대 응답 — `ProductResponse` (목록·상세 동일 shape)

| 필드 | 타입 | nullable | BE 현재(`ProductResponse`) | 비고 |
|---|---|---|---|---|
| `id` | UUID | N | ✅ | |
| `sellerId` | UUID | N | ✅ | 판매자 인증 변경 후 **스토어 `sellerInfoId`** 의미(§5·5) → `sellerName` 출처와 정합 |
| `sellerName` | string | Y | ✅ | member 스토어 이벤트를 소비한 로컬 `SellerStore` 투영에서 조회 |
| `name` | string | N | ✅ | |
| `description` | string | Y | ✅ | 등록·수정 body에서 선택값. 카드엔 미표시, 상세·폼에서 사용 |
| `categoryId` | UUID | Y | ✅ | null = 미분류 |
| `categoryName` | string | Y | ✅ | 카드 표기는 이름만 사용 |
| `price` | long | Y | ✅ | null = "가격 미정" |
| `thumbnailKey` | string | Y | ✅ | final key. FE `resolveImageSrc`가 이미지 조회 API URL로 변환 |
| `imageKeys` | string[] | N | ✅ | 추가 이미지 final key 목록. 없으면 빈 목록 |
| `createdAt` | Instant | N | ✅ | 기본 정렬(최신순) 근거 |

현재 BE 응답은 판매자 표시명과 이미지 갤러리를 포함한다. seed/mock의 풀 URL은 FE에서
그대로 쓰고, 저장소 key는 §1.5 조회 경로로 해석한다.

### 1.2 목록/검색 — `GET /api/v1/products?categoryId&keyword&sort&page&size`

- BE `ProductSearchRequest` = `categoryId`, `keyword` (+ `Pageable`). `page`·`size`는 적용되지만
  `ProductRepositoryAdaptor`가 `createdAt desc`를 고정하므로 현재 `sort` 값은 결과 순서에
  반영되지 않는다.

### 1.3 쓰기 바디 (FE → BE)

```
ProductWriteBody { name, description?, categoryId?, price?, thumbnailKey?, imageKeys? }
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
  "imageKeys": [
    "https://picsum.photos/seed/openat-1-detail-1/960/960"
  ],
  "createdAt": "2026-06-20T09:00:00Z"
}
```

### 1.5 이미지 업로드 — `POST /api/v1/products/images/presign` (**구현 완료**)

1. FE가 MIME 타입으로 업로드 URL을 요청한다. 허용 타입은 `image/jpeg`·`image/png`·`image/webp`다. staging 키 확장자는 MIME 타입에 따라 각각 `jpg`·`png`·`webp`로 결정된다.

   ```json
   { "contentType": "image/png" }
   ```

2. BE는 `200`과 staging 키·presigned PUT URL·만료 시각을 반환한다. 서명 유효시간은 10분이다.

   ```json
   {
     "stagingKey": "staging/550e8400-e29b-41d4-a716-446655440000.png",
     "uploadUrl": "http://localhost:9000/openat-images/images/staging/...",
     "expiresAt": "2026-07-20T03:10:00Z"
   }
   ```

3. FE가 `uploadUrl`에 원본 파일 바이트를 `PUT`한다. `Content-Type`은 presign 요청과 같아야 한다.
4. 상품 등록·수정 바디의 `thumbnailKey`·`imageKeys`에는 `stagingKey`를 넣는다. BE는 승격 전에 업로드된 실제 객체를 검증한다 — 크기(최대 5MB), 저장된 콘텐츠 타입이 허용 목록·키 확장자와 일치하는지, 앞부분 바이트가 해당 이미지 포맷의 시그니처인지. 통과하면 단일 버킷의 final prefix로 승격해 prefix 없는 final 키를 저장하고, 실패하면 `PRODUCT_IMAGE_INVALID`(400)를 반환한다. 수정 시 기존 final 키를 다시 보내도 그대로 유지된다.

- staging 키는 승격 전 조회 경로에서 읽을 수 없으므로 업로드 직후 미리보기는 FE의 blob URL을 사용한다.
- 조회 계약은 `GET /api/v1/products/images/{key}` 경로를 유지하되, 응답은 이미지 바이트가 아니라 presigned GET URL로의 `302` 리다이렉트다. `<img src>`는 리다이렉트를 자동으로 따라가므로 FE 변경은 없다. final 키 형식이 아니면 BE가 `PRODUCT_IMAGE_INVALID`(400)를 반환한다. 형식은 맞지만 객체가 없으면 BE를 거치지 않고 스토리지가 직접 오류를 응답하며, 운영 IAM은 ListBucket을 부여하지 않으므로 404가 아니라 403이 온다.

---

## 2. DROP (담당)

### 2.1 기대 응답 — `DropResponse` (목록 카드·상세 동일, FE `dropCardSchema`)

| 필드 | 타입 | nullable | 비고 |
|---|---|---|---|
| `id` | UUID | N | ⚠ FE는 **`id`** (`dropId` 아님) |
| `productId` | UUID | N | |
| `productName` | string | N | product에서 끌어옴 |
| `sellerName` | string | Y | member 스토어 이벤트의 로컬 투영에서 조회 |
| `categoryId` | UUID | Y | product 기준 |
| `categoryName` | string | Y | product 기준 |
| `thumbnailKey` | string | Y | product 기준, final key는 이미지 조회 API로 해석 |
| `dropPrice` | long | N | |
| `totalQuantity` | int | N | |
| `remainingQuantity` | int | N | ⚠ FE는 **`remainingQuantity`** (`remaining` 아님). Redis 게이트키퍼 파생 |
| `status` | enum | N | `REGISTERED \| OPEN \| CLOSE \| SOLD_OUT` — **OPEN/SOLD_OUT은 런타임 파생** |
| `openAt` | Instant | N | |
| `closeAt` | Instant | Y | |
| `limitPerUser` | int | Y | 1인 구매 한도, null = 무제한 |

`sellerName`과 `limitPerUser`는 현재 `DropResponse`에 포함된다. FE zod 스키마도 두 필드를
허용해야 생성 바디와 상세 화면의 구매 한도를 같은 값으로 유지할 수 있다.

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

목록의 `page`·`size`는 적용되지만 `DropRepositoryAdaptor`가 `openAt desc`를 고정하므로
현재 `sort` 값은 결과 순서에 반영되지 않는다.

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
  "closeAt": null,
  "limitPerUser": 2
}
```

---

## 3. CATEGORY (담당)

- `GET /api/v1/categories` → `List<CategoryResponse> { id, name }` (**구현 완료** — `CategoryController.getCategories`, 이름순)
- 쓰기: `POST`(201+Location), `PATCH /{id}`(204), `DELETE /{id}`(204). 현재 Gateway에서는
  일반 access JWT 인증만 요구하고 역할 제한은 두지 않는다. ADMIN 전용이 제품 의도라면
  문서가 아니라 Gateway 인가 구현을 보강해야 한다.
- FE seed 예시: `의류·액세서리·문구·전자기기·피규어·기타` (`id` = `c-apparel` 등 slug, 단 BE는 UUID 발급 — FE는 id를 불투명 문자열로만 사용하므로 무방).

```json
{ "id": "c-apparel", "name": "의류" }
```

---

## 4. 타 도메인 요약 (참고용 — 담당 팀원 확인 필요)

> 아래는 FE가 기대하는 응답의 **필드명 요약**일 뿐, source of truth는 FE `docs/be-api-contract.md`다.
> 해당 도메인 담당이 직접 검증해야 한다. (필드 타입·nullable 디테일은 원문 참조)

**Order** (`/api/v1/orders`)
- 생성 응답: `{ orderId, orderNumber, status(PAYMENT_PENDING), amount, orderName, paymentExpiresAt, created }`
- 상세: `{ orderId, orderNumber, dropId, productId, productName, quantity, totalPrice, status, paymentId?, paymentExpiresAt, failCode?, createdAt }`
- 목록: `PageResponse<OrderSummary>` (상세에서 `paymentId/paymentExpiresAt/failCode` 제외)
- 생성 바디에 `idempotencyKey` 포함, `status` 8종 / `failCode` 다수

**Payment / Refund / Wallet** (`/api/v1/payments`, `/refunds`, `/wallet`)
- `PaymentResponse { paymentId, status, paymentKey? }`
- `RefundResponse { refundId, paymentId, amount, status }`
- `WalletChargeResponse { chargeId, status }`, `GET /wallet → { balance }` (**구현 완료**)
- `POST /payments`는 WALLET 전용, PG는 토스 SDK 승인 뒤 `POST /payments/confirm` 단일 진입점
- WALLET 결제·환불·충전은 `Idempotency-Key` 헤더를 사용하고 PG 결제 confirm은 헤더를 받지 않는다.

**Settlement** (`/api/v1/settlements/{seller|admin}/...`)
- `GET .../orders` → `PageResponse<SettlementOrderSummary>` (paymentId, orderId, sellerId, buyerId, productId, settlementMonth(yyyyMM), 금액 필드들, status)
- `GET .../sellers` → `PageResponse<SellerSettlementSummary>` (월·판매자 집계)
- `POST admin/retry-failed?settlementMonth=yyyyMM → { batchId, settlementMonth, retriedSellerCount, status, failReason }`
- 현재 Gateway는 관리자 GET에만 ADMIN을 강제하고 관리자 POST는 일반 access JWT만 요구한다.
  판매자 GET도 SELLER 역할만 확인하며 controller가 로그인 판매자의 sellerInfoId로 결과를
  제한하지 않는다. 통합 테스트에서는 정상 응답뿐 아니라 이 현행 인가 범위를 결함으로 기록한다.

**Member / Seller** (`/api/v1/members`, `/api/v1/seller/me`)
- `Member { id, email, nickname, role, platformType }`, 로그인 `TokenResponse { tokenType, accessToken, refreshToken, expiresIn }`
- `SellerInfo { id, businessNumber, storeName, active }` — **product/drop의 `sellerName`은 여기 `storeName`이 출처**

---

## 5. 현재 구현과 연동 확인

1. **`DropResponse` 필드명 정합** — `id`(not `dropId`), `remainingQuantity`(not `remaining`)로 구현됐다.
2. **`sellerName` 구현 완료** — member의 스토어 이벤트를 product가 `SellerStore`로 투영하고 product/drop 응답에 노출한다.
3. **`thumbnailKey` URL 전략** — 결정: 신규 이미지는 presigned PUT으로 staging에 직접 업로드하고, 상품 등록·수정 시 BE가 final로 승격한다. 상품 응답은 **final key**를 주며 FE `resolveImageSrc`가 `GET /api/v1/products/images/{key}`로 해석한다(seed/목의 풀 URL은 패스스루). staging 이미지는 blob URL로 즉시 미리보기한다. 조회는 §1.5대로 presigned GET 리다이렉트로 전환했고, CDN은 별도 과제로 둔다.
4. **조회 API 현황** — `/drops`·`/drops/{id}`·`/drops/me`·`/products/me`·`/categories`·`/wallet` 모두 구현 완료다.
5. **`sellerId` = 스토어 `sellerInfoId`** — 상품/드롭 write·`/me` 소유 필터와 `ProductResponse.sellerId`는 게이트웨이가 판매자 scoped JWT에서 주입한 `sellerInfoId` 기준이다.
6. **FE 스키마 확인** — `ProductResponse.imageKeys`, `DropResponse.limitPerUser`, 주문 생성 응답 `created`를 FE zod 스키마가 허용하는지 실제 연동에서 확인한다.
7. **정렬 파라미터** — FE가 보내는 `sort`는 현재 product/drop 결과 순서에 반영되지 않는다. 통합 테스트에서는 상품 `createdAt desc`, 드롭 `openAt desc` 고정 순서를 기준으로 확인한다.

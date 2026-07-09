#!/usr/bin/env bash
# 드롭 조회~주문 e2e 스모크. 사용: ./scripts/smoke-drop-order.sh http://<EC2_IP>
# DB가 비어 있어도 자체 시드(회원→판매자→상품→드롭)를 만들고 주문까지 1회 수행한다.
set -euo pipefail
BASE="${1:?usage: $0 <base-url>}"
TS=$(date +%s)

req() { # method path token json → 응답 바디 출력, 4xx/5xx면 즉사(-f)
  local m=$1 p=$2 t=$3 d=$4
  curl -fsS -X "$m" "$BASE$p" -H 'Content-Type: application/json' \
       ${t:+-H "Authorization: Bearer $t"} ${d:+-d "$d"}
}
loc_id() { # 201 Location 헤더에서 마지막 세그먼트(id) 추출
  curl -fsS -D - -o /dev/null -X POST "$BASE$1" -H 'Content-Type: application/json' \
       -H "Authorization: Bearer $2" -d "$3" | awk -F/ 'tolower($0)~/^location:/{print $NF}' | tr -d '\r'
}

# ① 회원가입 → ② 로그인(구매자 겸 판매자 회원)
req POST /api/v1/members "" "{\"email\":\"smoke+$TS@test.local\",\"password\":\"smokePass1234\",\"nickname\":\"smoke$TS\"}" >/dev/null
TOKEN=$(req POST /api/v1/members/login "" "{\"email\":\"smoke+$TS@test.local\",\"password\":\"smokePass1234\"}" | jq -re .accessToken)

# ③ 판매자 등록 → ④ scoped 토큰(TTL 120초 — ⑤⑥을 즉시 연달아 실행)
SELLER_INFO_ID=$(req POST /api/v1/seller/me "$TOKEN" "{\"businessNumber\":\"999-99-$TS\",\"storeName\":\"smoke-store\"}" | jq -re '.sellerInfoId // .id')
STOKEN=$(req POST /api/v1/seller/token "$TOKEN" "{\"sellerInfoId\":\"$SELLER_INFO_ID\"}" | jq -re .accessToken)

# ⑤ 상품 등록(201 Location→productId) → ⑥ 드롭 등록(openAt=now+15s, @Future 충족)
PRODUCT_ID=$(loc_id /api/v1/products "$STOKEN" '{"name":"smoke sneakers","price":10000}')
OPEN_AT=$(date -u -d "+15 seconds" +%Y-%m-%dT%H:%M:%SZ)
DROP_ID=$(loc_id /api/v1/drops "$STOKEN" "{\"productId\":\"$PRODUCT_ID\",\"dropPrice\":10000,\"totalQuantity\":10,\"openAt\":\"$OPEN_AT\"}")

# ⑦ 오픈 대기 후 드롭 조회 — status OPEN 확인 (DoD 앞절반)
sleep 20
DROP=$(req GET "/api/v1/drops/$DROP_ID" "$TOKEN" "")
echo "$DROP" | jq -e '.status=="OPEN"' >/dev/null || { echo "FAIL: drop not OPEN: $DROP" >&2; exit 1; }

# ⑧ 주문 — PAYMENT_PENDING이면 성공 (DoD 뒷절반)
ORDER=$(req POST /api/v1/orders "$TOKEN" "{\"dropId\":\"$DROP_ID\",\"quantity\":1,\"idempotencyKey\":\"smoke-$TS\",\"orderName\":\"smoke sneakers\"}")
echo "$ORDER" | jq -e '.status=="PAYMENT_PENDING"' >/dev/null || { echo "FAIL: $ORDER" >&2; exit 1; }
echo "SMOKE OK: orderId=$(echo "$ORDER" | jq -r .orderId)"

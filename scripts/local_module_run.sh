#!/usr/bin/env bash
set -euo pipefail

# ── 경로 자동 탐지 ─────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# FE: 환경변수 우선, 없으면 BE 레포와 같은 부모 디렉터리에서 자동 탐색
FE_DIR="${FE_DIR:-$(find "$(dirname "$BE_DIR")" -maxdepth 1 -type d -name "*Let*GPT*FE*" 2>/dev/null | head -1)}"

# Java 21 탐지: 환경변수 → 시스템 java → ~/.jdks 자동 탐색
if [ -z "${JAVA21:-}" ]; then
    if command -v java &>/dev/null && java -version 2>&1 | grep -q '"21'; then
        JAVA21="$(command -v java)"
    else
        JAVA21="$(find "$HOME/.jdks" -name "java" -path "*/bin/java" 2>/dev/null \
            | while read -r bin; do
                "$bin" -version 2>&1 | grep -q '"21' && echo "$bin" && break
              done)"
    fi
fi

if [ -z "${JAVA21:-}" ]; then
    echo "[ERROR] Java 21을 찾을 수 없습니다. JAVA21=/path/to/java21 을 앞에 붙여 실행하세요."
    exit 1
fi

LOG_DIR="${LOG_DIR:-/tmp/letsgpt-logs}"
mkdir -p "$LOG_DIR"

echo "[INFO] BE : $BE_DIR"
echo "[INFO] FE : ${FE_DIR:-(없음 — FE 기동 건너뜀)}"
echo "[INFO] JVM: $JAVA21  ($(\"$JAVA21\" -version 2>&1 | head -1))"
echo "[INFO] LOG: $LOG_DIR"
echo ""

# ── 인프라 (Kafka, DB) ────────────────────────────────────────────────
docker compose -f "$BE_DIR/docker-compose.yml" up -d

# ── 모듈 빌드 (-x test: 단위 테스트 건너뜀, JAR 생성만) ─────────────
cd "$BE_DIR"
./gradlew :member:bootJar :product:bootJar :order:bootJar \
          :payment:bootJar :settlement:bootJar :apigateway:bootJar \
          -x test

# ── 환경변수 로드 ─────────────────────────────────────────────────────
set -a && source "$BE_DIR/.env" && set +a

# ── 서비스 기동 ───────────────────────────────────────────────────────
JAVA_PIDS=()

"$JAVA21" -jar "$BE_DIR/member/build/libs/member-0.0.1-SNAPSHOT.jar"         --spring.profiles.active=local > "$LOG_DIR/member.log"     2>&1 & JAVA_PIDS+=($!)
"$JAVA21" -jar "$BE_DIR/product/build/libs/product-0.0.1-SNAPSHOT.jar"       --spring.profiles.active=local > "$LOG_DIR/product.log"    2>&1 & JAVA_PIDS+=($!)
"$JAVA21" -jar "$BE_DIR/order/build/libs/order-0.0.1-SNAPSHOT.jar"           --spring.profiles.active=local > "$LOG_DIR/order.log"      2>&1 & JAVA_PIDS+=($!)
"$JAVA21" -jar "$BE_DIR/payment/build/libs/payment-0.0.1-SNAPSHOT.jar"       --spring.profiles.active=real  > "$LOG_DIR/payment.log"    2>&1 & JAVA_PIDS+=($!)
"$JAVA21" -jar "$BE_DIR/settlement/build/libs/settlement-0.0.1-SNAPSHOT.jar" --spring.profiles.active=local > "$LOG_DIR/settlement.log" 2>&1 & JAVA_PIDS+=($!)
"$JAVA21" -jar "$BE_DIR/apigateway/build/libs/apigateway-0.0.1-SNAPSHOT.jar" --spring.profiles.active=local > "$LOG_DIR/apigateway.log" 2>&1 & JAVA_PIDS+=($!)

cleanup() {
    echo ""
    echo "[INFO] 종료 중 — Java 서비스 6개 정리..."
    kill "${JAVA_PIDS[@]}" 2>/dev/null
    wait "${JAVA_PIDS[@]}" 2>/dev/null
    echo "[INFO] 정리 완료."
}
trap cleanup INT TERM

echo "[INFO] 서비스 6개 기동 완료. 로그: $LOG_DIR/"
echo ""

# ── FE 기동 ───────────────────────────────────────────────────────────
if [ -n "${FE_DIR:-}" ] && [ -d "$FE_DIR" ]; then
    cd "$FE_DIR"
    corepack pnpm dev --host
else
    echo "[WARN] FE 디렉터리를 찾지 못했습니다."
    echo "[WARN] FE_DIR=/path/to/FE 를 앞에 붙여 실행하거나, FE를 별도 터미널에서 실행하세요."
fi

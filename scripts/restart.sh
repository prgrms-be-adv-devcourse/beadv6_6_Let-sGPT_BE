#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="${LOG_DIR:-/tmp/letsgpt-logs}"

# ── 서비스 메타 (이름 → 포트, 프로필) ────────────────────────────
declare -A PORT=(
    [member]=9100 [product]=9110 [order]=9120
    [payment]=9130 [settlement]=9140 [apigateway]=8000
)
declare -A PROFILE=(
    [member]=local [product]=local [order]=local
    [payment]=real [settlement]=local [apigateway]=local
)

# ── 사용법 ────────────────────────────────────────────────────────
usage() {
    echo "사용법: $0 <서비스명> [--log]"
    echo "서비스: ${!PORT[*]}"
    echo ""
    echo "  $0 payment               # payment 재빌드 + 재시작"
    echo "  $0 payment --log         # 재빌드 + 재시작 + 로그 tailing"
    echo "  $0 payment --skip-build  # 재빌드 없이 재시작만"
    exit 1
}

[ $# -lt 1 ] && usage

SVC="$1"
TAIL_LOG=false
SKIP_BUILD=false
for arg in "${@:2}"; do
    [ "$arg" = "--log" ]        && TAIL_LOG=true
    [ "$arg" = "--skip-build" ] && SKIP_BUILD=true
done

if [ -z "${PORT[$SVC]:-}" ]; then
    echo "[ERROR] 알 수 없는 서비스: $SVC"
    echo "사용 가능: ${!PORT[*]}"
    exit 1
fi

# ── Java 21 탐지 ─────────────────────────────────────────────────
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
[ -z "${JAVA21:-}" ] && { echo "[ERROR] Java 21 없음. JAVA21=/path/to/java21 로 실행하세요."; exit 1; }

# ── 환경변수 로드 ─────────────────────────────────────────────────
[ -f "$BE_DIR/.env" ] && set -a && source "$BE_DIR/.env" && set +a

# ── 빌드 ─────────────────────────────────────────────────────────
JAR="$BE_DIR/$SVC/build/libs/$SVC-0.0.1-SNAPSHOT.jar"
if "$SKIP_BUILD"; then
    if [ ! -f "$JAR" ]; then
        echo "[ERROR] JAR 없음: $JAR  (--skip-build 제거 후 재실행)"
        exit 1
    fi
    echo "[INFO] --skip-build: 빌드 생략"
else
    echo "[INFO] $SVC 빌드 중..."
    cd "$BE_DIR" && ./gradlew ":${SVC}:bootJar" -x test -q
    echo "[INFO] 빌드 완료"
fi

# ── 기존 프로세스 종료 ────────────────────────────────────────────
OLD_PID=$(pgrep -f "${SVC}-0.0.1-SNAPSHOT.jar" 2>/dev/null || true)
if [ -n "$OLD_PID" ]; then
    echo "[INFO] 기존 $SVC 종료 (PID $OLD_PID)..."
    kill "$OLD_PID"
    # 포트 해제 대기
    for i in $(seq 1 10); do
        ss -tlnp | grep -q ":${PORT[$SVC]} " || break
        sleep 1
    done
fi

# ── 재시작 ────────────────────────────────────────────────────────
mkdir -p "$LOG_DIR"
echo "[INFO] $SVC 시작 (profile=${PROFILE[$SVC]}, port=${PORT[$SVC]})..."
"$JAVA21" -jar "$JAR" --spring.profiles.active="${PROFILE[$SVC]}" \
    > "$LOG_DIR/$SVC.log" 2>&1 &
NEW_PID=$!
echo "[INFO] PID $NEW_PID → 로그: $LOG_DIR/$SVC.log"

"$TAIL_LOG" && tail -f "$LOG_DIR/$SVC.log"

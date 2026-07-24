#!/usr/bin/env bash
# k8s Secret 부트스트랩 (최초 1회 수동 — 7/9 CD에서 CI 자동화로 교체 예정)
#
# 사용법:
#   ./create-secrets.sh <.env 경로>            # app-secrets 생성/갱신
#   GHCR_USER=<github id> GHCR_PAT=<read:packages PAT> ./create-secrets.sh <.env 경로>
#                                              # + ghcr-pull(이미지 pull 시크릿)도 생성/갱신
#
# 값은 기존 .env/GitHub ENV_SECRETS에서 그대로 사용(신규 발급 아님).
set -euo pipefail

ENV_FILE="${1:?사용법: $0 <.env 경로>}"
NS=openat
# 러너/서버 환경에 따라 kubectl 경로가 다름(k3s는 `k3s kubectl`). 기본은 kubectl, 필요 시 override.
KUBECTL="${KUBECTL:-kubectl}"

[ -f "$ENV_FILE" ] || { echo "ERROR: $ENV_FILE 없음" >&2; exit 1; }

# .env는 compose --env-file 포맷(KEY=VALUE, 단일 라인) — 민감키만 추출
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

for key in DB_USER DB_PASSWORD JWT_KEY_ID JWT_PRIVATE_KEY JWT_PUBLIC_KEY PG_CLIENT_KEY PG_SECRET_KEY PAYMENT_FIELD_ENCRYPTION_KEY OPENAI_API_KEY OPENAI_INFERENCEAI_BASE_URL OPENAI_INFERENCEAI_API_KEY; do
  [ -n "${!key:-}" ] || { echo "ERROR: $ENV_FILE 에 $key 없음/빈값 — 실패를 조용히 넘기지 않는다" >&2; exit 1; }
done

# --- 도메인별 Secret 분리 (7/18 env 주입 전환) ---
# 공용 DB (단일 postgres·단일 role이라 공유 합리)
"$KUBECTL" create secret generic db-secrets -n "$NS" \
  --from-literal=DB_USER="$DB_USER" \
  --from-literal=DB_PASSWORD="$DB_PASSWORD" \
  --dry-run=client -o yaml | "$KUBECTL" apply -f -
echo "OK: db-secrets 적용 완료"

# member 전용 — JWT 발급/서명 주체
"$KUBECTL" create secret generic member-secrets -n "$NS" \
  --from-literal=JWT_KEY_ID="$JWT_KEY_ID" \
  --from-literal=JWT_PRIVATE_KEY="$JWT_PRIVATE_KEY" \
  --from-literal=JWT_PUBLIC_KEY="$JWT_PUBLIC_KEY" \
  --dry-run=client -o yaml | "$KUBECTL" apply -f -
echo "OK: member-secrets 적용 완료"

# payment 전용
"$KUBECTL" create secret generic payment-secrets -n "$NS" \
  --from-literal=PG_SECRET_KEY="$PG_SECRET_KEY" \
  --from-literal=PAYMENT_FIELD_ENCRYPTION_KEY="$PAYMENT_FIELD_ENCRYPTION_KEY" \
  --dry-run=client -o yaml | "$KUBECTL" apply -f -
echo "OK: payment-secrets 적용 완료"

# search 전용 — GitHub의 OPENAI_INFERENCEAI_* 값을 같은 이름 그대로 파드에 주입.
"$KUBECTL" create secret generic search-secrets -n "$NS" \
  --from-literal=OPENAI_API_KEY="$OPENAI_API_KEY" \
  --from-literal=OPENAI_INFERENCEAI_BASE_URL="$OPENAI_INFERENCEAI_BASE_URL" \
  --from-literal=OPENAI_INFERENCEAI_API_KEY="$OPENAI_INFERENCEAI_API_KEY" \
  --from-literal=OPENAI_INFERENCEAI_EMBEDDING_ENABLED="${OPENAI_INFERENCEAI_EMBEDDING_ENABLED:-true}" \
  --dry-run=client -o yaml | "$KUBECTL" apply -f -
echo "OK: search-secrets 적용 완료"

# (전환기 한정) 레거시 app-secrets 병행 생성 — 매니페스트 revert 롤백 지렛대.
# 안정 확인 후 후속 커밋에서 이 블록과 위 필수 키 목록의 PG_CLIENT_KEY를 함께 제거하고
# `kubectl delete secret app-secrets -n openat` 1회 수동 실행으로 마감한다.
"$KUBECTL" create secret generic app-secrets -n "$NS" \
  --from-literal=DB_USER="$DB_USER" \
  --from-literal=DB_PASSWORD="$DB_PASSWORD" \
  --from-literal=JWT_KEY_ID="$JWT_KEY_ID" \
  --from-literal=JWT_PRIVATE_KEY="$JWT_PRIVATE_KEY" \
  --from-literal=JWT_PUBLIC_KEY="$JWT_PUBLIC_KEY" \
  --from-literal=PG_CLIENT_KEY="$PG_CLIENT_KEY" \
  --from-literal=PG_SECRET_KEY="$PG_SECRET_KEY" \
  --from-literal=PAYMENT_FIELD_ENCRYPTION_KEY="$PAYMENT_FIELD_ENCRYPTION_KEY" \
  --from-literal=OPENAI_API_KEY="$OPENAI_API_KEY" \
  --dry-run=client -o yaml | "$KUBECTL" apply -f -
echo "OK: app-secrets 적용 완료 (namespace=$NS)"

# GHCR 이미지 pull 시크릿 (BE/FE 이미지 모두 private — read:packages 스코프 PAT 필요)
if [ -n "${GHCR_PAT:-}" ]; then
  "$KUBECTL" create secret docker-registry ghcr-pull -n "$NS" \
    --docker-server=ghcr.io \
    --docker-username="${GHCR_USER:?GHCR_PAT 사용 시 GHCR_USER도 필요}" \
    --docker-password="$GHCR_PAT" \
    --dry-run=client -o yaml | "$KUBECTL" apply -f -
  echo "OK: ghcr-pull 적용 완료"
  # 네임스페이스 default SA에 부착 → 전 파드가 imagePullSecret 상속(멀티노드 정석, Q83-6).
  "$KUBECTL" patch serviceaccount default -n "$NS" \
    -p '{"imagePullSecrets":[{"name":"ghcr-pull"}]}'
  echo "OK: default SA에 ghcr-pull 부착 완료"
else
  echo "SKIP: GHCR_PAT 미지정 — ghcr-pull 시크릿은 생성하지 않음 (앱 이미지 pull 불가 상태)"
fi

# 웜업 전용 — PostSync Job이 로그인해 임시 토큰을 발급받는 테스트 계정.
# 미지정이면 생성하지 않는다(웜업 Job은 REQUIRE_AUTH=0 으로 인증 대상만 스킵). 필수 키
# 검증 루프에 넣지 않는다 — 기존 운영자 .env가 즉시 실패하지 않도록 GHCR/Grafana와 동일한
# 선택적 분기로 둔다.
if [ -n "${WARMUP_EMAIL:-}" ] && [ -n "${WARMUP_PASSWORD:-}" ]; then
  "$KUBECTL" create secret generic warmup-secrets -n "$NS" \
    --from-literal=WARMUP_EMAIL="$WARMUP_EMAIL" \
    --from-literal=WARMUP_PASSWORD="$WARMUP_PASSWORD" \
    --dry-run=client -o yaml | "$KUBECTL" apply -f -
  echo "OK: warmup-secrets 적용 완료 (namespace=$NS)"
else
  echo "SKIP: WARMUP_EMAIL/WARMUP_PASSWORD 미지정 — 웜업 인증 대상은 스킵됨"
fi

# postgres-exporter용 Secret. exporter가 openat 네임스페이스에 있으므로 같은 네임스페이스에
# 생성한다(cross-namespace 참조 불필요). DB_USER/DB_PASSWORD를 URI에 그대로 넣으면 '%' 등
# 특수문자가 있을 때 postgres_exporter의 DSN 파서가 잘못된 percent-encoding으로 오인해 파싱
# 실패(2026-07-12 실측 발견) — 반드시 URL 인코딩 후 삽입.
DB_USER_ENC=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$DB_USER")
DB_PASSWORD_ENC=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$DB_PASSWORD")
"$KUBECTL" create secret generic pg-exporter-secret -n "$NS" \
  --from-literal=DATA_SOURCE_NAME="postgresql://${DB_USER_ENC}:${DB_PASSWORD_ENC}@postgres.openat.svc.cluster.local:5432/openat?sslmode=disable" \
  --dry-run=client -o yaml | "$KUBECTL" apply -f -
echo "OK: pg-exporter-secret 적용 완료 (namespace=$NS)"

# WS-D — Grafana admin 자격증명. 미지정 시 랜덤 생성(하드코딩 금지 — 실패는 시끄럽게).
# 멱등 처리 근거: 과거엔 미지정 시 매 실행마다 openssl rand로 새 비밀번호를 생성해 Secret을
# 덮어썼는데, CD가 돌 때마다 Secret이 회전되어 이미 기동된 grafana 파드의 env(기동 시점 주입)와
# 어긋나 admin 로그인이 계속 깨졌다(2026-07-20 실측: Secret 해시와 파드 env 해시 불일치).
# 따라서 명시 지정이 없고 Secret이 이미 있으면 회전하지 않고 그대로 둔다.
# grafana-admin은 observability 네임스페이스에 있어야 하므로 여기서 네임스페이스를 보장한다
# (postgres-exporter가 openat으로 옮겨간 뒤에도 grafana 등 관측 스택은 observability에 남는다).
"$KUBECTL" create namespace observability --dry-run=client -o yaml | "$KUBECTL" apply -f -
if [ -n "${GRAFANA_ADMIN_PASSWORD:-}" ]; then
  # 명시 지정 — 의도적 회전 허용(현행대로 apply).
  "$KUBECTL" create secret generic grafana-admin -n observability \
    --from-literal=admin-user="${GRAFANA_ADMIN_USER:-admin}" \
    --from-literal=admin-password="$GRAFANA_ADMIN_PASSWORD" \
    --dry-run=client -o yaml | "$KUBECTL" apply -f -
  echo "OK: grafana-admin 적용 완료 (namespace=observability, admin-user=${GRAFANA_ADMIN_USER:-admin})"
elif "$KUBECTL" get secret grafana-admin -n observability >/dev/null 2>&1; then
  # 미지정 + 이미 존재 — 회전 금지(파드 env와의 불일치 방지).
  echo "SKIP: grafana-admin 이미 존재 — 회전 안 함(명시 회전은 GRAFANA_ADMIN_PASSWORD 지정)"
else
  # 미지정 + 부재 — 최초 랜덤 생성.
  GRAFANA_ADMIN_PASSWORD="$(openssl rand -base64 18)"
  "$KUBECTL" create secret generic grafana-admin -n observability \
    --from-literal=admin-user="${GRAFANA_ADMIN_USER:-admin}" \
    --from-literal=admin-password="$GRAFANA_ADMIN_PASSWORD" \
    --dry-run=client -o yaml | "$KUBECTL" apply -f -
  echo "OK: grafana-admin 최초 생성 완료 (namespace=observability, admin-user=${GRAFANA_ADMIN_USER:-admin})"
fi

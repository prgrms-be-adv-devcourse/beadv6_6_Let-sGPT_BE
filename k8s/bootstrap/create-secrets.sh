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

for key in DB_USER DB_PASSWORD JWT_KEY_ID JWT_PRIVATE_KEY JWT_PUBLIC_KEY PG_CLIENT_KEY PG_SECRET_KEY PAYMENT_FIELD_ENCRYPTION_KEY OPENAI_API_KEY; do
  [ -n "${!key:-}" ] || { echo "ERROR: $ENV_FILE 에 $key 없음/빈값 — 실패를 조용히 넘기지 않는다" >&2; exit 1; }
done

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

# WS-C(7/10 observability plan) — postgres_exporter용 Secret. openat의 app-secrets는
# cross-namespace 참조가 안 되므로 observability 네임스페이스에 동일 값으로 복제.
# DB_USER/DB_PASSWORD를 URI에 그대로 넣으면 '%' 등 특수문자가 있을 때 postgres_exporter의
# DSN 파서가 잘못된 percent-encoding으로 오인해 파싱 실패(2026-07-12 실측 발견) — 반드시
# URL 인코딩 후 삽입.
DB_USER_ENC=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$DB_USER")
DB_PASSWORD_ENC=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$DB_PASSWORD")
"$KUBECTL" create namespace observability --dry-run=client -o yaml | "$KUBECTL" apply -f -
"$KUBECTL" create secret generic pg-exporter-secret -n observability \
  --from-literal=DATA_SOURCE_NAME="postgresql://${DB_USER_ENC}:${DB_PASSWORD_ENC}@postgres.openat.svc.cluster.local:5432/openat?sslmode=disable" \
  --dry-run=client -o yaml | "$KUBECTL" apply -f -
echo "OK: pg-exporter-secret 적용 완료 (namespace=observability)"

# WS-D — Grafana admin 자격증명. 미지정 시 랜덤 생성(하드코딩 금지 — 실패는 시끄럽게).
GRAFANA_ADMIN_PASSWORD="${GRAFANA_ADMIN_PASSWORD:-$(openssl rand -base64 18)}"
"$KUBECTL" create secret generic grafana-admin -n observability \
  --from-literal=admin-user="${GRAFANA_ADMIN_USER:-admin}" \
  --from-literal=admin-password="$GRAFANA_ADMIN_PASSWORD" \
  --dry-run=client -o yaml | "$KUBECTL" apply -f -
echo "OK: grafana-admin 적용 완료 (namespace=observability, admin-user=${GRAFANA_ADMIN_USER:-admin})"

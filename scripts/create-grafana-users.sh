#!/usr/bin/env bash
#
# create-grafana-users.sh — Grafana Admin API로 팀원 Viewer 계정을 일괄 생성.
#
# 정책(7-19 plan §1): 개별 5계정, 전원 Viewer, 계정별 랜덤 초기 비밀번호(openssl rand).
# 대시보드는 전부 프로비저닝(ConfigMap)이라 편집 권한 불요 — Viewer가 자연스러운 경계.
#
# 인증: /api/admin/users 는 Basic Auth 전용(Service Account Token 불가) — admin 자격을
#       --user "admin:${GRAFANA_ADMIN_PASSWORD}" 로 주입. admin 비밀번호는 절대 하드코딩 금지.
# 멱등: 이미 존재하는 로그인(409)은 skip.
# 산출: 로그인명/초기비밀번호를 chmod 600 로컬 파일로만 출력(커밋 금지 — .gitignore 등록됨).
#       개별 전달 후 각자 로그인하여 비밀번호 변경 안내.
#
# 필수 환경변수: GRAFANA_URL, GRAFANA_ADMIN_PASSWORD (미설정 시 즉시 실패 — 실패는 시끄럽게).
# 선택 환경변수: GRAFANA_ADMIN_USER(기본 admin), GRAFANA_ORG_ID(기본 1),
#               GRAFANA_USER_DOMAIN(기본 openat.local), GRAFANA_USERS_FILE(결과 파일 경로).
set -euo pipefail

# ---- 팀원 로그인명(5명 자리표시자 — 실제 값으로 교체) ----
USERS=(
  "teammate1"
  "teammate2"
  "teammate3"
  "teammate4"
  "teammate5"
)

# ---- 필수 환경변수 검사 ----
: "${GRAFANA_URL:?GRAFANA_URL 미설정 — 예: export GRAFANA_URL=https://grafana.openat.duckdns.org}"
: "${GRAFANA_ADMIN_PASSWORD:?GRAFANA_ADMIN_PASSWORD 미설정 — admin 비밀번호를 환경변수로 주입할 것(하드코딩 금지)}"
GRAFANA_ADMIN_USER="${GRAFANA_ADMIN_USER:-admin}"
GRAFANA_ORG_ID="${GRAFANA_ORG_ID:-1}"
GRAFANA_USER_DOMAIN="${GRAFANA_USER_DOMAIN:-openat.local}"

command -v curl >/dev/null 2>&1 || { echo "ERROR: curl 미설치" >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "ERROR: openssl 미설치" >&2; exit 1; }

GRAFANA_URL="${GRAFANA_URL%/}"  # 후행 슬래시 제거
OUT_FILE="${GRAFANA_USERS_FILE:-grafana-users-$(date +%Y%m%d-%H%M%S).txt}"

# 결과 파일은 생성 즉시 권한 잠금(600) 후 append.
umask 077
: > "$OUT_FILE"
chmod 600 "$OUT_FILE"
{
  echo "# Grafana 초기 계정 — 생성 시각: $(date -Iseconds)"
  echo "# URL: ${GRAFANA_URL}"
  echo "# 전달 후 각자 로그인하여 비밀번호를 변경할 것. 이 파일은 커밋 금지(.gitignore 등록됨)."
  echo "# login<TAB>password<TAB>status"
} >> "$OUT_FILE"

echo "==> Grafana 계정 생성 시작 (${GRAFANA_URL}, org=${GRAFANA_ORG_ID})"

for login in "${USERS[@]}"; do
  password="$(openssl rand -base64 15)"
  email="${login}@${GRAFANA_USER_DOMAIN}"

  # POST /api/admin/users — 생성. 본문+HTTP코드 동시 캡처.
  payload=$(printf '{"name":"%s","login":"%s","email":"%s","password":"%s"}' \
            "$login" "$login" "$email" "$password")
  body_file="$(mktemp)"
  http_code=$(curl -sS -o "$body_file" -w '%{http_code}' \
    --user "${GRAFANA_ADMIN_USER}:${GRAFANA_ADMIN_PASSWORD}" \
    -H "Content-Type: application/json" \
    -X POST "${GRAFANA_URL}/api/admin/users" \
    -d "$payload" || true)

  case "$http_code" in
    200)
      user_id=$(sed -n 's/.*"id":[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$body_file")
      # 생성 직후 org role을 Viewer로 명시 확정(자동배정 역할에 의존하지 않음).
      role_code=$(curl -sS -o /dev/null -w '%{http_code}' \
        --user "${GRAFANA_ADMIN_USER}:${GRAFANA_ADMIN_PASSWORD}" \
        -H "Content-Type: application/json" \
        -X PATCH "${GRAFANA_URL}/api/orgs/${GRAFANA_ORG_ID}/users/${user_id}" \
        -d '{"role":"Viewer"}' || true)
      if [ "$role_code" != "200" ]; then
        echo "  ! ${login}: 생성됨(id=${user_id})이나 Viewer 역할 설정 실패(HTTP ${role_code}) — 수동 확인 필요" >&2
        printf '%s\t%s\tCREATED_ROLE_UNVERIFIED(id=%s)\n' "$login" "$password" "$user_id" >> "$OUT_FILE"
      else
        echo "  + ${login}: 생성 완료(id=${user_id}, role=Viewer)"
        printf '%s\t%s\tCREATED(id=%s,Viewer)\n' "$login" "$password" "$user_id" >> "$OUT_FILE"
      fi
      ;;
    409|412)
      # 이미 존재 — 멱등 skip(비밀번호 재설정하지 않음).
      echo "  = ${login}: 이미 존재 — skip"
      printf '%s\t%s\tSKIPPED_EXISTS\n' "$login" "-" >> "$OUT_FILE"
      ;;
    *)
      echo "ERROR: ${login} 생성 실패 (HTTP ${http_code}): $(cat "$body_file")" >&2
      rm -f "$body_file"
      exit 1
      ;;
  esac
  rm -f "$body_file"
done

echo "==> 완료. 초기 비밀번호는 ${OUT_FILE} (chmod 600)에 기록됨 — 안전 전달 후 삭제 권장."

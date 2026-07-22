#!/usr/bin/env bash

set -euo pipefail

required_variables=(
  AI_READ_MODEL_DB_HOST
  AI_READ_MODEL_DB_PORT
  AI_READ_MODEL_DB_NAME
  AI_READ_MODEL_ADMIN_USER
  AI_READ_MODEL_ADMIN_PASSWORD
  AI_QUERY_DB_PASSWORD
  AI_QUERY_DB_PREVIOUS_PASSWORD
)

for variable_name in "${required_variables[@]}"; do
  if [[ -z "${!variable_name:-}" ]]; then
    echo "필수 환경 변수가 비어 있어 read-model 적용을 중단해: ${variable_name}" >&2
    exit 1
  fi
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ddl_file="${script_dir}/../src/main/resources/db/read-model/01-ai-read-model.sql"
verify_file="${script_dir}/../src/main/resources/db/read-model/verify-ai-read-model.sql"
termination_log="${AI_READ_MODEL_TERMINATION_LOG:-/dev/termination-log}"

record_db_state() {
  local state="$1"
  printf 'DB_STATE=%s\n' "$state" >"${termination_log}" 2>/dev/null || true
  echo "DB_STATE=${state}"
}

probe_query_login() {
  local password="$1"
  PGPASSWORD="$password" psql \
    --no-psqlrc \
    --host="${AI_READ_MODEL_DB_HOST}" \
    --port="${AI_READ_MODEL_DB_PORT}" \
    --dbname="${AI_READ_MODEL_DB_NAME}" \
    --username=ai_query_app \
    --set=ON_ERROR_STOP=1 \
    --tuples-only \
    --command='SELECT 1'
}

verify_query_contract() {
  local password="$1"
  PGPASSWORD="$password" psql \
    --no-psqlrc \
    --host="${AI_READ_MODEL_DB_HOST}" \
    --port="${AI_READ_MODEL_DB_PORT}" \
    --dbname="${AI_READ_MODEL_DB_NAME}" \
    --username=ai_query_app \
    --set=ON_ERROR_STOP=1 \
    --file="${verify_file}"
}

verify_previous_state() {
  if probe_query_login "${AI_QUERY_DB_PREVIOUS_PASSWORD}"; then
    record_db_state PREVIOUS
  else
    record_db_state UNKNOWN
  fi
}

export AI_QUERY_DB_PASSWORD
if ! {
  printf '\\getenv AI_QUERY_DB_PASSWORD AI_QUERY_DB_PASSWORD\n'
  cat "${ddl_file}"
} | PGPASSWORD="${AI_READ_MODEL_ADMIN_PASSWORD}" \
  PGOPTIONS="-c lock_timeout=5s -c statement_timeout=30s" psql \
  --no-psqlrc \
  --host="${AI_READ_MODEL_DB_HOST}" \
  --port="${AI_READ_MODEL_DB_PORT}" \
  --dbname="${AI_READ_MODEL_DB_NAME}" \
  --username="${AI_READ_MODEL_ADMIN_USER}" \
  --set=ON_ERROR_STOP=1 \
  --single-transaction; then
  echo "read-model DDL transaction에 실패해 직전 자격증명 상태를 확인합니다." >&2
  verify_previous_state
  exit 1
fi

if ! verify_query_contract "${AI_QUERY_DB_PASSWORD}"; then
  echo "새 AI 조회 자격증명 검증에 실패해 직전 비밀번호로 복구를 시도합니다." >&2
  export AI_QUERY_DB_PREVIOUS_PASSWORD
  if ! {
    printf '\\getenv AI_QUERY_DB_PREVIOUS_PASSWORD AI_QUERY_DB_PREVIOUS_PASSWORD\n'
    printf "ALTER ROLE ai_query_app PASSWORD :'AI_QUERY_DB_PREVIOUS_PASSWORD';\n"
  } | PGPASSWORD="${AI_READ_MODEL_ADMIN_PASSWORD}" \
    PGOPTIONS="-c lock_timeout=5s -c statement_timeout=30s" psql \
    --no-psqlrc \
    --host="${AI_READ_MODEL_DB_HOST}" \
    --port="${AI_READ_MODEL_DB_PORT}" \
    --dbname="${AI_READ_MODEL_DB_NAME}" \
    --username="${AI_READ_MODEL_ADMIN_USER}" \
    --set=ON_ERROR_STOP=1 \
    --single-transaction; then
    record_db_state UNKNOWN
    echo "직전 AI 조회 비밀번호 복구 transaction이 실패해 자동 Secret 복구를 금지합니다." >&2
    exit 1
  fi
  if probe_query_login "${AI_QUERY_DB_PREVIOUS_PASSWORD}"; then
    record_db_state PREVIOUS
    echo "직전 AI 조회 비밀번호 복구와 로그인을 확인했지만 배포는 중단합니다." >&2
  else
    record_db_state UNKNOWN
    echo "직전 AI 조회 비밀번호 로그인을 확인하지 못해 자동 Secret 복구를 금지합니다." >&2
  fi
  exit 1
fi

record_db_state NEW
unset AI_QUERY_DB_PASSWORD AI_QUERY_DB_PREVIOUS_PASSWORD AI_READ_MODEL_ADMIN_PASSWORD

echo "AI read-model 적용과 실행 역할 검증이 완료됐어."

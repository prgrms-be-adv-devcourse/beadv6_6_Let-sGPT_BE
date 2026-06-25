#!/usr/bin/env bash
# 이미 떠 있는 postgres 컨테이너(볼륨이 비어있지 않아 init 스크립트가 자동 실행되지 않은 경우)에
# 5개 도메인 스키마를 수동으로 생성한다.
#
# 사용법:
#   ./db/create-schemas.sh [postgres 컨테이너명] [DB명]
#   기본값: local-postgres / openat  (docker-compose.yml 기준)

set -euo pipefail

CONTAINER="${1:-local-postgres}"
DB_NAME="${2:-openat}"

docker exec -i "$CONTAINER" psql -U "${DB_USER:-postgres}" -d "$DB_NAME" <<'SQL'
CREATE SCHEMA IF NOT EXISTS member;
CREATE SCHEMA IF NOT EXISTS product;
CREATE SCHEMA IF NOT EXISTS orders;
CREATE SCHEMA IF NOT EXISTS payment;
CREATE SCHEMA IF NOT EXISTS settlement;
SQL

echo "5개 스키마 생성 완료 (member, product, orders, payment, settlement) @ $CONTAINER/$DB_NAME"

#!/bin/bash
# Docker Compose 초기 기동 시 PostgreSQL 스키마를 자동 구성한다.
# /docker-entrypoint-initdb.d/ 에 마운트되어 컨테이너 최초 생성 시 1회 실행된다.
set -euo pipefail

echo "▶ Applying schema.sql ..."
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -f /sql/schema.sql

# migration 파일을 버전 순서(V2, V3, ..., V17)로 적용
for f in $(ls /sql/migration/V*.sql | sort -t'V' -k2 -n); do
  echo "▶ Applying $(basename "$f") ..."
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f "$f"
done

echo "✔ Database initialization complete."

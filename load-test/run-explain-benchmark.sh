#!/usr/bin/env bash
set -euo pipefail

PSQL_BIN="${PSQL_BIN:-psql}"
BENCHMARK_YEAR="${BENCHMARK_YEAR:-2025}"
OUTPUT_DIR="${OUTPUT_DIR:-load-test/explain-results}"
START_DATE="${BENCHMARK_YEAR}-01-01 00:00:00"
END_DATE="$((BENCHMARK_YEAR + 1))-01-01 00:00:00"

mkdir -p "${OUTPUT_DIR}"

run_psql() {
  "${PSQL_BIN}" -v ON_ERROR_STOP=1 "$@"
}

echo "[1/5] setup benchmark dataset"
run_psql -f load-test/setup-explain-benchmark.sql > "${OUTPUT_DIR}/setup.log"

echo "[2/5] collect BEFORE measurements"
run_psql -c "DROP INDEX IF EXISTS idx_order_yearly_spent_non_cancelled;"
run_psql -c "VACUUM ANALYZE orders;"
run_psql -c "VACUUM ANALYZE order_items;"
run_psql -v start_date="'${START_DATE}'" -v end_date="'${END_DATE}'" \
  -f load-test/explain-benchmark-before.sql > "${OUTPUT_DIR}/before.txt"

echo "[3/5] apply optimization"
run_psql -c "CREATE INDEX idx_order_yearly_spent_non_cancelled ON orders(order_date) INCLUDE (user_id, final_amount) WHERE order_status <> 'CANCELLED';"

echo "[4/5] collect AFTER measurements"
run_psql -c "VACUUM ANALYZE orders;"
run_psql -v start_date="'${START_DATE}'" -v end_date="'${END_DATE}'" \
  -f load-test/explain-benchmark-after.sql > "${OUTPUT_DIR}/after.txt"

echo "[5/5] done"
echo "  setup log : ${OUTPUT_DIR}/setup.log"
echo "  before    : ${OUTPUT_DIR}/before.txt"
echo "  after     : ${OUTPUT_DIR}/after.txt"

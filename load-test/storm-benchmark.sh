#!/usr/bin/env bash
# storm-benchmark.sh
# Phase 21 (COUNT 공유 캐시) 단독 기여 격리 측정용.
# 재시작 직후 cold cache 상태에서 sort × page × size 조합을 burst로 쏴
# productList 캐시 미스 스톰 상황을 재현한다.
#
# 사용법:
#   BASE_URL=http://localhost:8080 CONCURRENCY=30 LABEL=phase21_on \
#     load-test/storm-benchmark.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
CONCURRENCY="${CONCURRENCY:-30}"
LABEL="${LABEL:-storm}"
OUTDIR="${OUTDIR:-/tmp/phase21-k6}"
mkdir -p "$OUTDIR"

URL_FILE="$OUTDIR/storm-urls.txt"
RAW_FILE="$OUTDIR/storm-${LABEL}-raw.txt"
TIMES_FILE="$OUTDIR/storm-${LABEL}-times.txt"
SUMMARY_FILE="$OUTDIR/storm-${LABEL}-summary.txt"

sorts=(best price_asc price_desc newest rating review)
pages=(0 1 2 3 4)
sizes=(10 20)

: > "$URL_FILE"
for s in "${sorts[@]}"; do
    for p in "${pages[@]}"; do
        for sz in "${sizes[@]}"; do
            echo "${BASE_URL}/products?sort=${s}&page=${p}&size=${sz}" >> "$URL_FILE"
        done
    done
done

TOTAL=$(wc -l < "$URL_FILE")

START_NS=$(date +%s%N)
# -P: 동시 실행 프로세스 수
# -w "%{http_code} %{time_total}\n": 응답 상태 + 총 소요 시간(초)
xargs -P "$CONCURRENCY" -a "$URL_FILE" -I{} \
    curl -s -o /dev/null -w "%{http_code} %{time_total}\n" "{}" > "$RAW_FILE"
END_NS=$(date +%s%N)

ELAPSED_SEC=$(awk -v s="$START_NS" -v e="$END_NS" 'BEGIN{printf "%.3f", (e - s)/1e9}')

OK_COUNT=$(awk '$1 == "200" {n++} END{print n+0}' "$RAW_FILE")
FAIL_COUNT=$(awk '$1 != "200" {n++} END{print n+0}' "$RAW_FILE")

awk '{print $2}' "$RAW_FILE" | sort -n > "$TIMES_FILE"
pct() {
    # 선형 보간 없이 nearest-rank 방식(부하 테스트 관행).
    local p=$1
    local idx
    idx=$(awk -v t="$TOTAL" -v p="$p" 'BEGIN{i=int(t*p/100); if(i<1) i=1; print i}')
    sed -n "${idx}p" "$TIMES_FILE"
}
P50=$(pct 50)
P95=$(pct 95)
P99=$(pct 99)
MAX=$(tail -1 "$TIMES_FILE")
SUM=$(awk '{s+=$1} END{print s}' "$TIMES_FILE")
AVG=$(awk -v s="$SUM" -v n="$TOTAL" 'BEGIN{printf "%.6f", s/n}')

{
    echo "label=${LABEL}"
    echo "base_url=${BASE_URL}"
    echo "concurrency=${CONCURRENCY}"
    echo "total_requests=${TOTAL}"
    echo "ok_count=${OK_COUNT}"
    echo "fail_count=${FAIL_COUNT}"
    echo "elapsed_sec=${ELAPSED_SEC}"
    echo "avg_sec=${AVG}"
    echo "p50_sec=${P50}"
    echo "p95_sec=${P95}"
    echo "p99_sec=${P99}"
    echo "max_sec=${MAX}"
} | tee "$SUMMARY_FILE"

#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

python3 - <<'PY'
from pathlib import Path
import re
import subprocess
import sys

README_PATH = Path("README.md")
SCHEMA_PATH = Path("src/main/resources/schema.sql")

readme = README_PATH.read_text(encoding="utf-8")
schema = SCHEMA_PATH.read_text(encoding="utf-8")

schema_clean = "\n".join(
    line for line in schema.splitlines() if not re.match(r"^\s*--", line)
)

def count_files(pattern: str) -> int:
    output = subprocess.check_output(["bash", "-lc", f"find {pattern} | wc -l"], text=True)
    return int(output.strip())

actual = {
    "main_java_files": count_files("src/main/java -type f -name '*.java'"),
    "test_java_files": count_files("src/test/java -type f -name '*.java'"),
    "html_templates": count_files("src/main/resources/templates -type f -name '*.html'"),
    "schema_tables": len(re.findall(r"\bCREATE\s+TABLE\b", schema_clean, flags=re.I)),
    "schema_indexes_total": len(re.findall(r"\bCREATE\s+(?:UNIQUE\s+)?INDEX\b", schema_clean, flags=re.I)),
    "schema_indexes_unique": len(re.findall(r"\bCREATE\s+UNIQUE\s+INDEX\b", schema_clean, flags=re.I)),
}
actual["schema_indexes_normal"] = actual["schema_indexes_total"] - actual["schema_indexes_unique"]

patterns = {
    "main_java_files": r"메인 코드: `src/main/java` 기준 \*\*(\d+)개 Java 파일\*\*",
    "test_java_files": r"테스트 코드: `src/test/java` 기준 \*\*(\d+)개 Java 파일\*\*",
    "html_templates": r"템플릿: `src/main/resources/templates` 기준 \*\*(\d+)개 HTML 파일\*\*",
    "schema_tables": r"DB 스키마: \*\*(\d+)개 테이블\*\*",
    "schema_indexes_total": r"DB 스키마: .*?\*\*(\d+)개 인덱스\*\*",
    "schema_indexes_normal": r"\(일반 (\d+) \+ UNIQUE",
    "schema_indexes_unique": r"UNIQUE (\d+)\)",
}

labels = {
    "main_java_files": "Java 파일 수 (main)",
    "test_java_files": "Java 파일 수 (test)",
    "html_templates": "템플릿 파일 수",
    "schema_tables": "schema.sql 테이블 수",
    "schema_indexes_total": "schema.sql 인덱스 수(총)",
    "schema_indexes_normal": "schema.sql 인덱스 수(일반)",
    "schema_indexes_unique": "schema.sql 인덱스 수(UNIQUE)",
}

documented = {}
missing = []
for key, pattern in patterns.items():
    m = re.search(pattern, readme, flags=re.S)
    if not m:
        missing.append(key)
        continue
    documented[key] = int(m.group(1))

if missing:
    print("| 항목 | 실제값 | 문서값 | 상태 |")
    print("|---|---:|---:|---|")
    for key in missing:
        print(f"| {labels[key]} | {actual.get(key, '-') } | - | ❌ README 패턴 미검출 |")
    print("\nREADME 수치 파싱에 실패했습니다. 패턴을 확인해 주세요.", file=sys.stderr)
    sys.exit(1)

print("| 항목 | 실제값 | 문서값 | 상태 |")
print("|---|---:|---:|---|")

has_drift = False
for key in labels:
    a = actual[key]
    d = documented[key]
    ok = a == d
    status = "✅ 일치" if ok else "❌ 드리프트"
    if not ok:
        has_drift = True
    print(f"| {labels[key]} | {a} | {d} | {status} |")

if has_drift:
    print("\n문서 수치 드리프트를 감지했습니다. README 수치를 갱신하세요.", file=sys.stderr)
    sys.exit(1)
PY

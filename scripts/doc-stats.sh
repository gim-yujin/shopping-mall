#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

java_files_count="$(find src/main/java -type f -name '*.java' | wc -l | tr -d ' ')"
html_templates_count="$(find src/main/resources/templates -type f -name '*.html' | wc -l | tr -d ' ')"

java_packages_count="$(find src/main/java -type f -name '*.java' \
  | xargs -I{} dirname '{}' \
  | sed 's#^src/main/java/##' \
  | sed 's#/#.#g' \
  | sort -u \
  | wc -l | tr -d ' ')"

if [ -d src/main/java/com/shop/domain ]; then
  domain_packages_count="$(find src/main/java/com/shop/domain -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
else
  domain_packages_count="0"
fi

commit_hash="$(git rev-parse --short HEAD)"

echo "as_of_commit=${commit_hash}"
echo "java_files=${java_files_count}"
echo "html_templates=${html_templates_count}"
echo "java_packages=${java_packages_count}"
echo "domain_packages=${domain_packages_count}"

echo
echo "# markdown"
printf -- '- as of commit `%s`\n' "$commit_hash"
echo "- Java 파일 수: ${java_files_count} (src/main/java/**/*.java)"
echo "- 템플릿 파일 수: ${html_templates_count} (src/main/resources/templates/**/*.html)"
echo "- Java 패키지 수: ${java_packages_count} (src/main/java 하위 실제 package 디렉터리 기준)"
echo "- 도메인 패키지 수: ${domain_packages_count} (src/main/java/com/shop/domain/* 기준)"

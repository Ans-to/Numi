#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts/apk
count=0
for variant in debug release; do
  for file in app/build/outputs/apk/"$variant"/*.apk; do
    [ -f "$file" ] || continue
    name="${file##*/}"
    cp "$file" "artifacts/apk/calc-vault-${name#app-}"
    count=$((count+1))
  done
done
if [ "$count" -eq 0 ]; then echo 'No APK has been built. Run Gradle successfully first.' >&2; exit 1; fi
(cd artifacts/apk && sha256sum ./*.apk > SHA256SUMS)
printf 'Copied %s APK(s) to %s/artifacts/apk/\n' "$count" "$PWD"

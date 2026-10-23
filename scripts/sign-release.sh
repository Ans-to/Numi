#!/usr/bin/env bash
set -euo pipefail
: "${CALCVAULT_KEYSTORE:?Set the owner keystore path}"
: "${CALCVAULT_KEY_ALIAS:?Set the owner key alias}"
: "${CALCVAULT_STORE_PASSWORD:?Set the keystore password in the environment}"
: "${CALCVAULT_KEY_PASSWORD:?Set the key password in the environment}"
: "${ANDROID_HOME:?Set Android SDK path}"
cd "$(dirname "$0")/.."
input="${1:-app/build/outputs/apk/release/app-release-unsigned.apk}"
[ -f "$input" ] || { echo 'Unsigned APK does not exist.' >&2; exit 1; }
mkdir -p artifacts/apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 "$input"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign --ks "$CALCVAULT_KEYSTORE" --ks-key-alias "$CALCVAULT_KEY_ALIAS" \
  --ks-pass env:CALCVAULT_STORE_PASSWORD --key-pass env:CALCVAULT_KEY_PASSWORD \
  --out artifacts/apk/calc-vault-release.apk "$input"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs artifacts/apk/calc-vault-release.apk
(cd artifacts/apk && sha256sum ./*.apk > SHA256SUMS)

#!/usr/bin/env bash
# Smoke-test the exact minified build from the build job, using a throwaway CI test
# certificate. The owner's real release key never enters CI. No real user data exists.
set -euo pipefail
unsigned=$(find smoke-input -name app-release-unsigned.apk -print -quit)
[ -n "$unsigned" ] || { echo 'Unsigned release artifact missing.' >&2; exit 1; }
mkdir -p app/build/reports/release-smoke
output=app/build/reports/release-smoke
# Never run an uninstalling smoke test against someone's real phone or vault.
[ "${CI:-}" = "true" ] || { echo 'This destructive fixture is CI-only.' >&2; exit 1; }
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = "1" ] || { echo 'A disposable emulator is required.' >&2; exit 1; }
smoke_dir=$(mktemp -d)
smoke_store="$smoke_dir/test-signing.p12"
trap 'rm -f "$smoke_store"; rmdir "$smoke_dir"; unset SMOKE_PASSWORD' EXIT
export SMOKE_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
keytool -genkeypair -storetype PKCS12 -keystore "$smoke_store" -alias ci-smoke \
  -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Disposable Calc Vault CI' \
  -storepass:env SMOKE_PASSWORD -keypass:env SMOKE_PASSWORD -noprompt
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign --ks "$smoke_store" --ks-type PKCS12 \
  --ks-key-alias ci-smoke --ks-pass env:SMOKE_PASSWORD --key-pass env:SMOKE_PASSWORD \
  --out "$output/release-smoke.apk" "$unsigned"
if adb shell pm path com.calcvault | grep -q "package:"; then adb uninstall com.calcvault; fi
adb install "$output/release-smoke.apk"
adb logcat -c
adb shell am start -W -n com.calcvault/.MainActivity | tee "$output/launch.txt"
grep -q 'Status: ok' "$output/launch.txt"
# UIAutomator waits for idle, then reads accessibility metadata on this disposable device.
adb shell uiautomator dump /sdcard/calc-vault-smoke.xml
adb pull /sdcard/calc-vault-smoke.xml "$output/window.xml"
grep -q 'More options' "$output/window.xml"
adb logcat -d -b crash > "$output/crashes.txt"
if grep -q 'com.calcvault' "$output/crashes.txt"; then echo 'Release process crashed.' >&2; exit 1; fi
rm "$output/release-smoke.apk"
echo '::notice title=Release smoke test::Minified release installed and displayed Calculator on API 35; no process crash. CI test certificate only; owner release is signed separately offline.'

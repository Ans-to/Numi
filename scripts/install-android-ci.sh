#!/usr/bin/env bash
set -euo pipefail
sdk="${ANDROID_HOME:-${RUNNER_TEMP:-/tmp}/android-sdk}"
if [ ! -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$sdk/cmdline-tools"
  archive="${RUNNER_TEMP:-/tmp}/android-commandline.zip"
  curl --fail --location --retry 2 https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o "$archive"
  stage=$(mktemp -d)
  unzip -q "$archive" -d "$stage"
  mv "$stage/cmdline-tools" "$sdk/cmdline-tools/latest"
  rm -rf "$stage" "$archive"
fi
export ANDROID_HOME="$sdk"
export PATH="$sdk/cmdline-tools/latest/bin:$sdk/platform-tools:$PATH"
# yes exits with SIGPIPE once sdkmanager has read the licenses; only sdkmanager's status matters.
set +o pipefail
yes | sdkmanager --licenses >/dev/null
set -o pipefail
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
echo "ANDROID_HOME=$sdk" >> "$GITHUB_ENV"
echo "ANDROID_SDK_ROOT=$sdk" >> "$GITHUB_ENV"
echo "$sdk/cmdline-tools/latest/bin" >> "$GITHUB_PATH"
echo "$sdk/platform-tools" >> "$GITHUB_PATH"

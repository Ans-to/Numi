# Verification record — 1.0.1

Date: **2026-10-23**. Application/test revision: **`5ddfbc00e01eb3bf4773f570860ac0b486bbadfc`**.

**PASS:** [Full Android verification run](https://github.com/Ans-to/Numi/actions/runs/39918852620).

| Check | Result |
|---|---|
| Standalone Kotlin/JVM suite, 128 MiB heap | **39 passed**, including 256 MiB streamed import |
| `:core:test` and `:app:testDebugUnitTest` | **Passed** (one JUnit case in each module; core case runs all 39 checks) |
| `:app:lintDebug`, `:app:lintRelease` | **Passed** |
| Debug, unsigned release and instrumentation APK builds | **Passed** |
| API 35 x86_64 instrumentation | **8 passed**, 0 failures/errors/skips |
| Minified release install/launch on API 35 | **Passed**; actual calculator UI, no process crash |
| Offline owner-signed release verification | **Passed**, v2/v3 signatures; original RSA-3072 certificate |
| Final APK manifest/permissions | **Passed**; package com.calcvault, version 1.0.1/code 2, API 26–36, release non-debuggable, backup disabled, single-task activity; no Internet, network-state, wake-lock, or broad storage permissions |
| Native library ZIP offsets and ELF load alignment | **Passed**, 16 KiB for all four packaged ABIs |

The release smoke check uses a disposable CI certificate on the minified build. Owner signing happens locally, without uploading the private key. All original ZIP entry contents in the signed APK were compared byte-for-byte with the verified unsigned CI APK; only signing metadata was added. The owner-signed update has **not** been installed on a physical phone in this workspace.

## Eight executed Android tests

1. Ordinary digits are visible before equals; no normal-screen vault banner; arithmetic and secure-window flag.
2. Only a raw numeric PIN unlocks; an arithmetic expression containing that PIN does not; actual activity backgrounding relocks.
3. Unlock cooldown leaves digits visible and arithmetic usable, without announcing a vault-specific error.
4. **Actual DocumentsUI picker** selects a shell-owned PNG. Auto-lock, callback delivery, activity recreation, PIN unlock, automatic import, exact decrypted bytes, decoded pixels, internal preview, and continued existence of the source are checked.
5. **Actual Android Photo Picker** exercises the same complete flow. The import is identified by content digest because Photo Picker intentionally exposes a numeric filename. These are real picker results, not injected callbacks or file:// repository shortcuts.
6. Real Keystore-backed PIN record, multi-chunk import, duplicate skip, rename, folder move, lock invalidation of readers, catalog reopening, and precise deletion preserving another item and the original source.
7. Missing-source failure yields an actionable safe category without leaking a private path or publishing a catalog item.
8. Manifest permission, backup exclusion, and single-task assertions.

Picker fixtures are inserted/written by the **shell**, not by the target app. The app reads the selected content URI using the system-provided grant. The picker tests require API 31+ for their test-only shell stdin helper; this run used API 35. Test infrastructure only dismisses the emulator's unrelated **Pixel Launcher** ANR dialog, never Calculator or picker failures.

**Safety:** instrumentation resets app-private test storage. Run it only on a disposable emulator/test installation, never an installation containing real vault files.

## Core checks and local constraints

The 39 checks cover decimal arithmetic, precedence, parentheses, negatives, percentages, invalid/oversized input, PIN format/KDF/salts/throttling, session timeout boundaries, chunk authentication, exact round trips, random seeks, wrong keys/identities, tampering/truncation/extra bytes, interrupted imports and bounded-memory large-file processing.

Local command used:

```bash
JAVA_HOME=/home/user/.cache/calcvault-tools/python/jdk4py/java-runtime \
KOTLIN_HOME=/home/user/.cache/calcvault-tools/kotlin/node_modules/kotlin-compiler \
./scripts/test-core.sh
```

The sandbox cannot download the Android SDK/Gradle dependencies from their normal hosts, so Android compilation/lint/device tests ran in the authorized GitHub workflow, not locally. APK bytes were transported through Git blobs with independently checked SHA-256 digests because artifact CDN access is restricted. No APK was committed to the source tree and no signing key was uploaded. `git diff --check` also passed.

Records are in `artifacts/reports/`: `core-checks-v1.0.1.txt`, `android-ci-v1.0.1.json`, `build-annotations-v1.0.1.json`, `device-test-annotations-v1.0.1.json`, `release-signature-v1.0.1.txt`, and `apk-inspection-v1.0.1.json`.

## What changed after the phone report

The historical [1.0.0 green run](https://github.com/Ans-to/Numi/actions/runs/39905579540) contained only four Android tests and bypassed actual picker transitions. It did **not** prove imports worked through the UI. Opening a picker removed the vault composable and unregistered its result handler. Launchers now belong to the activity. Translucent photo pickers also exposed a lock-timer edge case; the timer now starts explicitly when handing control to a picker.

Earlier correction runs exposed test selector/fixture issues, the emulator launcher ANR, and Photo Picker's anonymized filenames. Those failures were investigated and corrected, not counted as passes. The final run linked above passed the full suite and release smoke check.

## Remaining physical/manual coverage

- Biometric enrollment, cryptographic unlock/cancel/lockout, enrollment changes and fallback on actual supported hardware.
- Physical/OEM/cloud-provider picker behavior, provider revocation, low storage and multi-gigabyte video imports.
- API 26 and other representative Android versions, accessibility, large fonts, tablets and portrait/landscape layouts.
- Real reboot, process kill at each commit boundary, power-loss durability, hardware storage failure and recovery limits.
- Media codecs, video/PDF/zoom/rotation behavior, rapid backgrounding, and screenshot/task-preview protection across devices.
- Pending selections are memory-only; after process death reselect originals. No backup/export/recovery feature or independent security audit is claimed.

# Verification record — 1.0.2

Date: **2026-10-23**. Application/test revision: **`5a3968f4cbc81f3a31f4173c1ec7399aaa6ab392`**.

**PASS:** [Full Android verification run](https://github.com/Ans-to/Numi/actions/runs/39925199885).

| Check | Result |
|---|---|
| Standalone Kotlin/JVM suite, 128 MiB heap | **39 passed**, including 256 MiB streamed import |
| `:core:test` and `:app:testDebugUnitTest` | **Passed** (one JUnit case in each module; core case runs all 39 checks) |
| `:app:lintDebug`, `:app:lintRelease` | **Passed** |
| Debug, unsigned release and instrumentation APK builds | **Passed** |
| API 35 x86_64 instrumentation | **16 passed**, 0 failures/errors/skips |
| Minified release install/launch on API 35 | **Passed**; actual calculator UI, no process crash |
| Offline owner-signed release verification | **Passed**, v2/v3 signatures; original RSA-3072 certificate |
| Final APK manifest/permissions | **Passed**; package com.calcvault, version 1.0.2/code 3, API 26–36, release non-debuggable, backup disabled, single-task activity; no Internet, network-state, wake-lock, or broad storage permissions |
| Native library ZIP offsets and ELF load alignment | **Passed**, 16 KiB for all four packaged ABIs |

The release smoke check uses a disposable CI certificate on the minified build. Owner signing happens locally, without uploading the private key. All original ZIP entry contents in the signed APK were compared byte-for-byte with the verified unsigned CI APK; only signing metadata was added. The owner-signed update has **not** been installed on a physical phone in this workspace.

## Sixteen executed Android tests

1. Ordinary digits are visible before equals; no normal-screen vault banner; arithmetic and secure-window flag.
2. Only a raw numeric PIN unlocks; an arithmetic expression containing that PIN does not; actual activity backgrounding relocks.
3. Unlock cooldown leaves digits visible and arithmetic usable, without announcing a vault-specific error.
4. **Actual DocumentsUI picker** selects a shell-owned PNG. Auto-lock, callback delivery, activity recreation, PIN unlock, automatic import, exact decrypted bytes, decoded pixels, internal preview, and continued existence of the source are checked.
5. **Actual Android Photo Picker** exercises the same complete flow. The import is identified by content digest because Photo Picker intentionally exposes a numeric filename. These are real picker results, not injected callbacks or file:// repository shortcuts.
6. Real Keystore-backed PIN record, multi-chunk import, duplicate skip, rename, folder move, lock invalidation of readers, catalog reopening, and precise deletion preserving another item and the original source.
7. Missing-source failure yields an actionable safe category without leaking a private path or publishing a catalog item.
8. Manifest permission, backup exclusion, and single-task assertions.

9. Move via actual DocumentsUI, including auto-lock/recreation: selection/import/review leave the original present; explicit confirmation removes it from shared storage and the MediaStore image query, while exact vault bytes remain readable.
10. **Keep originals** in the confirmation preserves the source and encrypted copy.
11. A source modified after import is not removed.
12. Deleting the vault copy before confirmation blocks original removal.
13. Tampering with encrypted media before confirmation blocks original removal.
14. Corrupting the persisted encrypted catalog (while session keys remain in memory) blocks original removal.
15. An earlier copy, renamed inside the vault, can be reselected with Move: it is reverified/reused without adding another item, the confirmation names the original correctly, and only that original is removed.
16. Source removal refuses raw file:// paths; it never falls back to File.delete on arbitrary paths.

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

Records are in `artifacts/reports/`: `core-checks-v1.0.2.txt`, `android-ci-v1.0.2.json`, `build-annotations-v1.0.2.json`, `device-test-annotations-v1.0.2.json`, `release-signature-v1.0.2.txt`, and `apk-inspection-v1.0.2.json`.

## Previous behavior and new consent boundary

The [1.0.1 verification](https://github.com/Ans-to/Numi/actions/runs/39918852620) fixed calculator/picker behavior but intentionally copied files without deleting originals. Version 1.0.2 retains that copy behavior and adds a separate opt-in Move flow. The user approved this feature; importing alone still never authorizes deletion.

The new deletion protections are exercised through real Android file-picker grants and synthetic shell-owned media. No real user originals were used or removed during development. Provider/cloud deletion semantics and races are documented in SECURITY.md. Selecting, reviewing, and declining removal all leave the source present in the executed tests.

## Remaining physical/manual coverage

- Biometric enrollment, cryptographic unlock/cancel/lockout, enrollment changes and fallback on actual supported hardware.
- Physical/OEM/cloud-provider picker behavior, provider revocation, low storage and multi-gigabyte video imports.
- API 26 and other representative Android versions, accessibility, large fonts, tablets and portrait/landscape layouts.
- Real reboot, process kill at each commit boundary, power-loss durability, hardware storage failure and recovery limits.
- Media codecs, video/PDF/zoom/rotation behavior, rapid backgrounding, and screenshot/task-preview protection across devices.
- Pending selections/removal reviews are memory-only; after process death reselect originals. No deletion is automatically resumed. No backup/export/recovery feature or independent security audit is claimed.

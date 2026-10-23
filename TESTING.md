# Verification record

Development date: 2026-10-23. **Final verification: PASS.**

Application-source revision: `c1aee48` (later documentation-only commits do not change application/test code). [Successful full CI run](https://github.com/Ans-to/Numi/actions/runs/39905579540).

| Check | Result |
|---|---|
| Standalone Kotlin/JVM checks, 128 MiB heap | **39 passed**, including a 256 MiB streaming import |
| `:core:test` | **Passed** (one JUnit case executes all 39 core checks) |
| `:app:testDebugUnitTest` | **Passed** (one app-module JUnit case) |
| `:app:lintDebug`, `:app:lintRelease` | **Passed**, no blocking lint errors |
| `:app:assembleDebug`, `:app:assembleRelease` | **Passed**, real APKs generated |
| `:app:assembleDebugAndroidTest` | **Passed** |
| `:app:connectedDebugAndroidTest`, API 35 x86_64 | **4 passed**, 0 failures, 0 errors, 0 skipped |
| Minified release install/launch on API 35 | **Passed**; UIAutomator confirmed Calculator, no process crash |
| Owner-signed release verification | **Passed**; APK Signature Schemes v2 and v3, RSA-3072 owner key |
| Final APK manifest inspection | **Passed**; API 26/36, release non-debuggable, single-task activity, backup disabled |
| Permission inspection | **Passed**; no Internet, network-state, wake-lock, or broad storage permission |
| Native library ZIP offsets and ELF load alignment | **Passed**, 16 KiB for all packaged ABIs |

The emulator release smoke test uses a disposable CI certificate on the exact minified unsigned build, **not** the owner's private key. The delivered APK is signed separately in the owner workspace and verified with Android SDK 36's `apksigner`. The owner-signed binary itself has not been installed on a physical phone.

Earlier failures (SDK provisioning, Kotlin syntax/API usage, a Compose lint finding, and a CI smoke-helper keystore assumption) were corrected. Superseded verification runs were cancelled when source was updated. The final full run above is green; there are no remaining failed automated checks in that run.

## Executed locally

`JAVA_HOME=/home/user/tools/python/jdk4py/java-runtime KOTLIN_HOME=/home/user/tools/kotlin/node_modules/kotlin-compiler ./scripts/test-core.sh`

**39 checks passed**, using real Kotlin 2.2.21/JVM cryptography, not Python reimplementations or UI mocks. Checks cover:

- Four arithmetic operations, decimal accuracy, precedence, parentheses, unary negatives, percentages, Unicode keypad operators, leading zeros, repeating fractions.
- Division by zero, malformed/oversized/over-nested input rejected safely.
- Correct/incorrect PINs, leading-zero PINs, invalid PIN formats, randomized salts, rejection of arithmetic as PIN candidates, progressive persisted-attempt deadlines and clock rollback.
- Immediate background locking, grace periods, expiration, process-start locked state, monotonic rollback.
- Ciphertext not containing source plaintext; exact import/decrypt equality; empty and exact-chunk-boundary imports; >12 MiB multi-chunk input, random seeks, and a 256 MiB streaming import under a 128 MiB JVM heap.
- Wrong keys/identities, modified ciphertext/header, missing end marker, trailing data and truncation rejected.
- Interrupted/cancelled imports leave no partial valid item or plaintext staging; reopened reader verifies original content; closed readers reject access; invalid names rejected.

Also executed: `git diff --check`, shell syntax checking for the build/signing helpers, and Python syntax checking for CI helper scripts.

The local combined Gradle command was attempted and failed **before tasks ran** because the sandbox cannot download the Gradle distribution. The sandbox initially had no Java/Android tools; a Java runtime and Kotlin compiler were installed from allowed registries to execute the standalone suite. No local emulator or physical device is available.

## Remote Android verification

The owner authorized pushing only `arena/d236bb71-numi` and running GitHub Actions. The workflow installed SDK 36, ran actual Gradle unit tests, lint, debug/release APK generation and instrumentation compilation, then successfully ran an API 35 emulator. The native app was installed and launched, not represented by a web demonstration.

Initial remote attempts exposed environment and compilation problems; SDK provisioning and source were corrected rather than treating those attempts as passed. Build and device-test statuses are retained in `artifacts/reports/android-ci.json` and `device-test-annotations.json`. GitHub artifact CDNs are blocked in the sandbox, so public APK bytes were retrieved through temporary, unreferenced Git blobs and checked against CI SHA-256 notices. No APK binary was added to source history, and no signing key was uploaded.

**Safety:** instrumentation resets app-private test storage. Run it only on a disposable emulator/test installation, never an installation holding real vault files.

Instrumentation source includes:

- Real Android Keystore-backed PIN record, correct/wrong/expression attempts.
- Verified multi-chunk import through ContentResolver, exact duplicate skip, rename, folder move, lock rejection of existing readers, catalog re-open, and precise deletion preserving another item and the source.
- No Internet/general-storage permission and backup-disabled manifest assertions.
- Actual activity launch, calculator interaction, secure window flag, PIN unlock and activity background auto-lock.

## Required manual/device checks not replaced by unit tests

- Biometric enrollment and CryptoObject unlock/cancel/lockout, enrollment-change invalidation, and fallback on a supported physical device.
- Real picker grants, provider revocation, cloud-provider cancellation, low-storage device behavior and truly large multi-gigabyte video imports.
- Device reboot (not just repository reconstruction), process-kill at every commit boundary and power-loss durability.
- API 26 device compatibility, representative OEM/Android versions, accessibility services, large fonts, portrait/landscape/tablet layouts and media codec variations.
- Private media playback/PDF/zoom behavior across rotation, rapid backgrounding, corrupted source and encrypted media, lock timeouts, and recent-task/screenshot behavior.
- Verify release signing with the owner certificate, install the **signed release** on the target device, and retest real use with disposable files before storing anything important.
- Independent review of the key hierarchy, chunk protocol, Android platform integrations and threat model.

The Android integration fixture creates a plaintext test source in **test-only private cache**, verifies that production import never deletes it, and removes it in `finally`. Production has no plaintext import/cache/export writer. Tests cannot prove an absence of OS memory copies or forensic recoverability on compromised devices.

## Delivered APK identities

- Debug: `artifacts/apk/calc-vault-debug.apk`
  - SHA-256: `fe06989908221175dd6032786e3764fc384d3d63da98cdd43fff25fb459cda82`
- Owner-signed release: `artifacts/apk/calc-vault-release.apk`
  - SHA-256: `b69e9970c9d48935d78e4cf9cdd28a3a0d2e66c0526d5283de12796629a3670d`
- Release signer certificate SHA-256: `ca25ab3f87f924b8ede188a89551130bfb9a69c1d3a756e1c43e0bbe19ea0320`

Version 1.0.0, package `com.calcvault`, min SDK 26, target SDK 36. Certificate fingerprints are public verification information, not private keys. Full byte counts and ABI/manifest details are in `artifacts/reports/apk-inspection.json` and `build-provenance.json`.

# Verification record

Development date: 2026-10-23. See the final summary below before relying on the build.

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

The owner authorized pushing only `arena/d236bb71-numi` and running GitHub Actions. The workflow installs SDK 36, invokes actual Gradle unit tests, lint, debug/release APK generation, and instrumentation compilation, then attempts an API 35 emulator run.

Initial remote attempts exposed a missing SDK manager in the runner and a Kotlin input-handler syntax error. The SDK provisioning and source were corrected, rather than treating those attempts as passed. Final status and APK hashes are recorded after verification; until then, remote success is not claimed.

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

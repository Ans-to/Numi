# Calc Vault

A native, offline Android calculator with PIN-gated, encrypted private storage. Kotlin, Jetpack Compose / Material 3, MVVM, minimum Android 8.0 (API 26). Launcher name: **Calculator**.

> **1.0.1 verified 2026-10-23:** normal calculator digits are visible immediately, and system picker callbacks survive concealment, locking and activity recreation. All **eight API 35 instrumentation tests**, including actual photo/document picker imports, plus JVM tests, debug/release lint, and a minified-release launch check passed. The previous preview missed the picker lifecycle defect reported on a phone. See [TESTING.md](TESTING.md) for exact coverage and remaining device limitations.

## Delivered files

In the owner workspace `/home/user/Numi`:

| Deliverable | Location |
|---|---|
| **Owner-signed release APK** (non-debuggable; recommended) | `artifacts/apk/calc-vault-release.apk` |
| Debug APK (disposable testing only) | `artifacts/apk/calc-vault-debug.apk` |
| Complete source archive (no keys, SDK, or build caches) | `artifacts/calc-vault-source.zip` |
| APK checksums | `artifacts/apk/SHA256SUMS` |
| CI results, APK inspection and signature records | `artifacts/reports/` |

These generated outputs are intentionally not committed to Git. CI artifacts contain debug and **unsigned** release builds; owner signing happens offline. The protected owner key and password are outside this checkout at `/home/user/CalcVault-owner-signing/`. Back up that material securely and move the password to a password manager. It cannot recover an on-device vault key.

**[Download the owner-signed 1.0.1 APK](https://github.com/Ans-to/Numi/releases/download/v1.0.1-preview.1/calc-vault-release.apk)** · [Release and checksum](https://github.com/Ans-to/Numi/releases/tag/v1.0.1-preview.1)

Verified application/test revision: `5ddfbc0`. [Successful full Android verification](https://github.com/Ans-to/Numi/actions/runs/39918852620). This supersedes `v1.0.0-preview.1`; the old release is preserved. Version code **2**, package **com.calcvault**, original owner signing certificate and unchanged encrypted storage format. **Install the owner-signed update over the existing owner-signed app; do not uninstall or clear data.**

## Open and build

Open the repository root in a recent stable Android Studio compatible with AGP 8.13.2. Use **JDK 17**, Android SDK **36**, build-tools **36.0.0**, and the checked-in Gradle **8.13** wrapper. Kotlin is pinned to **2.2.21** and Compose to BOM **2025.12.01**; dependencies are pinned, not dynamic versions. Android Studio's SDK manager can install these components.

For command-line builds / VS Code:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
# Or set sdk.dir=/absolute/path/to/android-sdk in untracked local.properties.
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease
./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest  # disposable emulator/test install ONLY; fixtures reset app data
./scripts/collect-apks.sh
```

On Windows use `gradlew.bat` and set the equivalent environment variables. Dependency resolution needs Google's Maven repository, Maven Central, Gradle Plugin Portal and Gradle's distribution host; these are **build-time** requirements. The installed app has no Internet permission and needs no server.

### APK outputs

- Gradle debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Gradle unsigned release APK, if no owner key is configured: `app/build/outputs/apk/release/app-release-unsigned.apk`
- Signed release APK, if owner signing environment variables are supplied: `app/build/outputs/apk/release/app-release.apk`
- `scripts/collect-apks.sh` copies available APKs into ignored `artifacts/apk/` with SHA-256 hashes. It fails if no APK exists; it never creates a fake APK.
- CI uploads APKs and test/lint reports as `calc-vault-build`, and device reports as `device-test-reports`.

A debug APK is installable but debuggable: **do not use it for real sensitive content**. An unsigned release cannot be installed until signed. Debug and owner-release signatures differ: switching requires uninstalling and loses all app-private data. Test with disposable data first.

## Use

1. Launch Calculator. Ordinary arithmetic is available immediately. For private storage, open **More options (⋮) → Set up private storage**, enter a **6–12 digit PIN**, press `=`, repeat it and press `=` again. Leading zeros are significant. There is no PIN recovery.
2. Enter the PIN on the calculator keypad and press `=` to open the vault. Only an entire, unsigned digit sequence is checked: `123456+0`, `(123456)` and `123456%` are calculations, never unlock attempts.
3. **Normal calculator display:** digits appear as you type, including a PIN entered for unlock. Only explicit PIN setup/confirmation/change screens mask their inputs. No PIN is persisted or logged, and there is no calculator history. Wrong PINs and entries during the cooldown behave as ordinary calculations without a vault-specific error dialog. There is no vault banner or fingerprint button on the normal keypad; optional biometric unlock is in the overflow menu. Repeated unsuccessful raw 6–12 digit entries can delay unlocking for up to five minutes; calculations still work during that cooldown.
4. Arithmetic supports `+`, `−`, `×`, `÷`, decimal points, unary negatives, parentheses, postfix `%`, `AC`, and backspace. `%` means divide by 100, so `200×5%` gives `10`; `200+5%` gives `200.05`. Decimal128 arithmetic avoids binary floating-point display artifacts; repeating fractions are rounded to 34 significant digits.
5. Import through **Photos & videos** (Android Photo Picker with system fallback) or **Documents & other files** (Storage Access Framework). No broad storage permissions are requested. Unlock after returning from the picker if auto-lock fired; encryption and verification then start automatically. Progress is displayed, successful/duplicate files are counted, and failed selections remain available for **Retry import** with actionable error messages. Activity recreation retains the pending selection; after a process kill, reselect the originals. Android Photo Picker may supply numeric filenames rather than original names; files can be renamed inside the vault. Select at most 100 files per batch. Keep the app foregrounded during import.
6. Browse categories, search filenames, sort by newest/name/type/size, create folders, and long-press or use a card's selection circle for bulk move/delete. Rename is available for a single selected item. Exact-byte duplicates are skipped even when the names differ. Same-name, different-content files are retained separately.
7. Photos support pinch/double-tap zoom and swiping. Videos use Media3 with play/pause/seek and a volume slider. PDFs have paged, zoomable rendering. Text previews are UTF-8 and bounded to 1 MiB. Unsupported file types can be stored but not externally opened or exported in this version; this avoids handing plaintext to another app. Supported codecs depend on the device.
8. Settings provide PIN change, strong biometric unlock, light theme, and immediate/15-second/1-minute background lock. Biometric access must be explicitly enrolled while unlocked. It uses a cryptographic Android biometric prompt, not just a success callback. PIN access remains available if biometric enrollment changes.
9. Tap the lock icon or back out of the vault to return to the calculator. Every process start is locked. Private UI is concealed as soon as the activity pauses, even within a nonzero grace period.

## Security and data handling

See [SECURITY.md](SECURITY.md) for the threat model, file format, durability boundaries, and limitations.

- AES-256-GCM authenticated encryption. Each imported file receives a fresh random 256-bit key. Keys and all sensitive metadata are held in a catalog encrypted by a non-exportable Android Keystore key.
- Files are encrypted in 1 MiB authenticated chunks, with file identity, format header, chunk index and length bound as associated data. An authenticated end marker detects truncation; wrong keys, modification, reordering and extra bytes are rejected.
- Import is streamed to ciphertext-only staging, synced, fully decrypted/read back for SHA-256 comparison, and only then added to the atomic encrypted catalog. Interrupted imports are not shown as valid files. Sources are **never deleted**.
- There are **no plaintext temporary files**, disk thumbnails, public exports, file-sharing providers, or media disk caches. Images/text/PDF pages are decoded into bounded memory. Video and PDF random access decrypt authenticated chunks in memory.
- PINs are salted PBKDF2-HMAC-SHA256 verifiers (600,000 iterations), further protected inside a Keystore-encrypted record. No raw PIN is persisted. PIN candidates are rate-limited persistently, reserving an attempt before verification.
- No accounts, ads, tracking, network permission, custom keyboard, notifications, or diagnostic content logging. Screenshot/task-preview protection (`FLAG_SECURE`) applies to the whole activity. App data is in no-backup private storage with cloud and device-transfer exclusions.

**Keep separate originals.** Uninstalling, clearing app data, losing the Keystore key, forgetting the PIN without already-enrolled biometrics, or storage corruption can cause permanent loss. There is no recovery key, encrypted backup/export feature, remote service, or reset bypass. A rooted/compromised device, runtime instrumentation, accessibility malware, or an attacker observing an unlocked device is outside this app's protection. JVM/Android memory cannot be reliably forensically erased. No independent security audit has been performed.

## Owner release signing

Never commit a signing key or password. No owner key is embedded in the source or CI workflow.

Generate a long-lived release key **outside this checkout**, with restricted permissions:

```bash
./scripts/create-owner-key.sh /secure/path/calc-vault-owner
```

The script generates a random password and a 3072-bit RSA signing key. It writes `owner.p12` and `owner.password` with owner-only permissions; it does not print the password. Move the password to a password manager and keep independent encrypted backups of the key. Losing the signing key prevents updating existing installs; a new signing key is not interchangeable.

Configure a signed Gradle build without command-line secrets:

```bash
export CALCVAULT_KEYSTORE=/secure/path/calc-vault-owner/owner.p12
export CALCVAULT_KEY_ALIAS=owner
export CALCVAULT_STORE_PASSWORD="$(cat /secure/path/calc-vault-owner/owner.password)"
export CALCVAULT_KEY_PASSWORD="$CALCVAULT_STORE_PASSWORD"
./gradlew :app:assembleRelease
unset CALCVAULT_STORE_PASSWORD CALCVAULT_KEY_PASSWORD
./scripts/collect-apks.sh
```

Or sign an already built, zip-aligned unsigned release with the Android SDK's `apksigner` using `scripts/sign-release.sh`. The script requires those same environment variables and verifies the result. Store keys in your own CI secrets if enabling remote release signing; the provided workflow deliberately builds an **unsigned** release and never generates an ephemeral "production" key.

## Structure

```text
core/                 Pure JVM arithmetic, PIN KDF, lock timing, chunk encryption, import transaction
app/.../data/         Android Keystore, encrypted auth/preferences/catalog, vault repository
app/.../media/        Authenticated Media3 source, memory image decoding, proxy PDF descriptor
app/.../ui/           StateFlow MVVM, calculator, vault, viewers, settings, Material 3 theme
app/src/androidTest/  Real Android Keystore/import/launch/manifest tests
scripts/              Standalone core tests, CI diagnostics, APK collection and owner signing
.github/workflows/    Build, lint, unit tests, emulator verification, report/APK artifacts
```

No web frontend, wrapper, or application server is included or needed.

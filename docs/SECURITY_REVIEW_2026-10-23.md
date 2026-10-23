# Calc Vault security review — 23 October 2026

## Verdict

**The tested encryption resisted the simulated ciphertext attacks. The application is not “uncrackable.” An attacker who can execute code inside its process can bypass the PIN/biometric application gate without breaking AES.** This was already a documented design boundary; the new Android tests explicitly exercise it.

One new, reproducible **read-after-authentication-failure cache defect** was found and fixed on the review branch. It could return zeroed bytes when a caller retried an earlier chunk after encountering corruption; it did not reveal a key or decrypt attacker-forged ciphertext. The published 1.0.2 APK has **not** been replaced and does **not** contain this fix.

This is an implementation review and automated testing by the development agent, **not an independent security audit, physical-device penetration test, or security certification**.

## Scope and provenance

- Reviewed release: package `com.calcvault`, version **1.0.2 / code 3**, merged source `a53ab063158cc0bd8e1c80e5fdb7e138c58559fc`.
- Released APK SHA-256: `e892f880cb42950931c3820f67d99d96e00cc80ec2439875e6b1d94a9358c93b` (3,031,774 bytes). Retrieved again and verified during this review.
- Owner certificate SHA-256: `ca25ab3f87f924b8ede188a89551130bfb9a69c1d3a756e1c43e0bbe19ea0320`. Original APK passes v2/v3 verification.
- Review branch: `arena/ba993319-numi`; application fix and regression test: commit `46ea6d1`.
- Files inspected include the actual cipher, import transaction, PIN verifier and attempt policy, Keystore record storage, authentication and biometric repositories, vault repository/readers, media adapters, activity/ViewModel lifecycle, backup rules, and the **merged manifest extracted from the signed APK**.
- All attack fixtures were synthetic. No owner's PIN, real media, private signing key, or production installation was used. No live device clock was changed.

## Executed evidence

| Exercise | Result and interpretation |
|---|---|
| Existing core suite, run again against the fixed source | **39 checks passed**, including a 256 MiB streamed import under a 128 MiB JVM heap |
| New adversarial core suite | **16 scenario groups passed**; some groups intentionally demonstrate limitations rather than defenses |
| Header tampering | All **160** individual header-bit mutations rejected |
| Ciphertext/data-tag tampering | **256** seeded bit-mutation cases rejected |
| Mandatory final tag | All **128** individual tag-bit mutations rejected |
| Truncation / appended data | **64** seeded truncations and **2** append cases rejected |
| Malformed frame/catalog lengths | **10** cases rejected |
| Wrong key / wrong identity | **64** independently generated wrong AES-256 keys and **32** wrong item identities rejected |
| Additional chunk checks | Caller output unchanged on authentication failure; reordered, duplicated and cross-file transplanted chunks rejected; later corrupted chunks rejected when visited |
| Fresh imports | **128** repeated imports produced distinct sampled 256-bit keys, IDs and ciphertext hashes; this is a sample check, not an entropy certification |
| Independent interoperability | Python `cryptography` / OpenSSL authenticated and decrypted the JVM-produced multi-chunk file exactly; the production JVM reader authenticated and decrypted a separately Python-produced file exactly, including the empty final record |
| Cache regression | **Failed on unchanged merged-release core**, then **passed with the cache invalidation fix** |
| Conditional offline PIN guessing | A deliberately weak synthetic verifier was recovered after **64 guesses in 12.845 seconds** (~4.98 guesses/second) using the production 600,000-iteration verifier |
| Virtual online throttle | **297 allowed attempts in a simulated first 24 hours**; advancing the supplied wall clock beyond the deadline permitted another attempt |
| Signed APK tampering | Flipping exactly one byte inside the signed DEX payload made `apksigner verify` fail with a SHA-256 content-digest mismatch; the signing block was preserved |
| Released manifest inspection | Non-debuggable; backups disabled; no Internet, broad storage or wake-lock permission; no exported vault provider |
| Android build/lint/device verification | **Passed**; **20 API 35 tests**, 0 failures/errors/skips, plus minified release smoke test |

The core suite makes **722 expected rejection checks** across mutation, wrong-key/identity, framing, and chunk-failure scenarios. Repeated operations are not 722 separate JUnit tests. The original core suite and new scenario suite each have a JUnit wrapper.

### Important interpretation of the PIN benchmark

The bounded dictionary contained only `000000` through `000063`, and the test fixture intentionally used the last value. **We did not crack a real PIN or search all one million six-digit PINs.** The test started with the verifier and salt already available in memory. A copied encrypted `auth` file does not directly provide them: it is encrypted with the Keystore root key.

The measured rate is a local, warmed-up, sequential CPU sample—not an Android-device, GPU, multi-core or cracking-time guarantee. A person with in-process access can bypass the gate directly and does not need this dictionary attack at all.

Local environment: Linux x86_64, virtual Intel Xeon 2.60 GHz / 2 logical CPUs, Temurin JDK 17.0.9, Kotlin 2.2.21, Python 3.11.2, `cryptography` 50.0.2 with OpenSSL 4.0.3. JVM test heap: 128 MiB.

## Findings

### 1. High impact if the app process/OS trust boundary is compromised: the PIN does not wrap the content keys

**Status: architectural limitation; not changed.**

`SecretStore` creates `calcvault.root.v1` without `setUserAuthenticationRequired(true)`. That root key decrypts the catalog, which contains the file keys. The PIN verifier is an application check, not a cryptographic input to catalog/file-key decryption. The biometric challenge uses a separate authentication-bound key; it does not make the root key authentication-bound.

The Android boundary test performs a wrong PIN attempt, calls the repository directly from the app's context, imports a synthetic fixture, locks/reopens the repository, and reads the exact fixture bytes **without a successful PIN or biometric authentication**. Another test checks that the root key's public encoding is null but its `KeyInfo.isUserAuthenticationRequired` is false.

**Prerequisite matters:** instrumentation deliberately runs with app-context access. This is a simulation of an already-compromised process, not a demonstrated privilege-escalation exploit against an ordinary phone. An unrelated sandboxed app cannot simply invoke these Kotlin methods. Downloading or decompiling the APK does not reveal generated vault keys. Re-signing an APK with someone else's key normally cannot replace the owner's signed installation while retaining its private data.

Non-exportability prevents asking the public API for root-key bytes; it does not prevent authorized app-context code from asking Keystore to decrypt. Hardware backing varies by device, and emulator results do not establish physical hardware resistance.

### 2. Corruption/retry integrity defect: zeroed chunk cache could remain marked valid

**Status: reproduced against released source; fixed on this branch, not in the published APK.**

Reproduction:

1. Import more than one chunk; change a ciphertext byte in the second chunk.
2. Read an intact prefix of the first chunk, populating the cache.
3. Read the corrupted second chunk; GCM correctly rejects it.
4. Catch that error and retry the original prefix through the same reader.

Before the fix, the cache buffer had been cleared, but its index still identified the first chunk. The retry returned zeros instead of the original authenticated bytes. The regression raised:

```text
IllegalStateException: Authentication failure left a zeroed cache marked valid
```

The fix invalidates `cachedIndex` **before** clearing/decrypting. An authentication exception cannot leave the wiped cache marked valid, and retrying the first chunk reloads/authenticates it correctly. No file format, encryption key, PIN behavior, or storage migration changes. The tested failure requires corruption and a caller that continues using that reader after an error; it is not a key-recovery or arbitrary plaintext-forgery demonstration.

### 3. Application throttling is neither hardware-enforced nor rollback-proof

**Status: documented limitation; not changed.**

The policy correctly reserves attempts before verification and persists them. The Android test checks that a new `AuthRepository` still observes the cooldown. Replaying an older, valid encrypted auth record from app-UID context removes the newer cooldown. GCM authenticates content, not freshness; the replay requires privileged access to private app files.

The separate core simulation shows that a forward wall-clock jump can expire a deadline. Ordinary clock rollback fails closed. Neither observation means a normal app restart resets throttling. The PIN benchmark demonstrates the different, conditional offline-verifier threat.

### 4. Other practical limits remain

- Normal calculator digits—including the PIN typed for unlock—are intentionally visible. Shoulder-surfing and cameras remain realistic threats. Setup/change screens remain masked.
- Accessibility services approved by the user, process instrumentation and a compromised OS are outside the protection boundary. `FLAG_SECURE` is not a universal anti-spyware defense.
- File counts and lengths are unpadded. Names/content keys are encrypted in the catalog, but ciphertext sizes remain inferable to someone who can read the private directory.
- Authentication during random-access playback is per visited chunk. Opening/reading an intact prefix does not establish that every unvisited chunk is intact. Full verification runs during import.
- Managed memory, media decoder buffers and graphics buffers are not guaranteed perfectly erasable. No memory-dump penetration test was performed.
- The merged APK also contains AndroidX's non-exported startup provider and an exported profile-install receiver protected by `android.permission.DUMP`; the latter is not an ordinary-app-accessible vault endpoint.
- No built-in encrypted backup/export/recovery exists. Uninstalling, clearing data or losing the Keystore key can permanently lose vault contents. Do not use the vault as the only copy of irreplaceable data.
- Move-to-vault has explicit confirmation and copy/source/catalog verification, but provider-controlled trash/cloud behavior and the lack of an atomic SAF compare-and-delete primitive remain as documented in `SECURITY.md`.

## Threat assessment

| Attacker capability | Assessment |
|---|---|
| Has only a copied `.cv` file, no keys | Tested tampering/wrong-key attempts failed. No practical AES break found; testing cannot prove universal cryptographic security. |
| Has only the released APK | No embedded owner's vault key. Modified payload failed signature verification. Reverse engineering alone is not access to existing vault files. |
| Ordinary unrelated app on an uncompromised Android installation | Android sandbox, private storage and UID-scoped Keystore are essential barriers. No exposed vault-decryption component was found in the reviewed manifest; no external-app exploitation campaign was performed. |
| Observes the owner's PIN / takes an already-unlocked vault | Realistic access risk; strong encryption cannot fix observed credentials or an already-authorized session. |
| Can manipulate device time or replay private auth storage | Local cooldown can be undermined under the stated prerequisites. |
| Controls the app process or can invoke its Keystore operations | **PIN/biometric gate can be bypassed without breaking AES. Not protected by this architecture.** |
| Sophisticated physical forensics / OS exploit / malicious accessibility / codec exploit | Not certified or comprehensively tested. |

## Android execution record

**Review run:** https://github.com/Ans-to/Numi/actions/runs/39944385419 — revision `46ea6d1`.

**PASS:** build, JVM unit tests, debug/release lint, all APK builds, **20 API 35 instrumentation tests (0 failures, 0 errors, 0 skipped)**, and minified release install/launch smoke test. Build job `120383427323` completed in 7m16s; device job `120386252806` completed in 8m54s. The four added device tests all passed:

1. In-process vault access without successful PIN/biometric authentication.
2. Root key non-exportable via the public API, but not user-authentication-bound.
3. Cooldown survives repository recreation, but privileged encrypted-record replay bypasses it.
4. Record ciphertext bit tampering and cross-record substitution fail; repeated writes use distinct encrypted output.

These are added to the existing 16 API 35 tests. Instrumentation uses a disposable debug installation; the minified release smoke test uses a disposable CI certificate. Neither is a physical-device test of the owner's signed APK.

**Historical qualification:** release verification runs `39925199885` and `39927188479` passed previously. The merge-triggered duplicate `39940726576`, whose result was pending at merge, was checked during this review: build succeeded, but 1 of 16 device tests timed out waiting for PIN setup in `LaunchTest.ensurePin`. Its root cause was not established; it must not be described as a successful run. The first review run `39944194180` was superseded/cancelled; only completed results are counted here.

## Reproduction and artifacts

Set `JAVA_HOME` to JDK 17+ and `KOTLIN_HOME` to Kotlin 2.2.21. Make Java available on `PATH`, and install Python `cryptography` into your chosen isolated environment (this run used version 50.0.2).

```bash
./scripts/test-core.sh
./scripts/test-security.sh
```

`test-security.sh` compiles the **production core**, runs the adversarial scenarios with a 128 MiB heap, creates public synthetic fixtures, runs the independent Python/OpenSSL decoder/encryptor, then checks the reverse fixture through the production JVM reader. It writes only disposable generated data under ignored `core/build/` and `artifacts/`.

To reproduce the original defect without modifying the working source, extract `core/src/main/kotlin` from commit `a53ab06` into a temporary directory, compile those files with the current `SecurityChecks.kt`, and invoke `com.calcvault.core.SecurityChecks`. It fails the cache regression as recorded above. This exact baseline extraction was executed during the review.

CI executes `:core:test`, `:app:testDebugUnitTest`, debug/release lint, debug/release/instrumentation builds, API 35 instrumentation, and the minified launch smoke test. **Never run the existing instrumentation suite on an installation with real vault contents:** it resets disposable test storage.

Local evidence (ignored generated artifacts):

- `artifacts/security-review/core-results.txt`
- `artifacts/security-review/security-results.txt`
- `artifacts/security-review/released-source-regression.txt`
- `artifacts/security-review/release-signature.txt`
- `artifacts/security-review/release-manifest.txt`
- `artifacts/security-review/apk-tamper-result.txt`
- `artifacts/security-review/android-ci.json`, `build-annotations.json`, `device-annotations.json`

The durable evidence is this report, the committed executable tests, the regression fix, and the linked CI run. Generated logs/binaries are not committed; no signing secrets are included.

## Recommendation before calling the product final

1. Ship the tested cache fix in a separately authorized update, preserving the existing signing identity and app data. This review did not merge or publish a new release.
2. Describe the product as an encrypted local vault that trusts Android's sandbox/Keystore—not as root-proof, spyware-proof, or uncrackable.
3. Use a strong device lock, current Android patches, a long random vault PIN, immediate auto-lock, and trusted accessibility services only. A longer PIN does **not** repair the in-process key-access boundary.
4. If protection after app-process compromise but before owner authentication is required, separately design a migration to user-secret-derived key wrapping and/or OS-authentication-bound key access. Six-digit PINs have limited entropy; stronger user secrets, KDF cost, biometric fallback and recovery all require careful design. Such changes still cannot protect already-decrypted content from a compromised runtime.
5. Obtain an independent mobile security review and physical-device/biometric coverage before trusting the app with high-risk, irreplaceable sensitive material. Maintain independent secure backups.

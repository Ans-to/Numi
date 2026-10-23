## Calculator display and import fixes

This **1.0.1** update corrects the problems reported with `v1.0.0-preview.1`:

- Ordinary calculator layout, with digits visible immediately; only explicit PIN setup/change is masked. No vault banner or fingerprint button on the normal keypad.
- System picker callbacks survive concealment/locking and activity recreation. The selected files import automatically after unlocking.
- The configured lock timer also applies to translucent photo pickers.
- Import progress, per-file failure reasons and explicit retry for failed selections. Originals are never automatically deleted.

### Install as an update — do not uninstall

Download **calc-vault-release.apk** below. Package `com.calcvault`, version **1.0.1**, version code **2**, Android 8.0/API 26 or newer. It uses the **same owner signing key** as the previous public preview and the encrypted storage format is unchanged. Install it over the existing owner-signed app. **Do not uninstall or clear app data: that would destroy the existing vault.** Keep separate originals.

Normal calculator input now shows the PIN too while entering it for unlock, as requested. Setup/change screens remain masked. Android Photo Picker may supply numeric filenames; files can be renamed inside the vault.

### Verification

[Successful full run](https://github.com/Ans-to/Numi/actions/runs/39918852620), application/test source `5ddfbc00e01eb3bf4773f570860ac0b486bbadfc`:

- 39 core checks; Gradle JVM tests; debug/release lint and builds.
- **Eight API 35 instrumentation tests**, including real photo/document pickers, lock/recreation/unlock, exact decrypted bytes, decoded images, internal previews and source preservation.
- Minified-release install/launch smoke test.
- Owner-signed APK v2/v3 verification, version/permission and native alignment inspection.

The owner-signed binary has not been physically installed/tested in this workspace; OEM/cloud-provider, biometric hardware and other Android-version QA remain incomplete. This remains a preview, not an independent security certification. Full details are in `TESTING.md` and `SECURITY.md` in the source archive below.

**APK SHA-256:**
`18193c0ba3171c655f47786d3c88505cf0d9e54343c9419f49114382192c6f2b`

**Owner certificate SHA-256:**
`ca25ab3f87f924b8ede188a89551130bfb9a69c1d3a756e1c43e0bbe19ea0320`

The old release is preserved. No private signing key or password is included in this release or source.

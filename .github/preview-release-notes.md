## Optional Move to vault

Version **1.0.2**, code **3**, package `com.calcvault`, Android 8.0/API 26 or newer.

### Remove selected originals from shared storage

1. Install this APK **over the existing owner-signed app**. It uses the original owner signing certificate and unchanged vault storage format. **Do not uninstall or clear data.**
2. Unlock the vault, then choose **Import files → Move to vault**.
3. Select the originals using Android Files (Images, Videos, Downloads or your provider). Unlock again if auto-lock fired.
4. After import/verification, dismiss the result and choose **Review originals → Remove originals**.

Selection, importing and opening review do not delete anything. **Keep originals** cancels the pending removal. Existing byte-identical vault copies are reverified/reused, so originals imported with earlier releases can also be selected for Move.

The app checks the persisted encrypted catalog, decrypts/hashes the vault copy, then compares the current source before requesting deletion. Changed sources, missing/corrupted copies or catalog, and unsupported access fail closed. No broad gallery/storage permissions are added.

**Important:** the selected provider must permit deletion. Read-only sources may need manual removal. Deletion may be permanent or affect synced cloud files. Gallery trash, cloud backups, cached thumbnails and other copies may remain. Keep an independent secure backup; uninstalling/clearing the app destroys the vault. Do not edit sources while moving them; SAF has no atomic compare-and-delete operation.

The ordinary **Photos & videos** and **Documents & other files** options still copy without removing originals.

### Verified, with limits

[Successful full verification](https://github.com/Ans-to/Numi/actions/runs/39925199885), application/test source `5a3968f4cbc81f3a31f4173c1ec7399aaa6ab392`:

- **16 API 35 emulator tests passed**, including real copy/move pickers, confirmation/cancellation, source changes, missing/corrupted vault copies, damaged persisted catalog, existing duplicates, exact vault bytes and shared-storage/MediaStore removal checks.
- 39 standalone core checks, Gradle JVM tests, debug/release lint/builds, minified-release install/launch smoke check.
- Original owner v2/v3 signatures verified; final version/permissions/native alignment inspected; signed APK payload matches the tested unsigned CI build.

Physical/OEM/cloud-provider, biometric hardware and other Android-version testing remain incomplete. This is a preview, not an independent security certification. See TESTING.md and SECURITY.md in the source archive.

**APK SHA-256:**
`e892f880cb42950931c3820f67d99d96e00cc80ec2439875e6b1d94a9358c93b`

**Owner certificate SHA-256:**
`ca25ab3f87f924b8ede188a89551130bfb9a69c1d3a756e1c43e0bbe19ea0320`

Prior releases remain available. No private signing key or password is published.

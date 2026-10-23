## Download and install

Download **calc-vault-release.apk** from Assets below. This is the owner-signed, non-debuggable native Android application, version 1.0.0.

- Requires Android 8.0 / API 26 or newer; targets API 36.
- On Android, open the APK and allow installation from your browser/file manager if prompted.
- The installed launcher name is **Calculator**. Use the first-launch private-storage setup to choose your own PIN.
- If downloading on Windows, save the APK and transfer it to your Android phone. It is not a Windows executable.

## Verification

39 core checks, Gradle JVM tests, debug/release lint, APK builds, and all four API 35 instrumentation tests passed. The minified release installed and launched on an API 35 emulator using a disposable CI certificate. This downloadable APK was signed separately with the owner's release key and its APK Signature Scheme v2/v3 signatures were verified.

[Successful verification run](https://github.com/Ans-to/Numi/actions/runs/39905579540)

APK SHA-256:
```
b69e9970c9d48935d78e4cf9cdd28a3a0d2e66c0526d5283de12796629a3670d
```
Release signer certificate SHA-256:
```
ca25ab3f87f924b8ede188a89551130bfb9a69c1d3a756e1c43e0bbe19ea0320
```

## Preview limitations and data safety

Physical-device, biometric, API 26/OEM and full media/fault-injection testing remain pending. No independent security audit has been performed. Unsupported document formats are store-only; this version has no export or encrypted backup/restore feature. See README.md, SECURITY.md and TESTING.md for details.

**Test with disposable files first and keep separate originals. Uninstalling, clearing app data, or losing device encryption keys can permanently lose vault access.** Debug builds have a different signing certificate; switching between debug and this owner release generally requires uninstalling the previous app and loses its vault data.

Only the signed APK and checksum file are published. No private signing key or password is included.

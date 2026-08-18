# Aves Media Enhanced

An independent public build of Aves with Android MediaStore and file-operation enhancements.

## Current scope

- Explicit MediaStore rescan entry point
- Detection of stale media records after external deletion or relocation
- Safer move/copy handling with destination verification
- MediaStore refresh after file operations
- Preservation of video display names/extensions
- Fail-closed MediaStore cleanup: query failures and blank DATA columns do not wipe the catalog
- Source file extensions win over a misdetected `video/mp2t` MIME (no more `.ts` on move/rename)
- Serialized full reloads and incremental refreshes, including the complete MediaStore stream lifetime
- Mounted-volume and readable-directory proof before either a missing ID or path can delete a catalog record
- Scoped-storage move detection via volume identity + `RELATIVE_PATH` + `DISPLAY_NAME` instead of `DATA` on Android 10+
- Cancellable, suspending MediaScanner retries without blocking callback threads
- Android storage-access entry point

This project is based on the Aves upstream source and is independently maintained. It is not an official Aves release.

## Build

GitHub Actions runs analysis and tests, then builds signed, minimized release APKs split by ABI on pushes to `main`.

- Package ID: `io.github.jieoz.avesmediaenhanced.libre`
- Architectures: `arm64-v8a`, `armeabi-v7a`, `x86_64`
- Release signing certificate SHA-256: `DB:3A:B9:84:C4:3E:74:68:58:81:50:C2:A0:D9:88:6C:37:01:99:2A:76:A6:32:6D:7B:51:0B:BA:BD:89:86:6F`

The signing key is stored only as encrypted GitHub Actions secrets and is never exposed to pull-request jobs. CI uses the repository-pinned Flutter SDK and checksum-verified Gradle wrapper, runs Flutter and Android unit tests, and verifies the exact signed split APKs before upload. A release is published only after those artifacts are downloaded and their package, version, signer, architecture, integrity, and hashes are verified again.

# Aves Media Enhanced

An independent public build of Aves with Android MediaStore and file-operation enhancements.

## Current scope

- Explicit MediaStore rescan entry point
- Detection of stale media records after external deletion or relocation
- Safer move/copy handling with destination verification
- MediaStore refresh after file operations
- Preservation of video display names/extensions
- Android storage-access entry point

This project is based on the Aves upstream source and is independently maintained. It is not an official Aves release.

## Build

GitHub Actions runs analysis and tests, then builds signed, minimized release APKs split by ABI on pushes to `main`.

- Package ID: `io.github.jieoz.avesmediaenhanced.libre`
- Architectures: `arm64-v8a`, `armeabi-v7a`, `x86_64`
- Release signing certificate SHA-256: `DB:3A:B9:84:C4:3E:74:68:58:81:50:C2:A0:D9:88:6C:37:01:99:2A:76:A6:32:6D:7B:51:0B:BA:BD:89:86:6F`

The signing key is stored only as encrypted GitHub Actions secrets. A release is published only after the exact CI artifacts are downloaded and their package, version, signer, architecture, integrity, and hashes are verified.

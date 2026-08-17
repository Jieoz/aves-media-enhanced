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

GitHub Actions builds a debug APK on pushes to `main` and manual workflow dispatch. A release build is not claimed until CI completes successfully and the resulting APK is downloaded and verified.

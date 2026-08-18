#!/usr/bin/env python3
"""Release contracts for MediaStore reconciliation hardening."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DART = (ROOT / "lib/model/source/media_store_source.dart").read_text()
KOTLIN = (ROOT / "android/app/src/main/kotlin/deckers/thibault/aves/model/provider/MediaStoreImageProvider.kt").read_text()
POLICY = (ROOT / "android/app/src/main/kotlin/deckers/thibault/aves/model/provider/MediaStoreReconciliationPolicy.kt").read_text()


def section(text: str, start: str, end: str) -> str:
    return text.split(start, 1)[1].split(end, 1)[0]


def main() -> None:
    assert "final _catalogLock = Lock();" in DART
    assert "_catalogLock.synchronized" in DART

    load_new = section(DART, "Future<void> _loadNewEntries", "// returns URIs to retry later")
    assert "await for (final entry" in load_new
    assert ".listen(" not in load_new

    scan = section(KOTLIN, "suspend fun scanNewPathByMediaStore", "fun getContentUriForPath")
    assert "Thread.sleep" not in scan
    assert "delay(" in scan
    assert "cont.isActive" in scan
    assert "runBlocking" not in KOTLIN

    missing_ids = section(KOTLIN, "fun checkObsoleteContentIds", "fun checkObsoleteByMissingPath")
    missing_paths = section(KOTLIN, "fun checkObsoleteByMissingPath", "fun checkObsoletePaths")
    assert "isDefinitelyMissing" in missing_ids
    assert "isDefinitelyMissing" in missing_paths
    assert "getExternalStorageState" in KOTLIN
    assert "isDefinitelyMissing" in POLICY
    assert "MEDIA_MOUNTED" in POLICY
    assert "MEDIA_MOUNTED_READ_ONLY" in POLICY

    obsolete_paths = section(KOTLIN, "fun checkObsoletePaths", "fun getChangedUris")
    assert "RELATIVE_PATH" in obsolete_paths
    assert "DISPLAY_NAME" in obsolete_paths
    assert "VOLUME_NAME" in obsolete_paths
    assert "volumeMatches" in obsolete_paths

    obsolete_scan = section(KOTLIN, "private fun scanObsoletePath", "private fun clearStaleSourceEntry")
    assert "delayedExecutor" not in obsolete_scan

    assert "deleteMediaStoreRowByPath" not in KOTLIN
    print("media reconciliation source contracts: PASS")


if __name__ == "__main__":
    main()

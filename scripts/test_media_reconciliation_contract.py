#!/usr/bin/env python3
"""Release contracts for MediaStore reconciliation hardening."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DART = (ROOT / "lib/model/source/media_store_source.dart").read_text()
DB_DART = (ROOT / "lib/model/db/db_sqflite.dart").read_text()
SERVICE_POLICY = (ROOT / "lib/services/common/service_policy.dart").read_text()
GLOBAL_SEARCH = (ROOT / "lib/services/global_search.dart").read_text()
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

    # External global search must never expose vault rows.
    assert "origin != ?" in DB_DART
    assert "EntryOrigins.vault" in DB_DART
    assert "ESCAPE" in DB_DART
    assert "replaceAll('%', '\\\\%')" in DB_DART
    assert "searchLiveEntries(query, limit: 9)" in GLOBAL_SEARCH

    # Android 14 partial photo access must fail closed for missing IDs.
    assert "READ_MEDIA_IMAGES" in KOTLIN
    assert "READ_MEDIA_VIDEO" in KOTLIN
    assert "UPSIDE_DOWN_CAKE" in KOTLIN

    # Refresh batches must clamp the final sublist boundary.
    assert "min(i + _maxConcurrentFetch, uriItems.length)" in DART
    changes = section(DART, "Future<void> checkForChanges()", "Future<void> updateGeneration()")
    assert changes.index("getGeneration()") < changes.index("getChangedUris")
    assert "_lastGeneration = upperGeneration" in changes

    # Queued same-key requests must not strand the replaced completer.
    assert "completeError(CancelledException" in SERVICE_POLICY

    print("media reconciliation source contracts: PASS")


if __name__ == "__main__":
    main()

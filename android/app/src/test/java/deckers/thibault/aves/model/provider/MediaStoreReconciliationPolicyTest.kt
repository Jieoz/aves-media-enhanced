package deckers.thibault.aves.model.provider

import android.os.Environment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaStoreReconciliationPolicyTest {
    @Test
    fun acceptsMountedAndReadOnlyVolumesOnly() {
        assertTrue(MediaStoreReconciliationPolicy.isReadableStorageState(Environment.MEDIA_MOUNTED))
        assertTrue(MediaStoreReconciliationPolicy.isReadableStorageState(Environment.MEDIA_MOUNTED_READ_ONLY))
        assertFalse(MediaStoreReconciliationPolicy.isReadableStorageState(Environment.MEDIA_UNMOUNTED))
        assertFalse(MediaStoreReconciliationPolicy.isReadableStorageState(Environment.MEDIA_REMOVED))
    }

    @Test
    fun removesOnlyFilesProvenMissingOnReadableStorage() {
        assertTrue(MediaStoreReconciliationPolicy.isDefinitelyMissing(Environment.MEDIA_MOUNTED, true, true, false))
        assertTrue(MediaStoreReconciliationPolicy.isDefinitelyMissing(Environment.MEDIA_MOUNTED, false, false, false))
        assertFalse(MediaStoreReconciliationPolicy.isDefinitelyMissing(Environment.MEDIA_UNMOUNTED, true, true, false))
        assertFalse(MediaStoreReconciliationPolicy.isDefinitelyMissing(Environment.MEDIA_MOUNTED, true, false, false))
        assertFalse(MediaStoreReconciliationPolicy.isDefinitelyMissing(Environment.MEDIA_MOUNTED, true, true, true))
    }

    @Test
    fun matchesScopedStorageRelativePathAndDisplayName() {
        assertEquals(
            true,
            MediaStoreReconciliationPolicy.pathMatches(
                "/storage/emulated/0/DCIM/Camera/video.mp4",
                "DCIM/Camera/",
                "video.mp4",
            ),
        )
        assertEquals(
            false,
            MediaStoreReconciliationPolicy.pathMatches(
                "/storage/emulated/0/Movies/video.mp4",
                "DCIM/Camera/",
                "video.mp4",
            ),
        )
    }

    @Test
    fun missingScopedStorageColumnsAreUnknownNotMoved() {
        assertNull(MediaStoreReconciliationPolicy.pathMatches("/storage/emulated/0/DCIM/a.jpg", null, "a.jpg"))
        assertNull(MediaStoreReconciliationPolicy.pathMatches("/storage/emulated/0/DCIM/a.jpg", "DCIM/", ""))
    }

    @Test
    fun scopedStorageVolumeIdentityDistinguishesCrossVolumeMoves() {
        assertEquals(true, MediaStoreReconciliationPolicy.volumeMatches("/storage/emulated/0/DCIM/a.jpg", "external_primary"))
        assertEquals(true, MediaStoreReconciliationPolicy.volumeMatches("/storage/ABCD-1234/DCIM/a.jpg", "abcd-1234"))
        assertEquals(false, MediaStoreReconciliationPolicy.volumeMatches("/storage/emulated/0/DCIM/a.jpg", "ABCD-1234"))
        assertNull(MediaStoreReconciliationPolicy.volumeMatches("/unknown/DCIM/a.jpg", "external_primary"))
    }
}

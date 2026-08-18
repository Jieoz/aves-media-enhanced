package deckers.thibault.aves.model.provider

import android.os.Environment
import java.io.File

/** Pure reconciliation policies kept separate so fail-closed decisions are unit-testable. */
internal object MediaStoreReconciliationPolicy {
    fun isReadableStorageState(state: String): Boolean =
        state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY

    fun isDefinitelyMissing(
        storageState: String,
        parentExists: Boolean,
        parentCanRead: Boolean,
        fileExists: Boolean,
    ): Boolean =
        isReadableStorageState(storageState) && !fileExists && (!parentExists || parentCanRead)

    /**
     * Returns whether a known absolute path matches scoped-storage metadata.
     * `null` means MediaStore did not provide enough evidence to decide.
     */
    fun pathMatches(knownPath: String, relativePath: String?, displayName: String?): Boolean? {
        if (relativePath.isNullOrBlank() || displayName.isNullOrBlank()) return null
        val normalizedKnownPath = knownPath.replace(File.separatorChar, '/')
        val normalizedRelativePath = relativePath.trim('/').replace("//", "/")
        val suffix = "/$normalizedRelativePath/$displayName".replace("//", "/")
        return normalizedKnownPath.endsWith(suffix)
    }

    /** Compare MediaStore's volume identity with the volume encoded in an absolute path. */
    fun volumeMatches(knownPath: String, mediaStoreVolumeName: String?): Boolean? {
        if (mediaStoreVolumeName.isNullOrBlank()) return null
        val normalized = knownPath.replace(File.separatorChar, '/')
        val knownVolume = when {
            normalized.startsWith("/storage/emulated/") ||
                normalized.startsWith("/storage/self/primary/") ||
                normalized.startsWith("/sdcard/") -> "external_primary"
            normalized.startsWith("/storage/") -> normalized.removePrefix("/storage/").substringBefore('/').takeIf { it.isNotBlank() }
            else -> null
        } ?: return null
        return knownVolume.equals(mediaStoreVolumeName, ignoreCase = true)
    }
}

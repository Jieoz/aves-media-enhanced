package deckers.thibault.aves.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MimeTypesExtensionTest {
    @Test
    fun keepsSourceMp4WhenMediaStoreClaimsMpegTs() {
        assertEquals(".mp4", MimeTypes.extensionFor("video/mp2t", "mp4"))
        assertEquals(".mp4", MimeTypes.extensionFor("video/mp2t", ".mp4"))
        assertEquals(".mp4", MimeTypes.extensionFor("video/mp2ts", "MP4"))
    }

    @Test
    fun keepsSourceMkvWhenMimeIsUnknown() {
        assertEquals(".mkv", MimeTypes.extensionFor("application/octet-stream", "mkv"))
    }

    @Test
    fun usesMpegTsOnlyWhenSourceExtensionIsMissing() {
        assertEquals(".m2ts", MimeTypes.extensionFor("video/mp2t", null))
        assertEquals(".m2ts", MimeTypes.extensionFor("video/mp2t", ""))
        assertEquals(".m2ts", MimeTypes.extensionFor("video/mp2t", "."))
    }
}

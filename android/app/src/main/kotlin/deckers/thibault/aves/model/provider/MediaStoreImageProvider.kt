package deckers.thibault.aves.model.provider

import android.annotation.SuppressLint
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.commonsware.cwac.document.DocumentFileCompat
import deckers.thibault.aves.MainActivity
import deckers.thibault.aves.MainActivity.Companion.DELETE_SINGLE_PERMISSION_REQUEST
import deckers.thibault.aves.model.AvesEntry
import deckers.thibault.aves.model.EntryFields
import deckers.thibault.aves.model.FieldMap
import deckers.thibault.aves.model.NameConflictStrategy
import deckers.thibault.aves.model.SourceEntry
import deckers.thibault.aves.utils.LogUtils
import deckers.thibault.aves.utils.MimeTypes
import deckers.thibault.aves.utils.MimeTypes.extensionFor
import deckers.thibault.aves.utils.MimeTypes.isHeic
import deckers.thibault.aves.utils.MimeTypes.isImage
import deckers.thibault.aves.utils.MimeTypes.isVideo
import deckers.thibault.aves.utils.StorageUtils
import deckers.thibault.aves.utils.StorageUtils.PathSegments
import deckers.thibault.aves.utils.StorageUtils.ensureTrailingSeparator
import deckers.thibault.aves.utils.StorageUtils.removeTrailingSeparator
import deckers.thibault.aves.utils.UriUtils.tryParseId
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.SyncFailedException
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume

class MediaStoreImageProvider : ImageProvider() {
    fun fetchAll(
        context: Context,
        knownEntries: Map<Long?, Long?>,
        directory: String?,
        handleNewEntry: NewEntryHandler,
    ) {
        Log.d(LOG_TAG, "fetching all media store items for ${knownEntries.size} known entries, directory=$directory")
        val isModified = fun(contentId: Long, dateModifiedMillis: Long): Boolean {
            val knownDate = knownEntries[contentId]
            return knownDate == null || knownDate < dateModifiedMillis
        }
        val handleNew: NewEntryHandler
        var selection: String? = null
        var selectionArgs: Array<String>? = null
        if (directory != null) {
            val relativePathDirectory = ensureTrailingSeparator(directory)
            val relativePath = PathSegments(context, relativePathDirectory).relativeDir
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
                selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DATA} LIKE ?"
                selectionArgs = arrayOf(relativePath, "$relativePathDirectory%")
            } else {
                selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
                selectionArgs = arrayOf("$relativePathDirectory%")
            }

            val parentCheckDirectory = removeTrailingSeparator(directory)
            handleNew = { entry ->
                // skip entries in subfolders
                val path = entry[EntryFields.PATH] as String?
                if (path != null && File(path).parent == parentCheckDirectory) {
                    handleNewEntry(entry)
                }
            }
        } else {
            handleNew = handleNewEntry
        }
        fetchFrom(context, isModified, handleNew, IMAGE_CONTENT_URI, IMAGE_PROJECTION, selection, selectionArgs)
        fetchFrom(context, isModified, handleNew, VIDEO_CONTENT_URI, VIDEO_PROJECTION, selection, selectionArgs)
    }

    // the provided URI can point to the wrong media collection,
    // e.g. a GIF image with the URI `content://media/external/video/media/[ID]`
    // so the effective entry URI may not match the provided URI
    override fun fetchSingle(context: Context, uri: Uri, sourceMimeType: String?, allowUnsized: Boolean, callback: ImageOpCallback) {
        var found = false
        val fetched = arrayListOf<FieldMap>()
        val id = uri.tryParseId()
        val alwaysValid: NewEntryChecker = fun(_: Long, _: Long): Boolean = true
        val onSuccess: NewEntryHandler = fun(entry: FieldMap) { fetched.add(entry) }
        if (id != null) {
            if (sourceMimeType == null || isImage(sourceMimeType)) {
                val contentUri = ContentUris.withAppendedId(IMAGE_CONTENT_URI, id)
                found = fetchFrom(context, alwaysValid, onSuccess, contentUri, IMAGE_PROJECTION)
            }
            if (!found && (sourceMimeType == null || isVideo(sourceMimeType))) {
                val contentUri = ContentUris.withAppendedId(VIDEO_CONTENT_URI, id)
                found = fetchFrom(context, alwaysValid, onSuccess, contentUri, VIDEO_PROJECTION)
            }
        }
        if (!found) {
            // the uri can be a file media URI (e.g. "content://0@media/external/file/30050")
            // without an equivalent image/video if it is shared from a file browser
            // but the file is not publicly visible
            found = fetchFrom(context, alwaysValid, onSuccess, uri, BASE_PROJECTION, fileMimeType = sourceMimeType)
        }

        if (found && fetched.isNotEmpty()) {
            if (fetched.size == 1) {
                callback.onSuccess(fetched.first())
            } else {
                callback.onFailure(Exception("found ${fetched.size} entries at uri=$uri"))
            }
        } else {
            callback.onFailure(Exception("failed to fetch entry at uri=$uri"))
        }
    }

    fun checkObsoleteContentIds(context: Context, knownPathById: Map<Long?, String?>): List<Long> {
        val foundContentIds = HashSet<Long>()
        var queryFailed = false
        fun check(context: Context, contentUri: Uri) {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            try {
                val cursor = context.contentResolver.query(contentUri, projection, null, null, null)
                if (cursor == null) {
                    queryFailed = true
                    return
                }
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    foundContentIds.add(cursor.getLong(idColumn))
                }
                cursor.close()
            } catch (e: Exception) {
                queryFailed = true
                Log.e(LOG_TAG, "failed to get content IDs for contentUri=$contentUri", e)
            }
        }
        check(context, IMAGE_CONTENT_URI)
        check(context, VIDEO_CONTENT_URI)
        // A failed or null query must not be treated as "every known id is gone".
        // The difference against an empty found-set would wipe the whole catalog.
        if (queryFailed) return emptyList()
        return knownPathById.keys
            .subtract(foundContentIds)
            .filterNotNull()
            .filter { id -> knownPathById[id]?.let { isDefinitelyMissing(it) } == true }
    }

    private fun isDefinitelyMissing(path: String): Boolean = try {
        val file = File(path)
        val parent = file.parentFile
        MediaStoreReconciliationPolicy.isDefinitelyMissing(
            storageState = Environment.getExternalStorageState(file),
            parentExists = parent?.exists() == true,
            parentCanRead = parent?.canRead() == true,
            fileExists = file.exists(),
        )
    } catch (_: Exception) {
        false
    }

    // Return the content ids whose backing file no longer exists on disk. This catches
    // entries deleted by other apps that Media Store has not indexed away yet. This
    // complements the missing-ID check, which now also requires path-level proof before removal.
    // Entries without a known path are skipped (we cannot tell whether the file is gone).
    // We only trust this when the app has All-files access: without it the stored paths
    // are unreliable, so a missing path must not be taken as proof the file is gone.
    fun checkObsoleteByMissingPath(context: Context, knownPathById: Map<Long?, String?>): List<Long> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return emptyList()
        }
        val obsoleteIds = ArrayList<Long>()
        for ((id, path) in knownPathById) {
            if (id == null || path.isNullOrBlank()) continue
            if (isDefinitelyMissing(path)) obsoleteIds.add(id)
        }
        return obsoleteIds
    }

    fun checkObsoletePaths(context: Context, knownPathById: Map<Long?, String?>): List<Long> {
        val obsoleteIds = ArrayList<Long>()
        var queryFailed = false
        fun check(context: Context, contentUri: Uri) {
            val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val projection = if (useRelativePath) {
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.VOLUME_NAME,
                )
            } else {
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
            }
            try {
                val cursor = context.contentResolver.query(contentUri, projection, null, null, null)
                if (cursor == null) {
                    queryFailed = true
                    return
                }
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val pathColumn = if (useRelativePath) -1 else cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val relativePathColumn = if (useRelativePath) cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                val displayNameColumn = if (useRelativePath) cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME) else -1
                val volumeNameColumn = if (useRelativePath) cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME) else -1
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val knownPath = knownPathById[id] ?: continue
                    val moved = if (useRelativePath) {
                        val relativePath = cursor.getString(relativePathColumn)
                        val displayName = cursor.getString(displayNameColumn)
                        val volumeName = cursor.getString(volumeNameColumn)
                        MediaStoreReconciliationPolicy.pathMatches(knownPath, relativePath, displayName) == false ||
                            MediaStoreReconciliationPolicy.volumeMatches(knownPath, volumeName) == false
                    } else {
                        // DATA is used only on pre-scoped-storage Android versions.
                        val path = cursor.getString(pathColumn)
                        !path.isNullOrBlank() && knownPath != path
                    }
                    if (moved) {
                        obsoleteIds.add(id)
                    }
                }
                cursor.close()
            } catch (e: Exception) {
                queryFailed = true
                Log.e(LOG_TAG, "failed to get content IDs for contentUri=$contentUri", e)
            }
        }
        check(context, IMAGE_CONTENT_URI)
        check(context, VIDEO_CONTENT_URI)
        if (queryFailed) return emptyList()
        return obsoleteIds
    }

    fun getChangedUris(context: Context, sinceGeneration: Int): List<String> {
        val changedUris = ArrayList<String>()
        fun check(context: Context, contentUri: Uri) {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?"
            val selectionArgs = arrayOf(sinceGeneration.toString())
            try {
                val cursor = context.contentResolver.query(contentUri, projection, selection, selectionArgs, null)
                if (cursor != null) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        changedUris.add(ContentUris.withAppendedId(contentUri, id).toString())
                    }
                    cursor.close()
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "failed to get content IDs for contentUri=$contentUri", e)
            }
        }
        check(context, IMAGE_CONTENT_URI)
        check(context, VIDEO_CONTENT_URI)
        return changedUris
    }

    private fun fetchFrom(
        context: Context,
        isValidEntry: NewEntryChecker,
        handleNewEntry: NewEntryHandler,
        contentUri: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        fileMimeType: String? = null,
    ): Boolean {
        var found = false
        val orderBy = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        try {
            val cursor = context.contentResolver.query(contentUri, projection, selection, selectionArgs, orderBy)
            if (cursor != null) {
                val contentUriContainsId = when (contentUri) {
                    IMAGE_CONTENT_URI, VIDEO_CONTENT_URI -> false
                    else -> true
                }

                // image & video
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val dateAddedSecsColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val dateModifiedSecsColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val dateTakenColumn = cursor.getColumnIndex(MediaColumns.DATE_TAKEN)

                // image & video for API >=29, only for images for API <29
                val orientationColumn = cursor.getColumnIndex(MediaColumns.ORIENTATION)

                // video only
                val durationColumn = cursor.getColumnIndex(MediaColumns.DURATION)
                val needDuration = projection.contentEquals(VIDEO_PROJECTION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val dateModifiedMillis = cursor.getInt(dateModifiedSecsColumn) * 1000L
                    if (isValidEntry(id, dateModifiedMillis)) {
                        // for multiple items, `contentUri` is the root without ID,
                        // but for single items, `contentUri` already contains the ID
                        val itemUri = if (contentUriContainsId) contentUri else ContentUris.withAppendedId(contentUri, id)
                        // `mimeType` can be registered as null for file media URIs with unsupported media types (e.g. TIFF on old devices)
                        // in that case we try to use the MIME type provided along the URI
                        val mimeType: String? = cursor.getString(mimeTypeColumn) ?: fileMimeType
                        var width = cursor.getInt(widthColumn)
                        var height = cursor.getInt(heightColumn)
                        val durationMillis = if (durationColumn != -1) cursor.getLong(durationColumn) else 0L

                        if (mimeType == null) {
                            Log.w(LOG_TAG, "failed to make entry from uri=$itemUri because of null MIME type")
                        } else {
                            val path = cursor.getString(pathColumn)

                            val isDir = path != null && File(path).isDirectory
                            if (isDir) {
                                // some directories are wrongly registered as media (e.g. `.../Android/media/is.xyz.mpv`)
                                Log.w(LOG_TAG, "failed to make entry from uri=$itemUri because path=$path refers to a directory")
                            } else {
                                var entryFields: FieldMap = hashMapOf(
                                    EntryFields.ORIGIN to SourceEntry.ORIGIN_MEDIA_STORE_CONTENT,
                                    EntryFields.URI to itemUri.toString(),
                                    EntryFields.PATH to path,
                                    EntryFields.SOURCE_MIME_TYPE to mimeType,
                                    EntryFields.WIDTH to width,
                                    EntryFields.HEIGHT to height,
                                    EntryFields.SOURCE_ROTATION_DEGREES to if (orientationColumn != -1) cursor.getInt(orientationColumn) else 0,
                                    EntryFields.SIZE_BYTES to cursor.getLong(sizeColumn),
                                    EntryFields.DATE_ADDED_SECS to cursor.getInt(dateAddedSecsColumn),
                                    EntryFields.DATE_MODIFIED_MILLIS to dateModifiedMillis,
                                    EntryFields.SOURCE_DATE_TAKEN_MILLIS to if (dateTakenColumn != -1) cursor.getLong(dateTakenColumn) else null,
                                    EntryFields.DURATION_MILLIS to durationMillis,
                                    // only for map export
                                    EntryFields.CONTENT_ID to id,
                                )

                                if (isHeic(mimeType) || mimeType == MimeTypes.TIFF) {
                                    // The reported size for some HEIC images is simply incorrect.
                                    // Some HEIC images are detected as TIFF.
                                    try {
                                        StorageUtils.openInputStream(context, itemUri)?.use { input ->
                                            val options = BitmapFactory.Options().apply {
                                                inJustDecodeBounds = true
                                            }
                                            BitmapFactory.decodeStream(input, null, options)
                                            val outWidth = options.outWidth
                                            val outHeight = options.outHeight
                                            if (outWidth > 0 && outHeight > 0) {
                                                width = outWidth
                                                height = outHeight
                                                entryFields[EntryFields.WIDTH] = width
                                                entryFields[EntryFields.HEIGHT] = height
                                            }
                                        }
                                    } catch (_: IOException) {
                                        // ignore
                                    }
                                }

                                if (MimeTypes.isRaw(mimeType)
                                    || (width <= 0 || height <= 0) && needSize(mimeType)
                                    || durationMillis == 0L && needDuration
                                ) {
                                    // Some images are incorrectly registered in the Media Store,
                                    // missing some attributes such as width, height, orientation.
                                    // Also, the reported size of raw images is inconsistent across devices
                                    // and Android versions (sometimes the raw size, sometimes the decoded size).
                                    val entry = SourceEntry(entryFields).fillPreCatalogMetadata(context)
                                    entryFields = entry.toMap()
                                }

                                getFileModifiedDateMillis(path)?.let { entryFields[EntryFields.DATE_MODIFIED_MILLIS] = it }

                                handleNewEntry(entryFields)
                                found = true
                            }
                        }
                    }
                }
                cursor.close()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "failed to get entries", e)
        }
        return found
    }

    private fun hasEntry(context: Context, contentUri: Uri): Boolean {
        var found = false
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        try {
            val cursor = context.contentResolver.query(contentUri, projection, null, null, null)
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    found = true
                }
                cursor.close()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "failed to get entry at contentUri=$contentUri", e)
        }
        return found
    }

    private fun needSize(mimeType: String) = MimeTypes.SVG != mimeType

    // `uri` is a media URI, not a document URI
    override fun delete(contextWrapper: ContextWrapper, uri: Uri, path: String?, mimeType: String) {
        path ?: throw Exception("failed to delete file because path is null")

        // the following situations are possible:
        // - there is an entry in the Media Store and there is a file on storage
        // - there is an entry in the Media Store but there is no longer a file on storage
        // - there is no entry in the Media Store but there is a file on storage
        val file = File(path)
        val fileExists = file.exists()

        if (fileExists) {
            if (StorageUtils.canEditByFile(contextWrapper, path)) {
                if (hasEntry(contextWrapper, uri)) {
                    Log.d(LOG_TAG, "delete [permission:file, file exists, content exists] content at uri=$uri path=$path")
                    contextWrapper.contentResolver.delete(uri, null, null)
                }
                // in theory, deleting via content resolver should remove the file on storage
                // in practice, the file may still be there afterwards
                if (file.exists()) {
                    Log.d(LOG_TAG, "delete [permission:file, file exists after content delete] file at uri=$uri path=$path")
                    if (file.delete()) {
                        // in theory, scanning an obsolete path should remove the entry from the Media Store
                        // in practice, the entry may still be there afterwards
                        scanObsoletePath(contextWrapper, uri, path, mimeType)
                        return
                    }
                } else {
                    return
                }
            } else if (!isMediaUriPermissionGranted(contextWrapper, uri, mimeType)
                && StorageUtils.requireAccessPermission(contextWrapper, path)
            ) {
                // the delete request may yield a `RecoverableSecurityException` when using scoped storage,
                // even if we have permissions on the tree document via SAF
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && hasEntry(contextWrapper, uri)) {
                    Log.d(LOG_TAG, "delete [permission:doc, file exists, content exists] content at uri=$uri path=$path")
                    contextWrapper.contentResolver.delete(uri, null, null)
                }

                // in theory, deleting via content resolver should remove the file on storage
                // in practice, the file may still be there afterwards
                if (file.exists()) {
                    Log.d(LOG_TAG, "delete [permission:doc, file exists after content delete] document at uri=$uri path=$path")
                    val df = StorageUtils.getDocumentFile(contextWrapper, path, uri)

                    if (df != null && df.delete()) {
                        scanObsoletePath(contextWrapper, uri, path, mimeType)
                        return
                    }
                    throw Exception("failed to delete document with df=$df")
                } else {
                    return
                }
            }
        } else if (uri.scheme?.lowercase(Locale.ROOT) == ContentResolver.SCHEME_FILE) {
            val uriFilePath = File(uri.path!!).path
            // URI and path both point to the same non-existent path
            if (uriFilePath == path) return
        }

        try {
            Log.d(LOG_TAG, "delete [file exists=$fileExists] content at uri=$uri path=$path")
            if (contextWrapper.contentResolver.delete(uri, null, null) > 0) {
                // the Media Store may defer the actual file removal to an idle pass,
                // so try to unlink the file right now; otherwise the storage space
                // is only reclaimed at an arbitrary later time
                if (path != null && file.exists()) {
                    try {
                        if (StorageUtils.canEditByFile(contextWrapper, path)) {
                            file.delete()
                        } else {
                            StorageUtils.getDocumentFile(contextWrapper, path, uri)?.delete()
                        }
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "failed to unlink file at path=$path after content delete", e)
                    }
                }
                return
            }

            if (hasEntry(contextWrapper, uri) || file.exists()) {
                throw Exception("failed to delete row from content provider")
            }
        } catch (securityException: SecurityException) {
            // even if the app has access permission granted on the containing directory,
            // the delete request may yield a `RecoverableSecurityException` on API >=29
            // when the underlying file no longer exists and this is an orphaned entry in the Media Store
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && contextWrapper is Activity) {
                Log.w(LOG_TAG, "caught a security exception when attempting to delete uri=$uri", securityException)
                val rse = securityException as? RecoverableSecurityException ?: throw securityException
                val intentSender = rse.userAction.actionIntent.intentSender

                // request user permission for this item
                MainActivity.pendingScopedStoragePermissionCompleter = CompletableFuture<Boolean>()
                contextWrapper.startIntentSenderForResult(intentSender, DELETE_SINGLE_PERMISSION_REQUEST, null, 0, 0, 0, null)
                val granted = MainActivity.pendingScopedStoragePermissionCompleter!!.join()

                MainActivity.pendingScopedStoragePermissionCompleter = null
                if (granted) {
                    delete(contextWrapper, uri, path, mimeType)
                } else {
                    throw Exception("failed to get delete permission")
                }
            } else {
                throw securityException
            }
        }
    }

    override suspend fun moveMultiple(
        activity: Activity,
        copy: Boolean,
        nameConflictStrategy: NameConflictStrategy,
        entriesByTargetDir: Map<String, List<AvesEntry>>,
        isCancelledOp: CancelCheck,
        callback: ImageOpCallback,
    ) {
        entriesByTargetDir.forEach { kv ->
            val targetDir = kv.key
            val entries = kv.value

            val toBin = targetDir == StorageUtils.TRASH_PATH_PLACEHOLDER
            val toVault = StorageUtils.isInVault(activity, targetDir)
            val toAppDir = toBin || toVault

            var effectiveTargetDir: String? = null
            var targetDirDocFile: DocumentFileCompat? = null
            if (!toAppDir) {
                effectiveTargetDir = targetDir
                targetDirDocFile = StorageUtils.createDirectoryDocIfAbsent(activity, targetDir)
                if (!File(targetDir).exists()) {
                    // download subdirectories can be created later by Media Store insertion
                    if (!isDownloadSubdir(activity, targetDir)) {
                        callback.onFailure(Exception("failed to create directory at path=$targetDir"))
                        return
                    }
                }
            }

            for (entry in entries) {
                val mimeType = entry.mimeType
                val trashed = entry.trashed

                val sourceUri = entry.uri
                val sourcePath = entry.storagePath

                val result: FieldMap = hashMapOf(
                    "uri" to sourceUri.toString(),
                    "success" to false,
                )

                // on API 30 we cannot get access granted directly to a volume root from its document tree,
                // but it is still less constraining to use tree document files than to rely on the Media Store
                //
                // Relying on `DocumentFile`, we can create an item via `DocumentFile.createFile()`, but:
                // - we need to scan the file to get the Media Store content URI
                // - the underlying document provider controls the new file name
                //
                // Relying on the Media Store, we can create an item via `ContentResolver.insert()`
                // with a path, and retrieve its content URI, but:
                // - the Media Store isolates content by storage volume (e.g. `MediaStore.Images.Media.getContentUri(volumeName)`)
                // - the volume name should be lower case, not exactly as the `StorageVolume` UUID
                //   cf new method in API 30 `StorageVolume.getMediaStoreVolumeName()`
                // - inserting on a removable volume works on API 29, but not on API 25 nor 26 (on which API/devices does it work?)
                // - there is no documentation regarding support for usage with removable storage
                // - the Media Store only allows inserting in specific primary directories ("DCIM", "Pictures") when using scoped storage
                try {
                    val appDir = when {
                        toBin -> StorageUtils.trashDirFor(activity, sourcePath ?: StorageUtils.getPrimaryVolumePath(activity))
                        toVault -> File(targetDir)
                        else -> null
                    }
                    if (appDir != null) {
                        effectiveTargetDir = ensureTrailingSeparator(appDir.path)
                        targetDirDocFile = DocumentFileCompat.fromFile(appDir)

                        if (toVault) {
                            appDir.mkdirs()
                        }
                    }

                    if (effectiveTargetDir != null) {
                        val newFields = if (isCancelledOp()) skippedFieldMap else {
                            val sourceFile = if (sourcePath != null) File(sourcePath) else null
                            if (sourceFile != null && !sourceFile.exists() && toBin) {
                                delete(activity, sourceUri, sourcePath, mimeType = mimeType)
                                deletedFieldMap
                            } else {
                                moveSingle(
                                    activity = activity,
                                    sourceFile = sourceFile,
                                    sourceUri = sourceUri,
                                    targetDir = effectiveTargetDir,
                                    targetDirDocFile = targetDirDocFile,
                                    // preserve the source file name (and thus its extension) on move.
                                    // In scoped storage we only have a content uri, so fall back to the
                                    // Media Store DISPLAY_NAME (readable without special permission) before
                                    // the numeric content id, so the extension is never lost (no `.ts`).
                                    desiredName = (if (trashed) entry.path?.let { File(it).name } else sourceFile?.name ?: getDisplayName(activity, sourceUri) ?: sourceUri.lastPathSegment) ?: createTimeStampFileName(),
                                    nameConflictStrategy = nameConflictStrategy,
                                    mimeType = mimeType,
                                    copy = copy,
                                    toBin = toBin,
                                )
                            }
                        }
                        result["newFields"] = newFields
                        result["success"] = true
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "failed to move to targetDir=$targetDir entry with sourcePath=$sourcePath", e)
                }
                callback.onSuccess(result)
            }
        }
    }

    private suspend fun moveSingle(
        activity: Activity,
        sourceFile: File?,
        sourceUri: Uri,
        targetDir: String,
        targetDirDocFile: DocumentFileCompat?,
        desiredName: String,
        nameConflictStrategy: NameConflictStrategy,
        mimeType: String,
        copy: Boolean,
        toBin: Boolean,
    ): FieldMap {
        val sourcePath = sourceFile?.path
        // Always derive the target extension from the SOURCE FILE NAME, which carries the
        // real extension in every access mode (a direct file path, or — via the display-name
        // fallback in `moveMultiple` — a content uri). Falling back to the MIME type here is
        // exactly what renamed `video.mp4` to `video.ts` when Media Store misdetected the
        // stream as `video/mp2t` (a frequent case for clips produced by other apps).
        val desiredExtension = desiredName.substringAfterLast(".", "")
        val sourceExtension = if (desiredExtension.isNotEmpty()) desiredExtension else sourceFile?.extension
        val sourceDir = sourceFile?.parent?.let { ensureTrailingSeparator(it) }
        if (sourceDir == targetDir && !(copy && nameConflictStrategy == NameConflictStrategy.RENAME)) {
            // nothing to do unless it's a renamed copy
            return skippedFieldMap
        }

        if (sourceFile != null && !sourceFile.exists()) {
            throw Exception("failed to move file because it is missing at path=$sourcePath")
        }

        val desiredNameWithoutExtension = desiredName.substringBeforeLast(".")
        val resolution = resolveTargetFileNameWithoutExtension(
            contextWrapper = activity,
            dir = targetDir,
            desiredNameWithoutExtension = desiredNameWithoutExtension,
            mimeType = mimeType,
            defaultExtension = sourceExtension,
            conflictStrategy = nameConflictStrategy,
        )
        val targetNameWithoutExtension = resolution.nameWithoutExtension ?: return skippedFieldMap

        // fast path: an instant same-volume rename when we have direct file
        // system access on both ends, instead of copying the whole file and
        // deleting the original (which is slow for large videos)
        if (!copy && !toBin && sourceFile != null && !StorageUtils.isInVault(activity, targetDir)) {
            val sourceDirPath = sourceFile.parent
            if (sourceDirPath != null &&
                StorageUtils.canEditByFile(activity, sourceDirPath) &&
                StorageUtils.canEditByFile(activity, targetDir)
            ) {
                val sourceVolume = StorageUtils.getVolumePath(activity, sourceDirPath)
                val targetVolume = StorageUtils.getVolumePath(activity, targetDir)
                if (sourceVolume != null && sourceVolume == targetVolume) {
                    val targetFile = File(targetDir, targetNameWithoutExtension + extensionFor(mimeType, sourceExtension))
                    if (!targetFile.exists() && sourceFile.renameTo(targetFile)) {
                        Log.d(LOG_TAG, "move [fast rename] from path=$sourcePath to path=${targetFile.path}")
                        // verify the rename actually produced a complete file before we
                        // touch the source entry; a partial/corrupt rename must roll back
                        // to the source instead of being reported as a success.
                        val renameComplete = targetFile.exists() && targetFile.length() == sourceFile.length()
                        if (!renameComplete) {
                            Log.w(LOG_TAG, "rename incomplete at path=${targetFile.path}, rolling back to path=$sourcePath")
                            try {
                                targetFile.renameTo(sourceFile)
                            } catch (_: Exception) {
                            }
                            // fall through to the copy+delete branch below
                        } else {
                        // Scan the new path FIRST and only drop the obsolete Media Store
                        // entry once the new file is actually indexed. This keeps the
                        // operation atomic: if scanning fails we roll the rename back so
                        // the source (and its Media Store entry) stay intact, instead of
                        // leaving a dangling entry pointing at a moved file.
                        try {
                            val newFields = scanNewPath(activity, targetFile.path, mimeType)
                            // drop the obsolete source entry from Media Store. The file is
                            // already gone (renamed), so we only remove the index row, using
                            // both the content uri and the path so it also works for file://
                            // source uris where `hasEntry` cannot see the row.
                            clearStaleSourceEntry(activity, sourceUri, sourcePath, mimeType)
                            return newFields
                        } catch (e: Exception) {
                            Log.w(LOG_TAG, "scan failed after rename, rolling back to path=$sourcePath", e)
                            try {
                                targetFile.renameTo(sourceFile)
                            } catch (_: Exception) {
                            }
                            throw e
                        }
                    }
                }
                    // rename failed (e.g. across filesystems): fall through to copy+delete
                }
            }
        }

        val sourceDocFile = DocumentFileCompat.fromSingleUri(activity, sourceUri)
        val targetPath = createSingle(
            activity = activity,
            mimeType = mimeType,
            targetDir = targetDir,
            targetDirDocFile = targetDirDocFile,
            targetNameWithoutExtension = targetNameWithoutExtension,
            defaultExtension = sourceExtension,
        ) { output: OutputStream ->
            try {
                sourceDocFile.copyTo(output)
            } catch (e: SyncFailedException) {
                // The copied file is synced after writing, but it consistently fails in some cases
                // (e.g. copying to SD card on Xiaomi 2201117PG with Android 11).
                // It seems this failure can be safely ignored, as the new file is complete.
                Log.w(LOG_TAG, "sync failure after copying from uri=$sourceUri, path=$sourcePath to targetDir=$targetDir", e)
            }
        }

        // Verify the copy is complete BEFORE touching the source. A truncated copy
        // (e.g. when the sync failure above is not harmless) must not cause us to
        // delete the only good copy.
        val targetFile = File(targetPath)
        val sourceSize = sourceFile?.length() ?: -1L
        val copyComplete = targetFile.exists() && (sourceSize < 0 || targetFile.length() == sourceSize)
        if (!copyComplete) {
            Log.w(LOG_TAG, "copy incomplete from path=$sourcePath to path=$targetPath, rolling back")
            try {
                if (targetFile.exists()) targetFile.delete()
            } catch (_: Exception) {
            }
            throw Exception("copy incomplete from path=$sourcePath to path=$targetPath")
        }

        if (!copy) {
            // Scan the new path first, then delete the source only once the new
            // file is indexed. If scanning fails we roll back the target so the
            // source stays intact (no data loss, no dangling entry).
            val newFields = try {
                scanNewPath(activity, targetPath, mimeType)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "scan failed after copy, rolling back target at path=$targetPath", e)
                try {
                    if (targetFile.exists()) targetFile.delete()
                } catch (_: Exception) {
                }
                throw e
            }
            // new copy is indexed: remove the source (best effort; if it fails the
            // source remains and we end up with two copies, which is safe)
            try {
                delete(activity, sourceUri, sourcePath, mimeType)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "failed to delete source after move at path=$sourcePath", e)
            }
            // also clear any Media Store row still indexed by the (now empty) source
            // path; this catches file:// source uris where `delete` cannot see the row.
            clearStaleSourceEntry(activity, sourceUri, sourcePath, mimeType)
            return newFields
        }
        return if (toBin) {
            hashMapOf(
                EntryFields.TRASHED to true,
                EntryFields.TRASH_PATH to targetPath,
            )
        } else {
            scanNewPath(activity, targetPath, mimeType)
        }
    }

    fun createSingle(
        activity: Activity,
        mimeType: String,
        targetDir: String,
        targetDirDocFile: DocumentFileCompat?,
        targetNameWithoutExtension: String,
        defaultExtension: String?,
        write: (OutputStream) -> Unit,
    ): String {
        if (StorageUtils.isInVault(activity, targetDir)) {
            return insertByFile(
                targetDir = targetDir,
                targetFileName = "$targetNameWithoutExtension${extensionFor(mimeType, defaultExtension)}",
                write = write,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isDownloadSubdir(activity, targetDir)) {
                return insertByMediaStore(
                    activity = activity,
                    targetDir = targetDir,
                    targetFileName = "$targetNameWithoutExtension${extensionFor(mimeType, defaultExtension)}",
                    write = write,
                )
            }
        }

        return insertByTreeDoc(
            activity = activity,
            mimeType = mimeType,
            targetDir = targetDir,
            targetDirDocFile = targetDirDocFile,
            targetNameWithoutExtension = targetNameWithoutExtension,
            defaultExtension = defaultExtension,
            write = write,
        )
    }

    private fun isDownloadSubdir(context: Context, dir: String): Boolean {
        val volumePath = StorageUtils.getVolumePath(context, dir) ?: return false
        val downloadDirPath = ensureTrailingSeparator(File(volumePath, Environment.DIRECTORY_DOWNLOADS).path)
        // effective download path may have a different case
        return dir.lowercase().startsWith(downloadDirPath.lowercase())
    }

    private fun insertByFile(
        targetDir: String,
        targetFileName: String,
        write: (OutputStream) -> Unit,
    ): String {
        val file = File(targetDir, targetFileName)
        FileOutputStream(file).use(write)
        return file.path
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertByMediaStore(
        activity: Activity,
        targetDir: String,
        targetFileName: String,
        write: (OutputStream) -> Unit,
    ): String {
        val volumePath = StorageUtils.getVolumePath(activity, targetDir)
        val relativePath = targetDir.substring(volumePath?.length ?: 0)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, targetFileName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = activity.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)

        uri?.let {
            resolver.openOutputStream(uri)?.use(write)
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } ?: throw Exception("MediaStore failed for some reason")

        return File(targetDir, targetFileName).path
    }

    // `DocumentsContract.moveDocument()` needs `sourceParentDocumentUri`, which could be different for each entry
    // `DocumentsContract.copyDocument()` yields "Unsupported call: android:copyDocument"
    // when used with entry URI as `sourceDocumentUri`, and targetDirDocFile URI as `targetParentDocumentUri`
    private fun insertByTreeDoc(
        activity: Activity,
        mimeType: String,
        targetDir: String,
        targetDirDocFile: DocumentFileCompat?,
        targetNameWithoutExtension: String,
        defaultExtension: String?,
        write: (OutputStream) -> Unit,
    ): String {
        targetDirDocFile ?: throw Exception("failed to get tree doc for directory at path=$targetDir")

        // the file created from a `TreeDocumentFile` is also a `TreeDocumentFile`
        // but in order to open an output stream to it, we need to use a `SingleDocumentFile`
        // through a document URI, not a tree URI
        // note that `DocumentFile.getParentFile()` returns null if we did not pick a tree first
        var targetTreeFile = targetDirDocFile.createFile(mimeType, targetNameWithoutExtension)
        var targetDocFile = DocumentFileCompat.fromSingleUri(activity, targetTreeFile.uri)

        // providing a display name and a MIME type does not guarantee
        // that the created document will be backed by a file with a valid media extension,
        // but having an extension is essential for media detection by Android,
        // so we retry with a display name that includes the extension
        if ((targetDocFile.extension == null || targetDocFile.extension.isEmpty() || targetDocFile.extension == "bin") && defaultExtension != null) {
            if (targetDocFile.exists()) {
                targetDocFile.delete()
            }

            val extension = if (defaultExtension.startsWith(".")) defaultExtension else ".$defaultExtension"
            targetTreeFile = targetDirDocFile.createFile(mimeType, "$targetNameWithoutExtension$extension")
            targetDocFile = DocumentFileCompat.fromSingleUri(activity, targetTreeFile.uri)
        }

        try {
            targetDocFile.openOutputStream().use(write)
        } catch (e: Exception) {
            // remove empty file
            if (targetDocFile.exists()) {
                targetDocFile.delete()
            }
            throw e
        }

        // the source file name and the created document file name can be different when:
        // - a file with the same name already exists, some implementations give a suffix like ` (1)`, some *do not*
        // - the original extension does not match the extension added by the underlying provider
        val fileName = targetDocFile.name
        return targetDir + fileName
    }

    override suspend fun renameSingle(
        activity: Activity,
        mimeType: String,
        oldMediaUri: Uri,
        oldPath: String,
        newFile: File,
    ): FieldMap = when {
        StorageUtils.canEditByFile(activity, oldPath) -> renameSingleByFile(activity, mimeType, oldMediaUri, oldPath, newFile)
        isMediaUriPermissionGranted(activity, oldMediaUri, mimeType) -> renameSingleByMediaStore(activity, mimeType, oldMediaUri, newFile)
        else -> renameSingleByTreeDoc(activity, mimeType, oldMediaUri, oldPath, newFile)
    }

    private suspend fun renameSingleByMediaStore(
        activity: Activity,
        mimeType: String,
        mediaUri: Uri,
        newFile: File
    ): FieldMap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw Exception("unsupported Android version")
        }

        Log.d(LOG_TAG, "rename content at uri=$mediaUri")
        val uri = StorageUtils.getMediaStoreScopedStorageSafeUri(mediaUri, mimeType)

        // `IS_PENDING` is necessary for `TITLE`, not for `DISPLAY_NAME`
        val tempValues = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        if (activity.contentResolver.update(uri, tempValues, null, null) == 0) {
            throw Exception("failed to update fields for uri=$uri")
        }

        val finalValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newFile.name)
            // scanning the new file will not automatically update `TITLE`
            put(MediaStore.MediaColumns.TITLE, newFile.nameWithoutExtension)
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        if (activity.contentResolver.update(uri, finalValues, null, null) == 0) {
            throw Exception("failed to update fields for uri=$uri")
        }

        // URI should not change
        return scanNewPathByMediaStore(activity, newFile.path, mimeType)
    }

    private suspend fun renameSingleByTreeDoc(
        activity: Activity,
        mimeType: String,
        oldMediaUri: Uri,
        oldPath: String,
        newFile: File
    ): FieldMap {
        Log.d(LOG_TAG, "rename document at uri=$oldMediaUri path=$oldPath")
        val df = StorageUtils.getDocumentFile(activity, oldPath, oldMediaUri)
        df ?: throw Exception("failed to get document at path=$oldPath")

        val requestedName = newFile.name
        val renamed = df.renameTo(newFile.name)
        if (!renamed) {
            throw Exception("failed to rename document at path=$oldPath")
        }
        val effectiveName = df.name
        if (requestedName != effectiveName) {
            Log.w(LOG_TAG, "requested renaming document at uri=$oldMediaUri path=$oldPath with name=${requestedName} but got name=$effectiveName")
        }
        val newPath = File(newFile.parentFile, df.name).path

        scanObsoletePath(activity, oldMediaUri, oldPath, mimeType)
        return scanNewPathByMediaStore(activity, newPath, mimeType)
    }

    private suspend fun renameSingleByFile(
        activity: Activity,
        mimeType: String,
        oldMediaUri: Uri,
        oldPath: String,
        newFile: File
    ): FieldMap {
        Log.d(LOG_TAG, "rename file at path=$oldPath")
        val renamed = File(oldPath).renameTo(newFile)
        if (!renamed) {
            throw Exception("failed to rename file at path=$oldPath")
        }
        scanObsoletePath(activity, oldMediaUri, oldPath, mimeType)
        return scanNewPathByMediaStore(activity, newFile.path, mimeType)
    }

    override fun scanPostMetadataEdit(context: Context, path: String, uri: Uri, mimeType: String, newFields: FieldMap, callback: ImageOpCallback) {
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mimeType)) { _, _ ->
            val projection = arrayOf(
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE,
            )
            try {
                val cursor = context.contentResolver.query(uri, projection, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED).let { if (it != -1) newFields[EntryFields.DATE_MODIFIED_MILLIS] = cursor.getInt(it) * 1000 }
                    cursor.getColumnIndex(MediaStore.MediaColumns.SIZE).let { if (it != -1) newFields[EntryFields.SIZE_BYTES] = cursor.getLong(it) }
                    cursor.close()
                }
            } catch (e: Exception) {
                callback.onFailure(e)
                return@scanFile
            }
            getFileModifiedDateMillis(path)?.let { newFields[EntryFields.DATE_MODIFIED_MILLIS] = it }
            callback.onSuccess(newFields)
        }
    }

    // try to fetch the modified date from the file,
    // as it is more precise than the one from the Media Store
    private fun getFileModifiedDateMillis(path: String?): Long? {
        if (path != null) {
            try {
                return File(path).lastModified()
            } catch (_: SecurityException) {
                // ignore
            }
        }
        return null
    }

    private fun scanObsoletePath(context: Context, uri: Uri, path: String, mimeType: String) {
        val file = File(path)
        if (file.exists()) {
            Log.w(LOG_TAG, "Skip obsolete-path scan because the file still exists at path=$path")
            return
        }

        if (hasEntry(context, uri)) {
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mimeType)) { _, newUri: Uri? ->
                if (newUri != null && hasEntry(context, newUri)) {
                    Log.w(LOG_TAG, "Failed to clear Media Store entry at uri=$newUri path=$path")
                } else {
                    Log.w(LOG_TAG, "Cleared Media Store entry at uri=$newUri path=$path")
                }
            }
        }
    }

    // Remove the now-obsolete source Media Store entry after a move. The file is
    // already gone (renamed away, or copied then deleted), so we only have to drop
    // the index row. We clean both by content uri (when it is a Media Store uri)
    // and by path (via MediaScanner), because for file:// source uris `hasEntry`
    // cannot see the row and a bare content-resolver delete would silently do
    // nothing, leaving a dangling entry that re-appears after a media scan.
    // Drop the Media Store index row that used to point at the (now moved) source file.
    // We delete by BOTH the content uri and the file path, so this also works when the
    // source entry was a `file://` uri (where `hasEntry` cannot see the row). Without this,
    // the source folder keeps a stale "ghost" entry after a move or in-app delete.
    private fun clearStaleSourceEntry(activity: Activity, sourceUri: Uri, sourcePath: String?, mimeType: String) {
        try {
            if (hasEntry(activity, sourceUri)) {
                activity.contentResolver.delete(sourceUri, null, null)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "failed to delete content entry after move at uri=$sourceUri", e)
        }
        // Never issue a broad DATA-based delete here. Under scoped storage DATA can be
        // stale or reused; deleting the exact content URI is safe, while a file:// URI
        // is left for MediaScanner/reconciliation to clean without risking another row.
        if (sourceUri.scheme != ContentResolver.SCHEME_CONTENT && sourcePath != null) {
            MediaScannerConnection.scanFile(activity, arrayOf(sourcePath), arrayOf(mimeType), null)
        }
    }

    // Best-effort lookup of a media entry's display name (e.g. "video.mp4") from its
    // content uri. The `DISPLAY_NAME` column is readable even under scoped storage, so
    // this recovers the real file extension when we only hold a content uri (no direct
    // file path) — preventing moves from renaming `video.mp4` to `video.ts`.
    private fun getDisplayName(activity: Activity, uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return try {
            activity.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) else null
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "failed to get display name for uri=$uri", e)
            null
        }
    }

    suspend fun scanNewPathByMediaStore(context: Context, path: String, mimeType: String): FieldMap {
        // `scanFile` may (e.g. when copying to SD card on Android 10 (API 29)):
        // 1) yield no URI,
        // 2) yield a temporary URI that fails when queried,
        // 3) yield a temporary URI that succeeds when queried right away, but the Media Store actually won't have an entry for it until device reboot.
        fun scanUri(uri: Uri?): FieldMap? {
                uri ?: return null

                // we retrieve updated fields as the renamed/moved file became a new entry in the Media Store
                val projection = arrayOf(
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                )
                try {
                    val cursor = context.contentResolver.query(uri, projection, null, null, null)
                    if (cursor != null && cursor.moveToFirst()) {
                        val newFields = hashMapOf<String, Any?>(
                            EntryFields.ORIGIN to SourceEntry.ORIGIN_MEDIA_STORE_CONTENT,
                            EntryFields.URI to uri.toString(),
                            EntryFields.CONTENT_ID to uri.tryParseId(),
                            EntryFields.PATH to path,
                        )
                        cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED).let { if (it != -1) newFields[EntryFields.DATE_ADDED_SECS] = cursor.getInt(it) }
                        cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED).let { if (it != -1) newFields[EntryFields.DATE_MODIFIED_MILLIS] = cursor.getInt(it) * 1000 }
                        cursor.close()
                        getFileModifiedDateMillis(path)?.let { newFields[EntryFields.DATE_MODIFIED_MILLIS] = it }
                        return newFields
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "failed to scan uri=$uri", e)
                }
                return null
        }

        for (iteration in 0..5) {
            if (iteration > 0) {
                // Suspending delay keeps the MediaScanner callback thread available and
                // allows coroutine cancellation to stop further retries.
                delay(iteration * 100L)
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                StorageUtils.getVolumePath(context, path)?.let { volumePath ->
                    if (volumePath != StorageUtils.getPrimaryVolumePath(context)) delay(100L)
                }
            }

            val newUri = suspendCancellableCoroutine<Uri?> { cont ->
                MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mimeType)) { _, scannedUri ->
                    if (cont.isActive) cont.resume(scannedUri)
                }
            }
            if (newUri != null) {
                var contentUri: Uri? = null
                // `newURI` is possibly a file media URI (e.g. "content://media/12a9-8b42/file/62872")
                // but we need an image/video media URI (e.g. "content://media/external/images/media/62872")
                val contentId = newUri.tryParseId()
                if (contentId != null) {
                    if (isImage(mimeType)) {
                        contentUri = ContentUris.withAppendedId(IMAGE_CONTENT_URI, contentId)
                    } else if (isVideo(mimeType)) {
                        contentUri = ContentUris.withAppendedId(VIDEO_CONTENT_URI, contentId)
                    }
                }

                // prefer image/video content URI, fallback to original URI (possibly a file content URI)
                val newFields = scanUri(contentUri) ?: scanUri(newUri)

                if (newFields != null) {
                    return newFields
                }
            }
        }

        throw Exception("failed to scan new path=$path after 6 iterations")
    }

    fun getContentUriForPath(context: Context, path: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DATA} = ?"
        val selectionArgs = arrayOf(path)

        fun check(context: Context, contentUri: Uri): Uri? {
            var mediaContentUri: Uri? = null
            try {
                val cursor = context.contentResolver.query(contentUri, projection, selection, selectionArgs, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    if (idColumn != -1) {
                        val id = cursor.getLong(idColumn)
                        mediaContentUri = ContentUris.withAppendedId(contentUri, id)
                    }
                    cursor.close()
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "failed to get URI for contentUri=$contentUri path=$path", e)
            }
            return mediaContentUri
        }
        return check(context, IMAGE_CONTENT_URI) ?: check(context, VIDEO_CONTENT_URI)
    }

    companion object {
        private val LOG_TAG = LogUtils.createTag<MediaStoreImageProvider>()

        private val IMAGE_CONTENT_URI = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        private val VIDEO_CONTENT_URI = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        private val BASE_PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaColumns.DATE_TAKEN,
        )

        private val IMAGE_PROJECTION = arrayOf(
            *BASE_PROJECTION,
            MediaColumns.ORIENTATION,
        )

        private val VIDEO_PROJECTION = arrayOf(
            *BASE_PROJECTION,
            MediaColumns.DURATION,
            // `ORIENTATION` was only available for images before Android 10 (API 29)
            *if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(
                MediaStore.MediaColumns.ORIENTATION,
            ) else emptyArray()
        )
    }
}

object MediaColumns {
    // `DATE_TAKEN`, `ORIENTATION`, `DURATION` used to be in `MediaStore.[Images,Video].Media`
    // but were moved to `MediaStore.MediaColumns` for API 29
    // it is safe to use them because they are static strings that have not changed

    @SuppressLint("InlinedApi")
    const val DATE_TAKEN = MediaStore.MediaColumns.DATE_TAKEN

    @SuppressLint("InlinedApi")
    const val ORIENTATION = MediaStore.MediaColumns.ORIENTATION

    @SuppressLint("InlinedApi")
    const val DURATION = MediaStore.MediaColumns.DURATION
}

typealias NewEntryHandler = (entry: FieldMap) -> Unit

private typealias NewEntryChecker = (contentId: Long, dateModifiedMillis: Long) -> Boolean
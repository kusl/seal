package com.junkfood.seal.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.CheckResult
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.R
import java.io.File
import okhttp3.internal.closeQuietly

const val AUDIO_REGEX = "(mp3|aac|opus|m4a)$"
const val THUMBNAIL_REGEX = "\\.(jpg|png)$"
const val SUBTITLE_REGEX = "\\.(lrc|vtt|srt|ass|json3|srv.|ttml)$"
private const val PRIVATE_DIRECTORY_SUFFIX = ".Seal"

object FileUtil {

    /**
     * Whether [path] is a Storage Access Framework document URI (the `content://` scheme) rather
     * than an ordinary filesystem path.
     *
     * Downloads written to the SD card / a custom tree store their location as a `content://…`
     * document URI (see [moveFilesToSdcard], which records `destUri.toString()`); every other
     * download stores a plain absolute path such as
     * `/storage/emulated/0/Download/Seal/foo.mp4`. Only the former can be resolved through
     * [DocumentFile] — its accessors (`exists()`, `length()`, `name`, `delete()`) all issue a
     * `ContentResolver` query against the documents provider.
     *
     * Calling those accessors on a *filesystem* path is not just wasteful: `ContentResolver.query()`
     * for a URI with no matching provider returns a **null** Cursor, and androidx.documentfile then
     * does `cursor.getCount()` inside a `try { … } catch (e) { Log.w(TAG, "Failed query: " + e) }`.
     * That is the source of the
     *   "Failed query: java.lang.NullPointerException: Attempt to invoke interface method
     *    'int android.database.Cursor.getCount()' on a null object reference"
     * warnings — harmless in isolation (the call is caught and simply returns false/0/null), but
     * pure noise, an avoidable binder round-trip, and surfaced as Sentry breadcrumbs because the
     * logcat→breadcrumb integration runs at VERBOSE. Gating DocumentFile behind this check keeps
     * filesystem paths on a pure `File` code path with no ContentResolver involvement at all.
     */
    private fun isDocumentUri(path: String): Boolean = path.startsWith("content://")

    fun openFileFromResult(downloadResult: Result<List<String>>) {
        val filePaths = downloadResult.getOrNull()
        if (filePaths.isNullOrEmpty()) return
        openFile(filePaths.first()) {
            ToastUtil.makeToastSuspend(context.getString(R.string.file_unavailable))
        }
    }

    inline fun openFile(path: String, onFailureCallback: (Throwable) -> Unit) =
        path
            .runCatching {
                createIntentForOpeningFile(this)?.run { context.startActivity(this) }
                    ?: throw Exception()
            }
            .onFailure { onFailureCallback(it) }

    private fun createIntentForFile(path: String?): Intent? {
        if (path == null) return null

        // Behaviour is identical to the previous implementation for every input; only the ORDER of
        // the two checks changed, and the SAF branch is now reached exclusively for content URIs.
        //
        // Old flow (for ALL paths):  DocumentFile.exists()  →  fall back to File.exists().
        // For a filesystem path the DocumentFile.exists() probe always failed *and logged the
        // "Failed query" NPE* before the working File branch was tried. Since a stored path is
        // either a content URI or a filesystem path (never both), branching on the scheme yields
        // the same Uri the old code produced, minus the wasted/null SAF query:
        //   • content URI, exists      → its own content Uri          (old: `this.uri`)
        //   • content URI, missing     → null                         (old: File("content://…") missing → null)
        //   • file path, exists        → FileProvider Uri             (old: same, after a failed SAF probe)
        //   • file path, missing/null  → null                         (old: same, after a failed SAF probe)
        val uri =
            path
                .runCatching {
                    if (isDocumentUri(this)) {
                        DocumentFile.fromSingleUri(context, Uri.parse(this))
                            ?.takeIf { it.exists() }
                            ?.uri
                    } else {
                        File(this)
                            .takeIf { it.exists() }
                            ?.let {
                                FileProvider.getUriForFile(
                                    context,
                                    context.getFileProvider(),
                                    it,
                                )
                            }
                    }
                }
                .getOrNull() ?: return null

        return Intent().apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            data = uri
        }
    }

    fun createIntentForOpeningFile(path: String?): Intent? =
        createIntentForFile(path)?.let {
            it.apply {
                action = (Intent.ACTION_VIEW)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

    fun createIntentForSharingFile(path: String?): Intent? =
        createIntentForFile(path)?.apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, data)
            val mimeType = data?.let { context.contentResolver.getType(it) } ?: "media/*"
            setDataAndType(this.data, mimeType)
            clipData = ClipData(null, arrayOf(mimeType), ClipData.Item(data))
        }

    fun Context.getFileProvider() = "$packageName.provider"

    fun String.getFileSize(): Long =
        // Same result as before for every input; the SAF query is now reached only for content
        // URIs instead of "whenever File.length() == 0".
        //   • content URI  → DocumentFile.length()  (old: File("content://…").length() was 0, so it
        //                                             always fell through to exactly this)
        //   • file path    → File.length()          (old: returned File.length() when > 0; when 0 it
        //                                             fell through to DocumentFile on a non-content
        //                                             URI, whose null query also yields 0 — but logs
        //                                             "Failed query" on every empty/missing file)
        if (isDocumentUri(this)) {
            DocumentFile.fromSingleUri(context, Uri.parse(this))?.length() ?: 0L
        } else {
            File(this).length()
        }

    fun String.getFileName(): String =
        this.run {
            File(this).nameWithoutExtension.ifEmpty {
                DocumentFile.fromSingleUri(context, Uri.parse(this))?.name ?: "video"
            }
        }

    fun deleteFile(path: String) =
        path.runCatching {
            if (!File(path).delete()) DocumentFile.fromSingleUri(context, Uri.parse(this))?.delete()
        }

    @CheckResult
    fun scanFileToMediaLibraryPostDownload(title: String, downloadDir: String): List<String> =
        File(downloadDir)
            .walkTopDown()
            .filter { it.isFile && it.absolutePath.contains(title) }
            .map { it.absolutePath }
            .toMutableList()
            .apply {
                MediaScannerConnection.scanFile(context, this.toList().toTypedArray(), null, null)
                removeAll {
                    it.contains(Regex(THUMBNAIL_REGEX)) || it.contains(Regex(SUBTITLE_REGEX))
                }
            }

    fun scanDownloadDirectoryToMediaLibrary(downloadDir: String) =
        File(downloadDir)
            .walkTopDown()
            .filter { it.isFile }
            .map { it.absolutePath }
            .run {
                MediaScannerConnection.scanFile(context, this.toList().toTypedArray(), null, null)
            }

    @CheckResult
    fun moveFilesToSdcard(tempPath: File, sdcardUri: String): Result<List<String>> {
        val uriList = mutableListOf<String>()
        val destDir =
            Uri.parse(sdcardUri).run {
                DocumentsContract.buildDocumentUriUsingTree(
                    this,
                    DocumentsContract.getTreeDocumentId(this),
                )
            }
        val res =
            tempPath.runCatching {
                walkTopDown().forEach {
                    if (it.isDirectory) return@forEach
                    val mimeType =
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension) ?: "*/*"

                    val destUri =
                        DocumentsContract.createDocument(
                            context.contentResolver,
                            destDir,
                            mimeType,
                            it.name,
                        ) ?: return@forEach

                    val inputStream = it.inputStream()
                    val outputStream =
                        context.contentResolver.openOutputStream(destUri) ?: return@forEach
                    inputStream.copyTo(outputStream)
                    inputStream.closeQuietly()
                    outputStream.closeQuietly()
                    uriList.add(destUri.toString())
                }
                uriList
            }
        tempPath.deleteRecursively()
        return res
    }

    fun clearTempFiles(downloadDir: File): Int {
        var count = 0
        downloadDir.walkTopDown().forEach {
            if (it.isFile && !it.isHidden) {
                if (it.delete()) count++
            }
        }
        return count
    }

    fun Context.getConfigDirectory(): File = cacheDir

    fun Context.getConfigFile(suffix: String = "") = File(getConfigDirectory(), "config$suffix.txt")

    fun Context.getCookiesFile() = File(getConfigDirectory(), "cookies.txt")

    fun getExternalTempDir() =
        File(getExternalDownloadDirectory(), "tmp").apply {
            mkdirs()
            createEmptyFile(".nomedia")
        }

    fun Context.getSdcardTempDir(child: String?): File =
        getExternalTempDir().run { child?.let { resolve(it) } ?: this }

    fun Context.getArchiveFile(): File = filesDir.createEmptyFile("archive.txt").getOrThrow()

    fun Context.getInternalTempDir() = File(filesDir, "tmp")

    internal fun getExternalDownloadDirectory() =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Seal")
            .also { it.mkdir() }

    internal fun getExternalPrivateDownloadDirectory() =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            PRIVATE_DIRECTORY_SUFFIX,
        )

    fun File.createEmptyFile(fileName: String): Result<File> =
        this.runCatching {
                mkdirs()
                resolve(fileName).apply { this@apply.createNewFile() }
            }
            .onFailure { it.printStackTrace() }

    fun writeContentToFile(content: String, file: File): File = file.apply { writeText(content) }

    fun getRealPath(treeUri: Uri): String {
        val path: String = treeUri.path.toString()
        Log.d(TAG, path)
        if (!path.contains("primary:")) {
            ToastUtil.makeToast("This directory is not supported")
            return getExternalDownloadDirectory().absolutePath
        }
        val last: String = path.split("primary:").last()
        return Environment.getExternalStorageDirectory().absolutePath + "/$last"
    }

    private const val TAG = "FileUtil"
}

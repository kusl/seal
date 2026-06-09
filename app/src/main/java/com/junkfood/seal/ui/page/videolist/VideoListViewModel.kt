package com.junkfood.seal.ui.page.videolist

import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.junkfood.seal.R
import com.junkfood.seal.database.backup.BackupUtil
import com.junkfood.seal.database.backup.BackupUtil.decodeToBackup
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.FileUtil.getFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "VideoListViewModel"

class VideoListViewModel : ViewModel() {

    private val mutableStateFlow = MutableStateFlow(VideoListViewState())
    val stateFlow = mutableStateFlow.asStateFlow()
    private val viewState
        get() = stateFlow.value

    private val _mediaInfoFlow = DatabaseUtil.getDownloadHistoryFlow()

    val videoListFlow: Flow<List<DownloadedVideoInfo>> =
        _mediaInfoFlow.map { it.reversed().sortedBy { info -> info.filterByType() } }

    val searchedVideoListFlow =
        videoListFlow.combine(stateFlow) { list, state ->
            if (!state.isSearching || state.searchText.isBlank()) list
            else
                list.filter {
                    state.searchText.let { text ->
                        with(it) {
                            videoTitle.contains(text, ignoreCase = true) ||
                                videoAuthor.contains(text, ignoreCase = true) ||
                                extractor.contains(text, ignoreCase = true) ||
                                videoPath.contains(text, ignoreCase = true)
                        }
                    }
                }
        }

    val filterSetFlow =
        searchedVideoListFlow.map { infoList ->
            mutableSetOf<String>().apply { infoList.forEach { this.add(it.extractor) } }
        }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    //  Per-item file sizes for the download-history list.
    //
    //  Operator ORDER here is load-bearing. `flowOn(dispatcher)` only changes the dispatcher for
    //  operators *upstream* of it; everything downstream runs on whatever context *collects* the
    //  flow. This is collected in VideoListPage via `collectAsStateWithLifecycle(...)`, which
    //  collects on the main (UI) thread.
    //
    //  The previous code was:
    //
    //      videoListFlow.flowOn(Dispatchers.IO).map { list ->
    //          list.associate { it.id to it.videoPath.getFileSize() }
    //      }
    //
    //  In that ordering, only `videoListFlow` (the Room read + reverse/sort) ran on IO, while the
    //  `.map { … getFileSize() }` ran on the COLLECTOR — i.e. the main thread. `getFileSize()` does
    //  a `File.length()` stat for every entry in the *entire* history, and a ContentResolver/SAF
    //  query (`DocumentFile.length()`) for any SD-card or zero-byte entry. Because `videoListFlow`
    //  is backed by a Room `Flow`, it re-emits on *every* change to the download history — including
    //  every completed download — so this whole stat/binder sweep was re-run on the main thread on
    //  each completion. With a large history and the rapid-fire downloads of the heavy
    //  app-switching workflow, that repeatedly stalls the UI thread (and recovers as soon as the
    //  page stops collecting, e.g. when the app is backgrounded). It is also where the
    //  "Failed query: NullPointerException … Cursor.getCount()" breadcrumbs originate (the SAF
    //  query inside `getFileSize()` for entries whose `ContentResolver.query()` returns null).
    //
    //  Moving `flowOn(Dispatchers.IO)` to AFTER the `.map { … }` puts the size computation upstream
    //  of `flowOn`, so it now runs on IO. The collector only ever receives the finished `Map`.
    //  The emitted value is identical to before — only the thread it is computed on changes.
    val fileSizeMapFlow =
        videoListFlow
            .map { list -> list.associate { it.id to it.videoPath.getFileSize() } }
            .flowOn(Dispatchers.IO)

    fun clickVideoFilter() {
        if (mutableStateFlow.value.videoFilter)
            mutableStateFlow.update { it.copy(videoFilter = false) }
        else mutableStateFlow.update { it.copy(videoFilter = true, audioFilter = false) }
    }

    fun clickAudioFilter() {
        if (mutableStateFlow.value.audioFilter)
            mutableStateFlow.update { it.copy(audioFilter = false) }
        else mutableStateFlow.update { it.copy(audioFilter = true, videoFilter = false) }
    }

    fun clickExtractorFilter(index: Int) {
        if (mutableStateFlow.value.activeFilterIndex == index)
            mutableStateFlow.update { it.copy(activeFilterIndex = -1) }
        else mutableStateFlow.update { it.copy(activeFilterIndex = index) }
    }

    fun toggleSearch(isSearching: Boolean = !viewState.isSearching) {
        mutableStateFlow.update { it.copy(isSearching = isSearching, searchText = "") }
    }

    fun updateSearchText(text: String) {
        mutableStateFlow.update { it.copy(searchText = text) }
    }

    fun deleteDownloadHistory(infoList: List<DownloadedVideoInfo>, deleteFile: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseUtil.deleteInfoList(infoList = infoList, deleteFile = deleteFile)
        }
    }

    fun importBackupFromUri(context: Context, uri: Uri, onComplete: suspend (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var res = 0
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText().let {
                    res = importBackupFromText(it)
                }
            }
            withContext(Dispatchers.Main) { onComplete(res) }
        }
    }

    fun importBackupFromText(string: String, onComplete: suspend (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = importBackupFromText(string)
            withContext(Dispatchers.Main) { onComplete(res) }
        }
    }

    private suspend fun importBackupFromText(string: String): Int {
        string.decodeToBackup().onSuccess {
            return DatabaseUtil.importBackup(
                backup = it,
                types = setOf(BackupUtil.BackupType.DownloadHistory),
            )
        }
        return 0
    }

    fun showImportedSnackbar(hostState: SnackbarHostState, context: Context, importedCount: Int) {
        viewModelScope.launch(Dispatchers.Main) {
            hostState.showSnackbar(
                message =
                    context
                        .getString(R.string.download_history_imported)
                        .format(
                            context.resources
                                .getQuantityString(R.plurals.item_count, importedCount)
                                .format(importedCount)
                        )
            )
        }
    }

    data class VideoListViewState(
        val activeFilterIndex: Int = -1,
        val videoFilter: Boolean = false,
        val audioFilter: Boolean = false,
        val isSearching: Boolean = false,
        val searchText: String = "",
    )
}

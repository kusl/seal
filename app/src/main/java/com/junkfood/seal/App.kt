package com.junkfood.seal

import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.content.getSystemService
import com.google.android.material.color.DynamicColors
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.DownloaderV2Impl
import com.junkfood.seal.ui.page.download.HomePageViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.page.settings.directory.Directory
import com.junkfood.seal.ui.page.settings.network.CookiesViewModel
import com.junkfood.seal.ui.page.videolist.VideoListViewModel
import com.junkfood.seal.util.AUDIO_DIRECTORY
import com.junkfood.seal.util.COMMAND_DIRECTORY
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.FileUtil.createEmptyFile
import com.junkfood.seal.util.FileUtil.getCookiesFile
import com.junkfood.seal.util.FileUtil.getExternalDownloadDirectory
import com.junkfood.seal.util.FileUtil.getExternalPrivateDownloadDirectory
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.SDCARD_URI
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.VIDEO_DIRECTORY
import com.junkfood.seal.util.YT_DLP_VERSION
import com.tencent.mmkv.MMKV
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)

        startKoin {
            androidLogger()
            androidContext(this@App)
            modules(
                module {
                    single<DownloaderV2> { DownloaderV2Impl(androidContext()) }
                    viewModel { DownloadDialogViewModel(downloader = get()) }
                    viewModel { HomePageViewModel() }
                    viewModel { CookiesViewModel() }
                    viewModel { VideoListViewModel() }
                }
            )
        }

        context = applicationContext
        packageInfo =
            packageManager.run {
                if (Build.VERSION.SDK_INT >= 33)
                    getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                else getPackageInfo(packageName, 0)
            }
        applicationScope = CoroutineScope(SupervisorJob())
        DynamicColors.applyToActivitiesIfAvailable(this)

        clipboard = getSystemService()!!
        connectivityManager = getSystemService()!!

        applicationScope.launch((Dispatchers.IO)) {
            try {
                YoutubeDL.init(this@App)
                FFmpeg.init(this@App)
                Aria2c.init(this@App)
                DownloadUtil.getCookiesContentFromDatabase().getOrNull()?.let {
                    FileUtil.writeContentToFile(it, getCookiesFile())
                }
                UpdateUtil.deleteOutdatedApk()
            } catch (th: Throwable) {
                withContext(Dispatchers.Main) { startCrashReportActivity(th) }
            }
        }

        videoDownloadDir = VIDEO_DIRECTORY.getString(getExternalDownloadDirectory().absolutePath)

        audioDownloadDir = AUDIO_DIRECTORY.getString(File(videoDownloadDir, "Audio").absolutePath)
        if (!PreferenceUtil.containsKey(COMMAND_DIRECTORY)) {
            COMMAND_DIRECTORY.updateString(videoDownloadDir)
        }
        if (Build.VERSION.SDK_INT >= 26) NotificationUtil.createNotificationChannel()

        Thread.setDefaultUncaughtExceptionHandler { _, e -> startCrashReportActivity(e) }
    }

    private fun startCrashReportActivity(th: Throwable) {
        th.printStackTrace()
        startActivity(
            Intent(this, CrashReportActivity::class.java)
                .setAction("$packageName.error_report")
                .apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("error_report", getVersionReport() + "\n" + th.stackTraceToString())
                }
        )
    }

    companion object {
        lateinit var clipboard: ClipboardManager
        lateinit var videoDownloadDir: String
        lateinit var audioDownloadDir: String
        lateinit var applicationScope: CoroutineScope
        lateinit var connectivityManager: ConnectivityManager
        lateinit var packageInfo: PackageInfo

        // ────────────────────────────────────────────────────────────────────────────────────
        //  Foreground-service binding
        // ────────────────────────────────────────────────────────────────────────────────────
        //
        //  The download work scheduler toggles the foreground service on every *structural*
        //  task-state transition (running ⇄ idle). startService()/stopService() are therefore
        //  called frequently, and from two different threads:
        //
        //    • the work scheduler runs on Dispatchers.Default (background), and
        //    • QuickDownloadActivity calls startService() on the main thread.
        //
        //  The previous implementation gated startService() on a flag that was only flipped to
        //  `true` inside ServiceConnection.onServiceConnected — which is delivered
        //  asynchronously on the main thread, *after* bindService() returns. That left a window
        //  in which a second startService() (very common during a burst of state changes) saw the
        //  flag still `false` and issued a *second* bindService() for the same connection. Each
        //  bind needs a matching unbind, so the surplus bind leaked (logcat:
        //  "ServiceConnectionLeaked: Service has leaked ServiceConnection ... that was originally
        //  bound here"). Over a long session of downloading + switching apps, those leaked
        //  bindings (and the foreground-service lifecycle callbacks they spawn on the main thread)
        //  pile up and can starve the UI thread — exactly the "stops responding after a while,
        //  recovers after backgrounding" symptom.
        //
        //  Fixes here:
        //    1. Track binding state *synchronously* at the moment we call bind/unbind (isBound),
        //       NOT from the async callback. This makes start/stop genuinely idempotent: a
        //       redundant startService() while bound is a no-op, and an actual bind only happens
        //       on a true idle→running edge.
        //    2. Guard the whole start/stop with a lock so the background scheduler and the main
        //       thread share one consistent view of the flag.
        //    3. If bindService() returns false (system not bringing the service up), release the
        //       connection we passed in, per the Android contract, so nothing leaks.

        private val serviceLock = Any()

        @Volatile private var isBound = false

        /**
         * Public, read-only mirror of the binding state. Kept so existing call sites that read
         * [App.isServiceRunning] continue to compile and behave as before.
         */
        @JvmStatic
        val isServiceRunning: Boolean
            get() = isBound

        private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(className: ComponentName, service: IBinder) {
                    // Binding state is tracked synchronously at bind/unbind time (see above), not
                    // here. We keep the cast purely as a sanity check on the returned binder.
                    @Suppress("UNUSED_VARIABLE")
                    val binder = service as? DownloadService.DownloadServiceBinder
                }

                override fun onServiceDisconnected(arg0: ComponentName) {
                    // The service process went away (e.g. it was killed). Reflect that so a future
                    // startService() will re-bind instead of being short-circuited by a stale flag.
                    synchronized(serviceLock) { isBound = false }
                }
            }

        fun startService() =
            synchronized(serviceLock) {
                if (isBound) return@synchronized
                val intent = Intent(context.applicationContext, DownloadService::class.java)
                val bringingUp =
                    try {
                        context.applicationContext.bindService(
                            intent,
                            connection,
                            Context.BIND_AUTO_CREATE,
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                if (bringingUp) {
                    isBound = true
                } else {
                    // bindService() did not start the service. We must still unbind the connection
                    // we just registered, otherwise it leaks.
                    try {
                        context.applicationContext.unbindService(connection)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

        fun stopService() =
            synchronized(serviceLock) {
                if (!isBound) return@synchronized
                isBound = false
                try {
                    context.applicationContext.unbindService(connection)
                } catch (e: Exception) {
                    // Connection was already unregistered (e.g. the service died). Nothing to do.
                    e.printStackTrace()
                }
            }

        val privateDownloadDir: String
            get() =
                getExternalPrivateDownloadDirectory().run {
                    createEmptyFile(".nomedia")
                    absolutePath
                }

        fun updateDownloadDir(uri: Uri, directoryType: Directory) {
            when (directoryType) {
                Directory.AUDIO -> {
                    val path = FileUtil.getRealPath(uri)
                    audioDownloadDir = path
                    PreferenceUtil.encodeString(AUDIO_DIRECTORY, path)
                }

                Directory.VIDEO -> {
                    val path = FileUtil.getRealPath(uri)
                    videoDownloadDir = path
                    PreferenceUtil.encodeString(VIDEO_DIRECTORY, path)
                }

                Directory.CUSTOM_COMMAND -> {
                    val path = FileUtil.getRealPath(uri)
                }

                Directory.SDCARD -> {
                    context.contentResolver?.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    PreferenceUtil.encodeString(SDCARD_URI, uri.toString())
                }
            }
        }

        fun getVersionReport(): String {
            val versionName = packageInfo.versionName
            val page = packageInfo
            val versionCode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                }
            val release =
                if (Build.VERSION.SDK_INT >= 30) {
                    Build.VERSION.RELEASE_OR_CODENAME
                } else {
                    Build.VERSION.RELEASE
                }
            return StringBuilder()
                .append("App version: $versionName ($versionCode)\n")
                .append("Device information: Android $release (API ${Build.VERSION.SDK_INT})\n")
                .append("Supported ABIs: ${Build.SUPPORTED_ABIS.contentToString()}\n")
                .append("Yt-dlp version: ${YT_DLP_VERSION.getString()}\n")
                .toString()
        }

        fun isFDroidBuild(): Boolean = BuildConfig.FLAVOR == "fdroid"

        fun isDebugBuild(): Boolean = BuildConfig.DEBUG

        @SuppressLint("StaticFieldLeak") lateinit var context: Context
    }
}

package com.junkfood.seal

import android.annotation.SuppressLint
import android.app.Activity
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
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
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
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
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
        val onCreateStart = SystemClock.uptimeMillis()

        // ──────────────────────────────────────────────────────────────────────────────────────
        //  Initialization order is load-bearing here. Do NOT reorder without reading this.
        // ──────────────────────────────────────────────────────────────────────────────────────
        //
        //  initSentry() (called below) reads a preference — YT_DLP_VERSION.getString() — from
        //  inside the SentryAndroid.init { … } options lambda. The very first preference access
        //  forces class initialization of PreferenceUtil, whose static initializer eagerly builds
        //  templateListStateFlow:
        //
        //      val templateListStateFlow =
        //          DatabaseUtil.getTemplateFlow().stateIn(applicationScope, …)
        //
        //  That single property transitively requires *both* of the companion's lateinit fields:
        //    • DatabaseUtil.getTemplateFlow() forces DatabaseUtil's own static init, which builds
        //      the Room database via Room.databaseBuilder(context, …)   →  needs App.context
        //    • .stateIn(applicationScope, …)                            →  needs App.applicationScope
        //
        //  If either is still uninitialized at that moment the chain throws
        //  UninitializedPropertyAccessException → ExceptionInInitializerError →
        //  NoClassDefFoundError(PreferenceUtil), and the app dies the instant it is launched.
        //
        //  Therefore these three cheap, dependency-free steps MUST run before initSentry():
        //    1. context           — required by DatabaseUtil's Room builder
        //    2. applicationScope  — required by the .stateIn(…) above (and DatabaseUtil.init)
        //    3. MMKV.initialize   — PreferenceUtil's getters read from MMKV
        //  None of them depends on Koin or on the heavyweight init below, so this is safe and early.
        //
        //  NOTE: this is also the ONLY place App.context may ever be assigned. It must always be
        //  the *application* context. MainActivity used to overwrite it with the Activity's
        //  baseContext on every onCreate(), leaking each Activity instance (and its Compose trees)
        //  for the lifetime of the process — see MainActivity.kt for the full story.
        context = applicationContext
        applicationScope = CoroutineScope(SupervisorJob())
        MMKV.initialize(this)

        // Initialize Sentry as early as correctness allows — immediately after the minimal app
        // state it transitively depends on (context, applicationScope, MMKV), and before the
        // heavyweight youtube-dl/ffmpeg/aria2c initialization further down. Initializing here means
        // the SDK's crash handler, ANR detection, breadcrumb collectors, and offline cache are all
        // live for the *entire* remainder of startup, so a crash or freeze that happens while
        // youtube-dl/ffmpeg/aria2c are initializing is still captured.
        //
        // This is a no-op when BuildConfig.SENTRY_DSN is blank, which is the case for the `fdroid`
        // flavor (see app/build.gradle.kts), so F-Droid builds remain completely telemetry-free.
        initSentry()

        // The in-process freeze detector. Sentry's ANRv2 can only report stalls that the OS itself
        // escalated to a full ANR (≥ 5 s of ignored *input*); the freezes under investigation
        // recover (or get force-killed) before that, which is exactly why no logs ever appeared.
        // The watchdog measures main-looper liveness directly with a 2-second threshold and dumps
        // all thread stacks to logcat, to a rotating file, and (gated on the same flag as
        // everything else) to Sentry. See MainThreadWatchdog.kt for the full design rationale.
        MainThreadWatchdog.install(context = this, sentryEnabled = isSentryEnabled)

        // Log every Activity lifecycle transition. Each Log.d below is rewritten into a Sentry
        // breadcrumb by the Gradle plugin's logcat instrumentation, so when a freeze report or
        // crash arrives, its timeline shows exactly the app-switching churn (pause/stop/recreate
        // bursts) that precedes the symptom. Locally: `adb logcat -s AppLifecycle`.
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                private val tag = "AppLifecycle"

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    Log.d(
                        tag,
                        "${activity.javaClass.simpleName} created" +
                            if (savedInstanceState != null) " (restored)" else "",
                    )
                }

                override fun onActivityStarted(activity: Activity) {
                    Log.d(tag, "${activity.javaClass.simpleName} started")
                }

                override fun onActivityResumed(activity: Activity) {
                    Log.d(tag, "${activity.javaClass.simpleName} resumed")
                }

                override fun onActivityPaused(activity: Activity) {
                    Log.d(tag, "${activity.javaClass.simpleName} paused")
                }

                override fun onActivityStopped(activity: Activity) {
                    Log.d(tag, "${activity.javaClass.simpleName} stopped")
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                    Log.d(tag, "${activity.javaClass.simpleName} saveInstanceState")
                }

                override fun onActivityDestroyed(activity: Activity) {
                    Log.d(tag, "${activity.javaClass.simpleName} destroyed")
                }
            }
        )

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

        // minSdk is 35: the modern PackageInfoFlags overload always exists, so the old
        // `if (SDK_INT >= 33)` fork is gone.
        packageInfo =
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
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
                // This failure is *caught*, so the global uncaught-exception handler below will not
                // see it. Report it to Sentry explicitly so initialization failures (a common
                // source of "the app is broken on some devices" reports) are still visible, then
                // fall back to the existing on-device crash screen exactly as before.
                Log.e(TAG, "youtubedl-android initialization failed", th)
                if (isSentryEnabled) {
                    Sentry.captureException(th)
                }
                withContext(Dispatchers.Main) { startCrashReportActivity(th) }
            }
        }

        videoDownloadDir = VIDEO_DIRECTORY.getString(getExternalDownloadDirectory().absolutePath)

        audioDownloadDir = AUDIO_DIRECTORY.getString(File(videoDownloadDir, "Audio").absolutePath)
        if (!PreferenceUtil.containsKey(COMMAND_DIRECTORY)) {
            COMMAND_DIRECTORY.updateString(videoDownloadDir)
        }
        // minSdk 35 ≥ 26: notification channels always exist; the version gate is gone.
        NotificationUtil.createNotificationChannel()

        installGlobalCrashHandler()

        Log.i(TAG, "App.onCreate completed in ${SystemClock.uptimeMillis() - onCreateStart} ms")
    }

    /**
     * Whether Sentry is active for this build. We gate purely on the DSN being non-blank: the
     * `fdroid` flavor sets [BuildConfig.SENTRY_DSN] to the empty string (see app/build.gradle.kts),
     * so this is automatically `false` there and Sentry is never initialized.
     */
    private val isSentryEnabled: Boolean
        get() = BuildConfig.SENTRY_DSN.isNotBlank()

    /**
     * Configures and starts the Sentry SDK with verbose, "capture as much as possible" settings.
     *
     * The overarching goal here is to debug the app-switching UI freeze / ANR, so the configuration
     * is deliberately aggressive: ANR detection (v2 on Android 11+) with the raw OS thread dump,
     * full thread stacks, screenshots and a view-hierarchy snapshot on errors, a long breadcrumb
     * trail, performance tracing (which also powers Sentry's server-side "DB/file I/O on the main
     * thread" ANR root-cause analysis), and a larger on-disk envelope cache so nothing is lost
     * while offline.
     *
     * Two related knobs live in `AndroidManifest.xml` instead of here, because they are read by
     * Sentry's init ContentProvider / are experimental:
     *  - `io.sentry.auto-init = false` — we initialize manually (here) for full programmatic control.
     *  - `io.sentry.anr.profiling.sample-rate = 1.0` — ANR stack profiling (a flamegraph of what the
     *    main thread was doing at the moment of an ANR), available since SDK 8.35.0.
     */
    private fun initSentry() {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return // Sentry disabled for this build (e.g. the F-Droid flavor).

        SentryAndroid.init(this) { options ->
            options.dsn = dsn

            // ── Identify the build so events can be grouped/filtered in the Sentry UI ──────────
            options.release =
                "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.environment =
                if (BuildConfig.DEBUG) "${BuildConfig.FLAVOR}-debug" else BuildConfig.FLAVOR
            options.dist = BuildConfig.VERSION_CODE.toString()
            options.setTag("flavor", BuildConfig.FLAVOR)
            options.setTag("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            // yt-dlp version is stored in MMKV (initialized above). It may be empty on a fresh
            // install before the first update check; that's fine, we just skip the tag then.
            YT_DLP_VERSION.getString().takeIf { it.isNotBlank() }
                ?.let { options.setTag("yt_dlp_version", it) }

            // ── Log as much as possible (the entire point of this integration) ────────────────
            // Verbose SDK self-logging only in debug builds, so release logcat stays clean.
            options.isDebug = BuildConfig.DEBUG
            // Attach request URLs/headers, IP, device name, etc. NOTE: this sends additional
            // personally-identifiable information — see the disclosure in README.md.
            options.isSendDefaultPii = true
            // Attach stack traces for *every* running thread to events, not just the crashing one.
            // This is the single most useful flag for understanding an ANR/deadlock.
            options.isAttachThreads = true
            // Capture a screenshot and a JSON snapshot of the view hierarchy at the moment of error.
            options.isAttachScreenshot = true
            options.isAttachViewHierarchy = true
            // Keep a longer trail of breadcrumbs (default is 100). The Sentry Gradle plugin turns
            // every android.util.Log call in the app into a breadcrumb (see app/build.gradle.kts),
            // so this directly controls how much of that log history rides along with each event.
            options.maxBreadcrumbs = 200
            // Enable Sentry Logs so logs captured via Sentry.logger() are forwarded as well.
            options.logs.isEnabled = true
            // Richer native-crash context using Android tombstones (the native NDK crash handler
            // itself ships in sentry-android and is on by default).
            options.isTombstoneEnabled = true

            // ── ANR detection: the primary motivation for adding Sentry ───────────────────────
            options.isAnrEnabled = true
            // Match Android's own 5-second ANR threshold (used by the pre-API-30 watchdog).
            options.anrTimeoutIntervalMillis = 5_000L
            // On the first launch after this SDK is added, also report ANRs that the OS recorded
            // *before* Sentry existed (from ApplicationExitInfo history).
            options.isReportHistoricalAnrs = true
            // Attach the raw thread dump the OS captured for the ANR (held locks, all threads, …).
            options.isAttachAnrThreadDump = true

            // ── Performance tracing ───────────────────────────────────────────────────────────
            // 100% sampling: capture every transaction. Besides giving timing data, this is what
            // lets Sentry link a slow Room/SQLite or file-I/O span (instrumented by the Gradle
            // plugin) to an ANR event as its root cause. Fine for a debugging-focused build; dial
            // this down later if event volume becomes a concern.
            options.tracesSampleRate = 1.0

            // ── Offline durability: "store the logs and send them next time online" ───────────
            // Sentry already persists every envelope (crash, ANR, log, breadcrumb-carrying event)
            // to the app cache dir and re-sends on the next launch / when connectivity returns.
            // We simply keep more of them so a long offline stretch doesn't drop older events.
            options.maxCacheItems = 100
        }
    }

    /**
     * Installs the process-wide uncaught-exception handler.
     *
     * Behaviour is intentionally identical to the original single line this replaces — show the
     * on-device [CrashReportActivity] — with one addition: when Sentry is active, the crash is also
     * handed off to Sentry's own handler so it is captured, written to disk, and flushed before the
     * process dies (and the process is then terminated, as it would be normally).
     *
     * Ordering matters. [SentryAndroid.init] (called earlier in [onCreate]) installs Sentry's
     * handler as the current default; we capture it here as [downstream]. Sentry's handler ends by
     * delegating to the system handler, which kills the process, so we must launch our crash screen
     * *first* and only then hand off. When Sentry is **not** active, we deliberately do not chain to
     * any downstream handler, preserving the exact pre-Sentry behaviour.
     */
    private fun installGlobalCrashHandler() {
        val sentryActive = isSentryEnabled
        val downstream = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            startCrashReportActivity(throwable)
            if (sentryActive) {
                downstream?.uncaughtException(thread, throwable)
            }
        }
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
        private const val TAG = "App"

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
        //
        //  Diagnostics (this round): bindService()/unbindService() are binder IPC calls into
        //  system_server. Under heavy system load they can take surprisingly long — and because
        //  both run inside `serviceLock`, a slow call made by the background scheduler can briefly
        //  block a main-thread caller on the lock. The timing logs below make any such stall
        //  visible in logcat and (via the logcat instrumentation) as Sentry breadcrumbs, so the
        //  watchdog's stack dumps can be correlated with a concrete cause.

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
                    val binder = service as? DownloadService.DownloadServiceBinder
                    Log.i(
                        TAG,
                        "onServiceConnected: ${className.shortClassName}" +
                            " (binder ok=${binder != null})",
                    )
                }

                override fun onServiceDisconnected(arg0: ComponentName) {
                    // The service process went away (e.g. it was killed). Reflect that so a future
                    // startService() will re-bind instead of being short-circuited by a stale flag.
                    Log.w(TAG, "onServiceDisconnected: ${arg0.shortClassName} — service died")
                    synchronized(serviceLock) { isBound = false }
                }
            }

        fun startService() =
            synchronized(serviceLock) {
                if (isBound) return@synchronized
                Log.i(TAG, "startService: binding DownloadService")
                val intent = Intent(context.applicationContext, DownloadService::class.java)
                val bindStart = SystemClock.uptimeMillis()
                val bringingUp =
                    try {
                        context.applicationContext.bindService(
                            intent,
                            connection,
                            Context.BIND_AUTO_CREATE,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "startService: bindService threw", e)
                        false
                    }
                val bindMs = SystemClock.uptimeMillis() - bindStart
                if (bringingUp) {
                    isBound = true
                    Log.i(TAG, "startService: bound (bindService took $bindMs ms)")
                } else {
                    // bindService() did not start the service. We must still unbind the connection
                    // we just registered, otherwise it leaks.
                    Log.w(
                        TAG,
                        "startService: bindService returned false after $bindMs ms" +
                            " — releasing connection",
                    )
                    try {
                        context.applicationContext.unbindService(connection)
                    } catch (e: Exception) {
                        Log.w(TAG, "startService: cleanup unbindService threw", e)
                    }
                }
            }

        fun stopService() =
            synchronized(serviceLock) {
                if (!isBound) return@synchronized
                isBound = false
                Log.i(TAG, "stopService: unbinding DownloadService")
                val unbindStart = SystemClock.uptimeMillis()
                try {
                    context.applicationContext.unbindService(connection)
                    val unbindMs = SystemClock.uptimeMillis() - unbindStart
                    Log.i(TAG, "stopService: unbound (unbindService took $unbindMs ms)")
                } catch (e: Exception) {
                    // Connection was already unregistered (e.g. the service died). Nothing to do.
                    Log.w(TAG, "stopService: unbindService threw — connection already gone", e)
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
            // minSdk 35 ≥ 28: longVersionCode always exists, and ≥ 30 means RELEASE_OR_CODENAME
            // always exists, so both legacy forks (and the dead `val page` that sat here) are gone.
            val versionCode = packageInfo.longVersionCode
            val release = Build.VERSION.RELEASE_OR_CODENAME
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

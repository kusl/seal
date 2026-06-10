package com.junkfood.seal

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.sentry.Sentry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A main-thread responsiveness watchdog that catches the freezes Sentry's ANR detection cannot.
 *
 * ## Why this exists
 *
 * Sentry's ANRv2 integration (on Android 11+) reports ANRs from [android.app.ApplicationExitInfo]
 * — i.e. it can only report a freeze **after the OS itself declared an ANR**, which requires the
 * main thread to ignore an *input event* for ~5 seconds. The freeze this app suffers after heavy
 * app-switching routinely escapes that net, in three ways:
 *
 *  1. The stall lasts 1–4 seconds and recovers → never an OS ANR → nothing for Sentry to report.
 *  2. The user swipes the app away from recents while it is frozen → the exit is recorded as
 *     `REASON_USER_REQUESTED`, not `REASON_ANR` → again nothing to report.
 *  3. The freeze happens while no input event is pending (e.g. right as the app returns to the
 *     foreground) → the 5-second input-dispatch clock never even starts.
 *
 * In all three cases the symptom is exactly what was observed: *the app visibly freezes, and no
 * log of it exists anywhere.* This watchdog closes that gap by measuring main-looper liveness
 * directly, in-process, with a much lower threshold ([STALL_THRESHOLD_MS] = 2 s), and writing a
 * full diagnosis the moment a stall is confirmed — while the process is still alive.
 *
 * ## How it works
 *
 * A single daemon thread loops forever: it posts a tiny heartbeat [Runnable] to the main-thread
 * [Handler] and then watches the clock. If the heartbeat has not executed within
 * [STALL_THRESHOLD_MS], the main thread is by definition not processing its message queue, and we
 * capture, once per stall:
 *
 *  - the **main thread's stack trace** (the smoking gun — what the main thread is doing right now),
 *  - the stacks of **every other live thread** (to expose lock owners / binder peers),
 *  - written to **logcat** (chunked, `Log.e`, tag `MainThreadWatchdog` — grep-able via
 *    `adb logcat -s MainThreadWatchdog`),
 *  - appended to a **rotating file** under `getExternalFilesDir("watchdog")` so the report survives
 *    logcat rotation and process death, and is readable on-device with any file manager
 *    (`Android/data/<pkg>/files/watchdog/`),
 *  - and, when Sentry is enabled for this build, a Sentry **event** whose exception stack *is* the
 *    main thread's stack ([MainThreadStallException]), so it groups meaningfully in the dashboard.
 *    Sentry persists the envelope to disk immediately, so even if the user kills the frozen app a
 *    second later, the event uploads on the next launch — this is the primary answer to
 *    "the app froze and there are no logs".
 *
 * After reporting, the watchdog waits for the heartbeat to finally run, logs the total measured
 * stall duration (a `Log.w`, which the Sentry logcat instrumentation also turns into a breadcrumb),
 * and resumes watching. One report per stall; no repeated spam while a single long freeze persists.
 *
 * ## Why it is safe
 *
 *  - The watchdog thread is a daemon: it can never keep the process alive.
 *  - It allocates one small object per poll (every 500 ms) and does no I/O at all until a stall is
 *    actually detected — steady-state overhead is unmeasurable.
 *  - Reporting work (stack collection, file write, Sentry capture) happens entirely on the
 *    watchdog thread, never on the (already stuck) main thread.
 *  - Every step of reporting is individually try/caught: a failure to write the file, say, never
 *    prevents the logcat dump, and nothing here can ever crash the app.
 *  - While a debugger is attached, detection is suspended (breakpoints freeze the main thread by
 *    design and would otherwise produce a flood of false positives).
 */
object MainThreadWatchdog {

    private const val TAG = "MainThreadWatchdog"

    /** Main-thread unresponsiveness, in ms, at which a stall is declared and reported. */
    private const val STALL_THRESHOLD_MS = 2_000L

    /** How often the watchdog posts a heartbeat / re-checks an outstanding one. */
    private const val POLL_INTERVAL_MS = 500L

    /** Maximum number of stall report files kept in the watchdog directory (oldest pruned). */
    private const val MAX_REPORT_FILES = 10

    /** logcat truncates entries around ~4 KB; chunking keeps every line of the dump visible. */
    private const val LOGCAT_CHUNK_CHARS = 3_500

    private val installed = AtomicBoolean(false)

    /**
     * Starts the watchdog. Call once from [App.onCreate], after Sentry initialization (the
     * [sentryEnabled] flag is decided there). Subsequent calls are no-ops.
     *
     * @param context used only to resolve the on-disk report directory; the application context is
     *   taken from it immediately, so no Activity/Service can leak through here.
     * @param sentryEnabled when false (the F-Droid flavor), the Sentry capture step is skipped
     *   entirely and the watchdog stays purely local (logcat + file) — zero telemetry.
     */
    fun install(context: Context, sentryEnabled: Boolean) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext

        val thread =
            Thread(
                {
                    watchLoop(appContext, sentryEnabled)
                },
                "SealMainThreadWatchdog",
            )
        thread.isDaemon = true
        thread.priority = Thread.NORM_PRIORITY
        thread.start()
        Log.i(
            TAG,
            "Installed: threshold=${STALL_THRESHOLD_MS}ms" +
                " poll=${POLL_INTERVAL_MS}ms sentry=$sentryEnabled",
        )
    }

    private fun watchLoop(appContext: Context, sentryEnabled: Boolean) {
        val mainHandler = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread

        while (true) {
            // A fresh token per cycle: no state is shared between cycles, so a *very* late
            // heartbeat from a previous cycle can never be mistaken for the current one.
            val beat = AtomicBoolean(false)
            val postedAtUptime = SystemClock.uptimeMillis()
            val posted = mainHandler.post { beat.set(true) }
            if (!posted) {
                // The main looper is quitting — the process is going away. Stop quietly.
                Log.i(TAG, "Main looper is quitting; watchdog exiting")
                return
            }

            if (!sleepQuietly(POLL_INTERVAL_MS)) return

            if (beat.get()) continue // Healthy: heartbeat ran promptly. By far the common path.

            // Heartbeat hasn't run yet. Keep waiting (in small steps) until either it runs or the
            // stall threshold is crossed.
            while (!beat.get() &&
                SystemClock.uptimeMillis() - postedAtUptime < STALL_THRESHOLD_MS) {
                if (!sleepQuietly(POLL_INTERVAL_MS)) return
            }

            if (beat.get()) continue // Recovered just under the threshold; not worth a report.

            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                // A breakpoint, not a bug. Wait out the debug session without reporting.
                while (!beat.get()) {
                    if (!sleepQuietly(POLL_INTERVAL_MS)) return
                }
                continue
            }

            // ── Stall confirmed: the main thread has processed nothing for ≥ STALL_THRESHOLD_MS ──
            val stalledForSoFar = SystemClock.uptimeMillis() - postedAtUptime
            reportStall(appContext, sentryEnabled, mainThread, stalledForSoFar)

            // One report per stall: now simply wait for recovery, then log how long it really took.
            while (!beat.get()) {
                if (!sleepQuietly(POLL_INTERVAL_MS)) return
            }
            val totalStall = SystemClock.uptimeMillis() - postedAtUptime
            // Log.w → also becomes a Sentry breadcrumb via the Gradle plugin's logcat
            // instrumentation, so the recovery duration rides along with any later event.
            Log.w(TAG, "Main thread recovered after ~${totalStall}ms of unresponsiveness")
        }
    }

    /** @return false if interrupted (caller should exit the watch loop). */
    private fun sleepQuietly(ms: Long): Boolean =
        try {
            Thread.sleep(ms)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun reportStall(
        appContext: Context,
        sentryEnabled: Boolean,
        mainThread: Thread,
        stalledForMs: Long,
    ) {
        try {
            // Capture the main thread's stack FIRST — it is the most important and most volatile
            // piece of evidence. Everything below is bookkeeping around it.
            val mainStack = mainThread.stackTrace
            val report = buildReport(mainThread, mainStack, stalledForMs)

            // 1) logcat, chunked so nothing is truncated.
            //    Retrieve with:  adb logcat -s MainThreadWatchdog
            logChunked(report)

            // 2) Rotating on-disk copy — survives logcat rotation and process death.
            try {
                writeReportFile(appContext, report)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to write stall report file", t)
            }

            // 3) Sentry event (skipped entirely for telemetry-free builds, e.g. F-Droid). The
            //    exception's stack trace IS the main thread's stack, so the Sentry issue title and
            //    grouping point straight at the blocking frame. options.isAttachThreads=true (set
            //    in App.initSentry) additionally attaches all other threads' stacks server-side.
            //    Sentry persists the envelope to its disk cache immediately, so this survives the
            //    user force-killing the frozen app and uploads on the next launch.
            if (sentryEnabled) {
                try {
                    Sentry.captureException(
                        MainThreadStallException(
                            "Main thread stalled for ≥ ${stalledForMs}ms (no looper progress)",
                            mainStack,
                        )
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to capture stall in Sentry", t)
                }
            }
        } catch (t: Throwable) {
            // Belt and braces: the watchdog must never be able to harm the app it watches.
            Log.e(TAG, "Stall reporting failed", t)
        }
    }

    private fun buildReport(
        mainThread: Thread,
        mainStack: Array<StackTraceElement>,
        stalledForMs: Long,
    ): String {
        val sb = StringBuilder(8 * 1024)
        val wallClock =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        sb.append("════════ MAIN THREAD STALL ════════\n")
        sb.append("time: ").append(wallClock).append('\n')
        sb.append("stalledFor: ≥ ").append(stalledForMs).append(" ms (still ongoing)\n")
        sb.append("app: ")
            .append(BuildConfig.APPLICATION_ID)
            .append(' ')
            .append(BuildConfig.VERSION_NAME)
            .append(" (")
            .append(BuildConfig.VERSION_CODE)
            .append(") ")
            .append(BuildConfig.FLAVOR)
            .append(if (BuildConfig.DEBUG) "-debug" else "")
            .append('\n')

        sb.append("\n--- main thread (the stalled one) ---\n")
        appendThread(sb, mainThread, mainStack)

        sb.append("\n--- all other threads ---\n")
        // Sorted for stable, diff-able output; main excluded (already printed above).
        Thread.getAllStackTraces()
            .toList()
            .filter { (thread, _) -> thread !== mainThread }
            .sortedBy { (thread, _) -> thread.name }
            .forEach { (thread, stack) ->
                appendThread(sb, thread, stack)
                sb.append('\n')
            }
        sb.append("════════ END STALL REPORT ════════")
        return sb.toString()
    }

    private fun appendThread(sb: StringBuilder, thread: Thread, stack: Array<StackTraceElement>) {
        sb.append('"')
            .append(thread.name)
            .append("\" id=")
            .append(thread.id)
            .append(" state=")
            .append(thread.state)
            .append(if (thread.isDaemon) " daemon" else "")
            .append('\n')
        if (stack.isEmpty()) {
            sb.append("    <no java stack>\n")
        } else {
            for (element in stack) sb.append("    at ").append(element).append('\n')
        }
    }

    private fun logChunked(report: String) {
        var index = 0
        var part = 1
        val parts = (report.length + LOGCAT_CHUNK_CHARS - 1) / LOGCAT_CHUNK_CHARS
        while (index < report.length) {
            val end = minOf(index + LOGCAT_CHUNK_CHARS, report.length)
            Log.e(TAG, "[stall ${part}/${parts}]\n" + report.substring(index, end))
            index = end
            part++
        }
    }

    /**
     * Writes the report to `<externalFiles>/watchdog/stall_<timestamp>.txt` (falling back to the
     * app's internal files dir if external storage is unavailable) and prunes the directory down
     * to the newest [MAX_REPORT_FILES] reports.
     */
    private fun writeReportFile(appContext: Context, report: String) {
        val dir =
            appContext.getExternalFilesDir("watchdog")
                ?: File(appContext.filesDir, "watchdog").apply { mkdirs() }
        if (!dir.exists()) dir.mkdirs()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(dir, "stall_$stamp.txt")
        file.writeText(report)
        Log.e(TAG, "Stall report written to ${file.absolutePath}")

        // Rotation: keep the newest MAX_REPORT_FILES, delete the rest. Timestamped names sort
        // lexicographically in chronological order, so a name sort is a time sort.
        dir.listFiles { f -> f.isFile && f.name.startsWith("stall_") }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_REPORT_FILES)
            ?.forEach { stale ->
                if (!stale.delete()) Log.w(TAG, "Could not prune old report ${stale.name}")
            }
    }
}

/**
 * Marker exception for Sentry whose stack trace is replaced with the **main thread's** stack at
 * the moment of the stall. The watchdog thread's own (irrelevant) stack is discarded, so the Sentry
 * issue is titled and grouped by the frame that actually blocked the UI.
 */
private class MainThreadStallException(
    message: String,
    mainThreadStack: Array<StackTraceElement>,
) : RuntimeException(message) {
    init {
        stackTrace = mainThreadStack
    }
}

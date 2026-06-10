package com.junkfood.seal

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.junkfood.seal.ui.common.LocalDarkTheme
import com.junkfood.seal.ui.common.SettingsProvider
import com.junkfood.seal.ui.page.AppEntry
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.matchUrlFromSharedText
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.KoinContext

class MainActivity : AppCompatActivity() {
    private val dialogViewModel: DownloadDialogViewModel by viewModel()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate (restored=${savedInstanceState != null})")

        // ── Two lines used to sit here; both are deliberately GONE. Do not reintroduce them. ──
        //
        // 1. `runBlocking { setLanguage(…) }` behind an `if (SDK_INT < 33)` guard:
        //    minSdk is now 34 (≥ 33), so the branch could never execute — pure dead code (the AndroidX
        //    per-app-locales service in the manifest handles locale persistence on 33+).
        //
        // 2. `context = this.baseContext` — the single biggest memory hazard found in this round.
        //    It overwrote the GLOBAL App.context (used by Room/DatabaseUtil, FileUtil, service
        //    binding, toasts, …) with this Activity's base ContextImpl. A ContextImpl holds its
        //    Activity via mOuterContext, so every recreation of MainActivity (theme change, the
        //    system reclaiming it during app-switching, …) leaked the previous Activity instance
        //    together with its entire Compose composition. Under the memory pressure of heavy
        //    app-switching, those accumulated leaks mean GC churn and progressively longer UI
        //    pauses — the exact freeze under investigation. App.context is assigned exactly once,
        //    in App.onCreate(), to the application context, and that is the only assignment that
        //    may ever exist.
        enableEdgeToEdge()

        setContent {
            KoinContext {
                val windowSizeClass = calculateWindowSizeClass(this)
                SettingsProvider(windowWidthSizeClass = windowSizeClass.widthSizeClass) {
                    SealTheme(
                        darkTheme = LocalDarkTheme.current.isDarkTheme(),
                        isHighContrastModeEnabled = LocalDarkTheme.current.isHighContrastModeEnabled,
                    ) {
                        AppEntry(dialogViewModel = dialogViewModel)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.getSharedURL()
        Log.d(TAG, "onNewIntent: action=${intent.action} urlExtracted=${url != null}")
        if (url != null) {
            dialogViewModel.postAction(DownloadDialogViewModel.Action.ShowSheet(listOf(url)))
        }
    }

    private fun Intent.getSharedURL(): String? {
        val intent = this

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.dataString
            }

            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedContent ->
                    intent.removeExtra(Intent.EXTRA_TEXT)
                    matchUrlFromSharedText(sharedContent).also { matchedUrl ->
                        if (sharedUrlCached != matchedUrl) {
                            sharedUrlCached = matchedUrl
                        }
                    }
                }
            }

            else -> {
                null
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private var sharedUrlCached = ""
    }
}

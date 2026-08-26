package org.sisam.langtutor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.sisam.langtutor.ui.common.SplashScreen
import androidx.core.content.IntentCompat
import org.sisam.langtutor.navigation.AppNav
import org.sisam.langtutor.ui.theme.LangTutorTheme

// AppCompatActivity (not ComponentActivity) for AppCompatDelegate per-app
// locales on minSdk 31.
class MainActivity : AppCompatActivity() {

    private companion object {
        /** Fixed splash beat — a floor, not a gate: loading continues after. */
        const val SPLASH_MS = 4_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = AppContainer.get(applicationContext)
        // Start warming every engine NOW, before any frame renders: the splash
        // below buys the loaders a ~4s head start, and by the time a child
        // reaches a button the cheap engines are up and the LLM is well into
        // its load. Idempotent — a second onCreate() joins the running pass.
        container.preloadAll()
        handleShareToImport(container)
        setContent {
            val appContainer = remember { container }
            LangTutorTheme {
                var splashDone by rememberSaveable { mutableStateOf(false) }
                if (splashDone) {
                    AppNav(appContainer)
                } else {
                    SplashScreen(appContainer)
                    LaunchedEffect(Unit) {
                        delay(SPLASH_MS)
                        splashDone = true
                    }
                }
            }
        }
    }

    /** Share-to-import: a file shared to the app is copied into files/models
     *  (verified) — progress is visible in Parent Zone → Packs. */
    private fun handleShareToImport(container: AppContainer) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        container.modelImporter.import(uri)
        Toast.makeText(this, getString(R.string.import_receiving), Toast.LENGTH_LONG).show()
    }
}

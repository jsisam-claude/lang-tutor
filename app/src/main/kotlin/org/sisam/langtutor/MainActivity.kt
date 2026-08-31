package org.sisam.langtutor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
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
        // Explicit, not implied: targetSdk 35+ already forces edge-to-edge,
        // but opting in makes inset dispatch deterministic across OEM skins —
        // and the root safeDrawingPadding below relies on those insets.
        enableEdgeToEdge()
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
                // Active on every screen: the 60 Hz decode experiment watches
                // generation globally, not per room.
                org.sisam.langtutor.ui.common.RefreshRateCapEffect(appContainer)
                // targetSdk 35+ is edge-to-edge whether we ask or not, and no
                // screen manages its own insets — so clear the system bars,
                // cutout and keyboard ONCE here, or every bottom-anchored
                // button sits under the navigation ribbon (found the hard way
                // in the picture room).
                Box(androidx.compose.ui.Modifier.safeDrawingPadding()) {
                var splashDone by rememberSaveable { mutableStateOf(false) }
                // Fresh installs answer the one question first: what Level?
                // Checked once per process via snapshot — the flag flips only
                // forward, so the screen can never reappear mid-session.
                var onboarded by rememberSaveable {
                    mutableStateOf(
                        !org.sisam.langtutor.ui.onboarding.isFreshProfile(container.profile.snapshot()),
                    )
                }
                if (splashDone && !onboarded) {
                    org.sisam.langtutor.ui.onboarding.LevelOnboarding(appContainer) { onboarded = true }
                } else if (splashDone) {
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
    }

    override fun onStart() {
        super.onStart()
        // Back within the grace period: everything that stayed warm stays.
        AppContainer.get(applicationContext).cancelBackgroundRelease()
    }

    override fun onStop() {
        super.onStop()
        // Backgrounded or screen locked: a phone in a pocket must not keep
        // talking or keep the mic hot. Rotation also passes through onStop,
        // and silencing Tuki mid-sentence for a rotate would be a regression
        // — hence the guard. Quiesce is instant (stage one); the multi-GB
        // engines get a grace period before they are given back (stage two),
        // so checking a notification does not cost a model reload.
        if (!isChangingConfigurations) {
            val container = AppContainer.get(applicationContext)
            container.quiesce()
            container.scheduleBackgroundRelease()
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

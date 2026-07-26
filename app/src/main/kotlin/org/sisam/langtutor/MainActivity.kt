package org.sisam.langtutor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.remember
import androidx.core.content.IntentCompat
import org.sisam.langtutor.navigation.AppNav
import org.sisam.langtutor.ui.theme.LangTutorTheme

// AppCompatActivity (not ComponentActivity) for AppCompatDelegate per-app
// locales on minSdk 31.
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = AppContainer.get(applicationContext)
        handleShareToImport(container)
        setContent {
            val appContainer = remember { container }
            LangTutorTheme {
                AppNav(appContainer)
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

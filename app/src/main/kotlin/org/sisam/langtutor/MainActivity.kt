package org.sisam.langtutor

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.remember
import org.sisam.langtutor.navigation.AppNav
import org.sisam.langtutor.ui.theme.LangTutorTheme

// AppCompatActivity (not ComponentActivity) for AppCompatDelegate per-app
// locales on minSdk 31.
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = AppContainer(applicationContext)
        setContent {
            val appContainer = remember { container }
            LangTutorTheme {
                AppNav(appContainer)
            }
        }
    }
}

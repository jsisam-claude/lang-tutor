package org.sisam.langtutor.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * LTR island for English learning content inside the RTL Hebrew chrome — the
 * single biggest BiDi gotcha in this app. Wrap any composable that renders
 * English sentences, readers, or chat bubbles.
 */
@Composable
fun EnglishContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Ltr,
        content = content,
    )
}

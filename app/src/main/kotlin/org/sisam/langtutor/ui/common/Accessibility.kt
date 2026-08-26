package org.sisam.langtutor.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far the user has pushed system font scale and display size.
 *
 * Both settings inflate layouts, and Tuki's audience — parents reading over a
 * child's shoulder, adult learners — is exactly the population that raises
 * them. Rather than sprinkle magic numbers, screens ask these questions and
 * spend the extra room in one direction: ornament and padding shrink so the
 * text and the controls keep theirs.
 *
 * The rule the whole app follows: **decoration yields, controls never do.**
 * A mascot may halve; a button must stay above the 48 dp touch minimum.
 */
object A11y {

    /** System font scale (1.0 = default; Android allows up to 2.0). */
    val fontScale: Float
        @Composable @ReadOnlyComposable get() = LocalDensity.current.fontScale

    /** True once text is meaningfully larger than default. */
    val largeText: Boolean
        @Composable @ReadOnlyComposable get() = fontScale >= 1.15f

    /** True when text is very large — drop decoration entirely here. */
    val hugeText: Boolean
        @Composable @ReadOnlyComposable get() = fontScale >= 1.5f

    /** Logical screen height in dp. Shrinks as display size grows, so it is
     *  the honest proxy for "how much fits" under the display-size setting. */
    val screenHeightDp: Int
        @Composable @ReadOnlyComposable get() = LocalConfiguration.current.screenHeightDp

    /** Logical screen width in dp — also shrinks with the display-size setting. */
    val screenWidthDp: Int
        @Composable @ReadOnlyComposable get() = LocalConfiguration.current.screenWidthDp

    /** True on a short viewport — either a small device or a large display
     *  size setting. Decorative art should shrink or disappear. */
    val shortViewport: Boolean
        @Composable @ReadOnlyComposable get() = screenHeightDp < 640

    /** True when the layout is under real pressure from either dial. Screens
     *  use it to drop a whole ornamental element rather than shrink it into
     *  an unreadable smudge. */
    val cramped: Boolean
        @Composable @ReadOnlyComposable get() = hugeText || (largeText && shortViewport)

    /**
     * Size for a decorative illustration, scaled DOWN as text and display size
     * grow. Ornament yields to content: at 1.5x font on a short screen the
     * mascot is less than half its comfortable size, leaving the room for the
     * words that carry meaning.
     */
    @Composable
    @ReadOnlyComposable
    fun decorativeSize(comfortable: Int, minimum: Int = 40): Int {
        var size = comfortable
        if (largeText) size = (size * 0.8f).toInt()
        if (hugeText) size = (size * 0.7f).toInt()
        if (shortViewport) size = (size * 0.8f).toInt()
        return size.coerceAtLeast(minimum)
    }

    /** [decorativeSize] as a [Dp], for the common call site. */
    @Composable
    @ReadOnlyComposable
    fun decorativeDp(comfortable: Int, minimum: Int = 40): Dp =
        decorativeSize(comfortable, minimum).dp

    /**
     * Size for something the user actually TAPS. Shrinks far more gently than
     * decoration and is floored well above the 48 dp accessibility minimum,
     * because a person who enlarged their font is not a person who wants a
     * smaller target — the space has to come out of the ornament instead.
     */
    @Composable
    @ReadOnlyComposable
    fun tapTargetDp(comfortable: Int, minimum: Int = 64): Dp {
        var size = comfortable
        if (hugeText) size = (size * 0.85f).toInt()
        if (shortViewport) size = (size * 0.85f).toInt()
        return size.coerceAtLeast(minimum).dp
    }

    /** Screen edge padding. Large text needs the horizontal room more than it
     *  needs the margin, and wide gutters are what push buttons off-screen. */
    val gutter: Dp
        @Composable @ReadOnlyComposable get() = when {
            hugeText -> 12.dp
            largeText -> 16.dp
            else -> 24.dp
        }

    /** Vertical gap between the major blocks of a screen. */
    val sectionGap: Dp
        @Composable @ReadOnlyComposable get() = when {
            hugeText -> 8.dp
            largeText -> 12.dp
            else -> 16.dp
        }

    /**
     * Widest a chat bubble may be. A fixed dp cap (the old 300.dp) is wrong in
     * both directions: it wastes a tablet and overflows a phone whose display
     * size setting shrank the viewport to ~320 dp wide.
     */
    val bubbleMaxWidth: Dp
        @Composable @ReadOnlyComposable get() = (screenWidthDp * 0.78f).dp
}

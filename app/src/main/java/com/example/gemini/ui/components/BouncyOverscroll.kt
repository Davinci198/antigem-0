package com.example.gemini.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier

/**
 * A plain vertical scroll, under the name the screens ported from upstream use.
 *
 * Upstream wraps this in a custom overscroll bounce; this fork has no overscroll factory for it, so the
 * name stays and the behaviour is the platform's own. Keeps the ported screens unchanged.
 */
fun Modifier.bouncyVerticalScroll(state: ScrollState): Modifier = verticalScroll(state)

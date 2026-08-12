package app.gamenative.ui.component

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.core.view.SoftwareKeyboardControllerCompat
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Gamepad-first search field shared by the QuickMenu panel and the shader browser
 * (extracted from the effects tab, spec 2026-08-10-search-field-ime-explicit-design):
 * the soft keyboard opens ONLY on explicit intent — X (A / DPAD_CENTER / ENTER) opens it,
 * B closes it — never because gamepad navigation (stick/hat, walk-down) landed on the field.
 */
@Composable
fun GamepadSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    focusIndex: Int,
    onFocusIndexChanged: (Int) -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val keyboard = remember { SoftwareKeyboardControllerCompat(view) }
    val density = LocalDensity.current
    // Recomposes whenever the IME insets change: WindowInsets.ime is backed by snapshot state.
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var searchFieldFocused by remember { mutableStateOf(false) }
    var searchImeWanted by remember { mutableStateOf(false) }
    val imeScope = rememberCoroutineScope()
    var suppressImeJob by remember { mutableStateOf<Job?>(null) }

    fun startImeSuppression() {
        suppressImeJob?.cancel()
        suppressImeJob = imeScope.launch {
            keyboard.hide()
            while (searchFieldFocused && !searchImeWanted) {
                delay(120L)
                if (!searchFieldFocused || searchImeWanted) break
                keyboard.hide()
            }
        }
    }

    fun stopImeSuppression() {
        suppressImeJob?.cancel()
        suppressImeJob = null
    }

    LaunchedEffect(searchFieldFocused) {
        if (searchFieldFocused) {
            Timber.d("QMFocus: row %d focused (search field)", focusIndex)
            onFocusIndexChanged(focusIndex)
            if (!searchImeWanted && SearchFieldImeLogic.arrivedViaGamepad(
                    now = SystemClock.uptimeMillis(),
                    lastMoveAt = GamepadNavigationClock.lastMoveAt,
                    windowMs = 400L,
                )
            ) {
                Timber.d("QMFocus: search field focused via gamepad - suppressing IME")
                startImeSuppression()
            }
        } else {
            stopImeSuppression()
            searchImeWanted = false
        }
    }
    LaunchedEffect(imeVisible) {
        if (!imeVisible) searchImeWanted = false
    }

    // X opens the IME, B closes it while it is up (consumed — the surface must not close;
    // B with the IME closed propagates as the usual hierarchical back).
    val searchImeKeyModifier = Modifier.onPreviewKeyEvent { keyEvent ->
        when (
            SearchFieldImeLogic.onKey(
                keyCode = keyEvent.nativeKeyEvent.keyCode,
                action = keyEvent.nativeKeyEvent.action,
                repeatCount = keyEvent.nativeKeyEvent.repeatCount,
                isFocused = searchFieldFocused,
                isImeVisible = imeVisible,
            )
        ) {
            SearchFieldImeLogic.KeyAction.OpenIme -> {
                searchImeWanted = true
                stopImeSuppression()
                keyboard.show()
                true
            }
            SearchFieldImeLogic.KeyAction.CloseIme -> {
                searchImeWanted = false
                keyboard.hide()
                true
            }
            SearchFieldImeLogic.KeyAction.Propagate -> false
        }
    }

    val accent = PluviaTheme.colors.accentPurple
    Box(
        modifier = modifier
            // Touch on the field is explicit intent: stop the suppression so a tap opens
            // the keyboard (observed without consuming — the field's own handling continues).
            .pointerInput(searchFieldFocused) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (searchFieldFocused) {
                        searchImeWanted = true
                        stopImeSuppression()
                    }
                }
            }
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (searchFieldFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.16f),
                            accent.copy(alpha = 0.08f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.Transparent),
                    )
                },
            ),
    ) {
        NoExtractOutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = searchImeKeyModifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .fillMaxWidth()
                .onFocusChanged { searchFieldFocused = it.hasFocus },
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.shader_clear_search))
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent),
        )
    }
}

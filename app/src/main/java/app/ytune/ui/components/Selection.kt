package app.ytune.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.ytune.ui.theme.P
import app.ytune.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * Multi-select state for a list. Selection mode is on while anything is selected: long-press a
 * row to start, tap rows to add / remove, and deselecting the last one ends it.
 */
@Stable
class Selection<K> {
    var keys: Set<K> by mutableStateOf(emptySet())
        private set

    val active: Boolean get() = keys.isNotEmpty()
    val size: Int get() = keys.size

    operator fun contains(key: K): Boolean = key in keys

    fun toggle(key: K) {
        keys = if (key in keys) keys - key else keys + key
    }

    /** Select everything, or nothing if everything already is. */
    fun toggleAll(all: Collection<K>) {
        keys = if (all.isNotEmpty() && keys.containsAll(all)) emptySet() else all.toSet()
    }

    /** Drops keys that are no longer in the list (e.g. after items were removed). */
    fun retain(valid: Collection<K>) {
        val set = valid.toSet()
        if (!set.containsAll(keys)) keys = keys.filterTo(LinkedHashSet()) { it in set }
    }

    fun clear() {
        keys = emptySet()
    }

    /** Row state for [TrackRow] / [PlaylistRow]: null outside selection mode. */
    fun rowState(key: K): Boolean? = if (active) key in keys else null
}

@Composable
fun <K> rememberSelection(vararg inputs: Any?): Selection<K> {
    val selection = remember(*inputs) { Selection<K>() }
    BackHandler(enabled = selection.active) { selection.clear() }
    return selection
}

/**
 * Floating bar shown while items are selected: count, select all, add to playlist and the
 * shared "⋯" menu with everything else (play, queue, download, delete, …).
 */
@Composable
fun SelectionBar(
    selection: Selection<*>,
    total: Int,
    onSelectAll: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = selection.active,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .height(56.dp)
                .clip(CircleShape)
                .background(P.inverse)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(Ic.Close, { selection.clear() }, tint = P.onInverse, contentDescription = "Cancel selection")
            Text(
                "${selection.size} SELECTED",
                style = Type.labelBold,
                color = P.onInverse,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                maxLines = 1,
            )
            val all = selection.size >= total && total > 0
            Box(
                Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onSelectAll)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(if (all) "NONE" else "ALL", style = Type.labelBold, color = P.onInverse)
            }
            IconBtn(Ic.PlaylistAdd, onAddToPlaylist, tint = P.onInverse, contentDescription = "Add to playlist")
            IconBtn(Ic.More, onMore, tint = P.onInverse, contentDescription = "More actions")
        }
    }
}

/** The round check shown at the end of a row in selection mode. */
@Composable
fun SelectMark(selected: Boolean, modifier: Modifier = Modifier) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier
                .size(22.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(P.accent)
                    else Modifier.border(1.5.dp, P.textFaint, CircleShape)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Ic.Check, contentDescription = "Selected", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

/** Text field + confirm button for a sheet (new playlist, rename). */
@Composable
fun NameEntry(initial: String, placeholder: String, confirm: String, onConfirm: (String) -> Unit) {
    val close = LocalSheetClose.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val submit = {
        val name = value.text.trim()
        if (name.isNotEmpty()) {
            keyboard?.hide()
            close()
            onConfirm(name)
        }
    }
    LaunchedEffect(Unit) {
        delay(250) // let the sheet slide in first
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(50.dp)
                .clip(CircleShape)
                .background(P.surfaceHigh)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.text.isEmpty()) Text(placeholder, style = Type.input, color = P.textFaint, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = Type.input.copy(color = P.text),
                cursorBrush = SolidColor(P.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Spacer(Modifier.width(10.dp))
        PillButton(confirm, { submit() }, style = PillStyle.Accent, enabled = value.text.isNotBlank())
    }
}

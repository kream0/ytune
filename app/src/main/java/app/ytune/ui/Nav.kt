package app.ytune.ui

import app.ytune.data.PlaylistRef
import app.ytune.ui.components.SheetSpec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Navigation requested from outside composition (e.g. once a lookup finishes); AppRoot acts on it. */
sealed interface NavEvent {
    data class OpenPlaylist(val ref: PlaylistRef) : NavEvent
    data class ShowSheet(val spec: SheetSpec) : NavEvent
}

object Nav {
    private val _events = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<NavEvent> = _events.asSharedFlow()

    fun openPlaylist(ref: PlaylistRef) {
        _events.tryEmit(NavEvent.OpenPlaylist(ref))
    }

    fun showSheet(spec: SheetSpec) {
        _events.tryEmit(NavEvent.ShowSheet(spec))
    }
}

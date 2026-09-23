package app.ytune.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.ytune.Graph
import app.ytune.data.PlaylistRef
import app.ytune.yt.YouTube
import app.ytune.yt.YtLink

enum class Tab(val label: String) { SEARCH("SEARCH"), LIBRARY("LIBRARY"), SETTINGS("SETTINGS") }

enum class LibraryPage(val label: String) { SONGS("SONGS"), PLAYLISTS("PLAYLISTS"), DOWNLOADS("QUEUE") }

/** App-level navigation state (survives configuration changes). */
class AppViewModel : ViewModel() {
    var tab by mutableStateOf(Tab.SEARCH)
    var libraryPage by mutableStateOf(LibraryPage.SONGS)
    var playlist by mutableStateOf<PlaylistRef?>(null)
    var nowPlaying by mutableStateOf(false)

    fun selectTab(t: Tab) {
        playlist = null
        tab = t
    }

    fun openPlaylist(ref: PlaylistRef) {
        nowPlaying = false
        playlist = ref
    }

    fun openDownloads() {
        nowPlaying = false
        playlist = null
        tab = Tab.LIBRARY
        libraryPage = LibraryPage.DOWNLOADS
    }

    fun handleSharedText(text: String) {
        when (val link = YouTube.parseLink(text)) {
            is YtLink.Playlist -> openPlaylist(PlaylistRef(url = link.url, title = "Shared playlist"))
            is YtLink.Video -> Actions.playVideo(link.id)
            null -> Graph.toast("No YouTube link found in what you shared")
        }
    }
}

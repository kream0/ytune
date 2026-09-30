package app.ytune.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.ytune.Graph
import app.ytune.data.PlaylistRef
import app.ytune.tr
import app.ytune.yt.YouTube
import app.ytune.yt.YtLink

enum class Tab(private val en: String, private val fr: String) {
    SEARCH("SEARCH", "RECHERCHE"),
    LIBRARY("LIBRARY", "BIBLIOTHÈQUE"),
    SETTINGS("SETTINGS", "RÉGLAGES");

    val label: String get() = tr(en, fr)
}

enum class LibraryPage(private val en: String, private val fr: String) {
    SONGS("SONGS", "TITRES"),
    PLAYLISTS("PLAYLISTS", "PLAYLISTS"),
    DOWNLOADS("QUEUE", "FILE");

    val label: String get() = tr(en, fr)
}

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
            is YtLink.Playlist -> openPlaylist(PlaylistRef(url = link.url, title = tr("Shared playlist", "Playlist partagée")))
            is YtLink.Video -> Actions.playVideo(link.id)
            null -> Graph.toast(tr("No YouTube link found in what you shared", "Aucun lien YouTube dans ce qui a été partagé"))
        }
    }
}

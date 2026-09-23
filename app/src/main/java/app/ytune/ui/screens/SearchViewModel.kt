package app.ytune.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ytune.Graph
import app.ytune.data.PlaylistRef
import app.ytune.yt.ResultItem
import app.ytune.yt.SearchFilter
import app.ytune.yt.SearchPager
import app.ytune.yt.SongResult
import app.ytune.yt.YouTube
import app.ytune.yt.YtLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchUi(
    val query: String? = null,
    val results: List<ResultItem> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: String? = null,
) {
    val songs get() = results.filterIsInstance<SongResult>().map { it.track }
}

class SearchViewModel : ViewModel() {

    var query by mutableStateOf("")
        private set
    var filter by mutableStateOf(
        SearchFilter.entries.firstOrNull { it.name == Graph.settings.current.searchFilter } ?: SearchFilter.SONGS
    )
        private set

    private val _ui = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _openPlaylist = Channel<PlaylistRef>(Channel.BUFFERED)
    val openPlaylist = _openPlaylist.receiveAsFlow()

    private var pager: SearchPager? = null
    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    fun onQueryChange(text: String) {
        query = text
        suggestJob?.cancel()
        if (text.isBlank() || YouTube.parseLink(text) != null) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(220)
            _suggestions.value = withContext(Dispatchers.IO) {
                runCatching { YouTube.suggestions(text) }.getOrDefault(emptyList())
            }
        }
    }

    fun selectFilter(f: SearchFilter) {
        if (f == filter) return
        filter = f
        Graph.settings.update { it.copy(searchFilter = f.name) }
        _ui.value.query?.let { search(it) }
    }

    fun search(text: String = query) {
        val q = text.trim()
        if (q.isEmpty()) return
        query = q
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        searchJob?.cancel()
        pager = null

        when (val link = YouTube.parseLink(q)) {
            is YtLink.Playlist -> {
                _openPlaylist.trySend(PlaylistRef(url = link.url, title = "Playlist"))
                return
            }
            is YtLink.Video -> {
                _ui.value = SearchUi(query = q, loading = true)
                searchJob = viewModelScope.launch {
                    _ui.value = try {
                        val track = withContext(Dispatchers.IO) {
                            YouTube.fetchTrack(link.id, Graph.settings.current.quality)
                        }
                        SearchUi(query = q, results = listOf(SongResult(track)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SearchUi(query = q, error = e.message ?: "Couldn't open that link")
                    }
                }
                return
            }
            null -> Unit
        }

        _ui.value = SearchUi(query = q, loading = true)
        val f = filter
        searchJob = viewModelScope.launch {
            _ui.value = try {
                val p = withContext(Dispatchers.IO) { YouTube.search(q, f) }
                val items = withContext(Dispatchers.IO) { p.loadNext() }
                pager = p
                SearchUi(query = q, results = items.distinctBy { it.key }, canLoadMore = p.hasMore)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SearchUi(query = q, error = e.message ?: "Search failed")
            }
        }
    }

    fun loadMore() {
        val p = pager ?: return
        val s = _ui.value
        if (s.loading || s.loadingMore || !s.canLoadMore) return
        _ui.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            val more = runCatching { withContext(Dispatchers.IO) { p.loadNext() } }
            if (pager !== p) return@launch // a new search started meanwhile
            val cur = _ui.value
            _ui.value = more.fold(
                onSuccess = { items ->
                    cur.copy(
                        results = (cur.results + items).distinctBy { it.key },
                        loadingMore = false,
                        canLoadMore = p.hasMore && items.isNotEmpty(),
                    )
                },
                onFailure = { cur.copy(loadingMore = false, canLoadMore = false) },
            )
        }
    }
}

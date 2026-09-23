package app.ytune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ytune.Graph
import app.ytune.data.PlaylistRef
import app.ytune.ui.Actions
import app.ytune.ui.components.DotGrid
import app.ytune.ui.components.DotLoader
import app.ytune.ui.components.EmptyState
import app.ytune.ui.components.Ic
import app.ytune.ui.components.LocalDl
import app.ytune.ui.components.LocalSheets
import app.ytune.ui.components.ModeChip
import app.ytune.ui.components.ModeOptions
import app.ytune.ui.components.PillButton
import app.ytune.ui.components.PillStyle
import app.ytune.ui.components.PlaylistRow
import app.ytune.ui.components.SelectionBar
import app.ytune.ui.components.rememberSelection
import app.ytune.ui.components.ScreenHeader
import app.ytune.ui.components.SheetSpec
import app.ytune.ui.components.TrackRow
import app.ytune.ui.theme.P
import app.ytune.ui.theme.Type
import app.ytune.yt.PlaylistResult
import app.ytune.yt.SearchFilter
import app.ytune.yt.SongResult

@Composable
fun SearchScreen(onOpenPlaylist: (PlaylistRef) -> Unit, vm: SearchViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val player by Graph.player.state.collectAsStateWithLifecycle()
    val sheets = LocalSheets.current
    val dl = LocalDl.current
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    val selection = rememberSelection<String>(ui.query, vm.filter)

    LaunchedEffect(Unit) { vm.openPlaylist.collect { onOpenPlaylist(it) } }

    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, ui.results.size) { if (nearEnd) vm.loadMore() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("YTUNE") {
            ModeChip(onOptions = { sheets(SheetSpec(title = "Playback mode", content = { ModeOptions() })) })
        }

        SearchField(
            value = vm.query,
            onValueChange = vm::onQueryChange,
            onSearch = {
                focus.clearFocus()
                vm.search()
            },
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchFilter.entries.forEach { f ->
                FilterChip(f.label, selected = f == vm.filter) { vm.selectFilter(f) }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                suggestions.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    items(suggestions) { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    focus.clearFocus()
                                    vm.search(s)
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Ic.Search, contentDescription = null, tint = P.textFaint, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(s, style = Type.body, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DotLoader() }

                ui.error != null -> EmptyState(
                    title = "NO SIGNAL",
                    body = ui.error ?: "",
                    action = { PillButton("Retry", onClick = { vm.search(ui.query ?: vm.query) }, icon = Ic.Refresh) },
                )

                ui.query == null -> SearchHero()

                ui.results.isEmpty() -> EmptyState("NOTHING", "No results for “${ui.query}”. Try another filter.")

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = if (selection.active) 80.dp else 12.dp),
                ) {
                    val songs = ui.songs
                    if (songs.size > 1) {
                        item(key = "actions") {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PillButton("Play all", { Graph.player.playAll(songs) }, icon = Ic.PlaylistPlay, style = PillStyle.Filled)
                                PillButton("Queue all", {
                                    Graph.player.enqueue(songs)
                                    Graph.toast("Added ${songs.size} tracks to the queue")
                                }, icon = Ic.PlaylistAdd)
                            }
                        }
                    }
                    items(ui.results, key = { it.key }) { item ->
                        when (item) {
                            is SongResult -> TrackRow(
                                track = item.track,
                                badge = dl.badge(item.track.id),
                                isCurrent = player.current?.id == item.track.id,
                                isPlaying = player.isPlaying,
                                selected = selection.rowState(item.track.id),
                                onClick = {
                                    if (selection.active) selection.toggle(item.track.id) else Graph.player.playNow(item.track)
                                },
                                onMore = { sheets(Actions.trackSheet(item.track)) },
                                onLongClick = { selection.toggle(item.track.id) },
                            )
                            is PlaylistResult -> PlaylistRow(
                                ref = item.ref,
                                onClick = { onOpenPlaylist(item.ref) },
                                onAddAll = { Actions.queuePlaylist(item.ref) },
                                onMore = { sheets(Actions.playlistSheet(item.ref) { onOpenPlaylist(item.ref) }) },
                            )
                        }
                    }
                    if (ui.loadingMore) {
                        item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { DotLoader() }
                        }
                    }
                }
            }

            val songs = ui.songs
            val chosen = { songs.filter { it.id in selection } }
            SelectionBar(
                selection = selection,
                total = songs.size,
                onSelectAll = { selection.toggleAll(songs.map { it.id }) },
                onAddToPlaylist = { sheets(Actions.addToPlaylistSheet(chosen()) { selection.clear() }) },
                onMore = { sheets(Actions.selectionSheet(chosen()) { selection.clear() }) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(54.dp)
            .clip(CircleShape)
            .background(P.surfaceHigh)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.Search, contentDescription = null, tint = P.textDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text("Songs, videos, playlists or a link", style = Type.input, color = P.textFaint, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Type.input.copy(color = P.text),
                cursorBrush = SolidColor(P.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Ic.Close, contentDescription = "Clear", tint = P.textDim, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) P.inverse else P.background)
            .border(1.dp, if (selected) P.inverse else P.outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, style = Type.labelBold, color = if (selected) P.onInverse else P.textDim)
    }
}

@Composable
private fun SearchHero() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 180.dp, height = 90.dp)) {
            DotGrid(Modifier.fillMaxSize(), color = P.outline, spacing = 14.dp, radius = 2.4.dp)
        }
        Spacer(Modifier.height(20.dp))
        Text("LISTEN", style = Type.display, color = P.text)
        Spacer(Modifier.height(10.dp))
        Text(
            "Search YouTube, tap a song to play it. Add whole playlists with the + button, " +
                "or paste / share a YouTube link. Switch on STREAM + DL to keep everything offline.",
            style = Type.body,
            color = P.textDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

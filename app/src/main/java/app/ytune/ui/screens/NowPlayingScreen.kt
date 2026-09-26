package app.ytune.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.ytune.Graph
import app.ytune.data.Track
import app.ytune.data.formatBytes
import app.ytune.data.formatMs
import app.ytune.playback.PlayerUiState
import app.ytune.ui.Actions
import app.ytune.ui.components.DlBadge
import app.ytune.ui.components.DotGlyphs
import app.ytune.ui.components.DotIcon
import app.ytune.ui.components.DotProgressBar
import app.ytune.ui.components.DotRing
import app.ytune.ui.components.HalftoneArtwork
import app.ytune.ui.components.Ic
import app.ytune.ui.components.IconBtn
import app.ytune.ui.components.LocalDl
import app.ytune.ui.components.LocalSheets
import app.ytune.ui.components.ModeChip
import app.ytune.ui.components.ModeOptions
import app.ytune.ui.components.NothingSwitch
import app.ytune.ui.components.PillButton
import app.ytune.ui.components.SectionLabel
import app.ytune.ui.components.saveLayer
import app.ytune.ui.components.SheetAction
import app.ytune.ui.components.SelectionBar
import app.ytune.ui.components.rememberSelection
import app.ytune.ui.components.SheetSpec
import app.ytune.ui.components.TrackRow
import app.ytune.ui.theme.P
import app.ytune.ui.theme.Type
import coil.compose.AsyncImage

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val state by Graph.player.state.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val sheets = LocalSheets.current
    var showQueue by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        if (showQueue) showQueue = false else onClose()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(P.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(Ic.ChevronDown, onClose, contentDescription = "Close")
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (showQueue) "QUEUE" else "NOW PLAYING", style = Type.labelBold, color = P.accent)
                if (state.queue.isNotEmpty()) {
                    Text(
                        "${state.currentIndex + 1} / ${state.queue.size}",
                        style = Type.label,
                        color = P.textDim,
                    )
                }
            }
            IconBtn(
                Ic.Queue,
                { showQueue = !showQueue },
                tint = if (showQueue) P.accent else P.text,
                contentDescription = "Queue",
            )
        }

        val track = state.current
        if (track == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("NOTHING PLAYING", style = Type.displaySmall, color = P.textFaint)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (showQueue) {
                    QueueList(state)
                } else {
                    BoxWithConstraints(
                        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val side = if (maxWidth < maxHeight) maxWidth else maxHeight
                        CoverArt(
                            url = Graph.library.artworkFor(track),
                            dots = settings.dotArtwork,
                            playing = state.isPlaying,
                            modifier = Modifier.size(side),
                            onToggle = { Graph.settings.update { it.copy(dotArtwork = !it.dotArtwork) } },
                        )
                    }
                }
            }

            TitleBlock(track)
            Spacer(Modifier.height(10.dp))
            Seekbar(state, track.id)
            Spacer(Modifier.height(6.dp))
            Transport(state)
            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeChip(onOptions = { sheets(SheetSpec(title = "Playback mode", content = { ModeOptions() })) })
                Spacer(Modifier.weight(1f))
                SaveStatusChip(track)
            }
        }
    }
}

@Composable
private fun CoverArt(url: String?, dots: Boolean, playing: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(P.surface)
            .border(1.dp, P.outline, RoundedCornerShape(28.dp))
            .clickable(onClick = onToggle),
    ) {
        Crossfade(targetState = dots, label = "art") { showDots ->
            if (showDots || url == null) {
                HalftoneArtwork(url, Modifier.fillMaxSize(), breathing = playing, background = P.surface)
            } else {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            if (dots) "DOTS" else "PHOTO",
            style = Type.label,
            color = P.textFaint,
            modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleBlock(track: Track) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(
            track.title,
            style = Type.titleLarge,
            color = P.text,
            maxLines = 1,
            modifier = Modifier.basicMarquee(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            track.artist.uppercase(),
            style = Type.label,
            color = P.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Seekbar(state: PlayerUiState, trackId: String) {
    val progress by Graph.player.progress.collectAsStateWithLifecycle()
    val duration = state.durationMs
    val fraction = if (duration > 0) progress.positionMs.toFloat() / duration else 0f
    // Dots ahead of the playhead show how much of the track is saved offline, coloured
    // red → green. Only stream-only tracks fall back to the (grey) playback buffer.
    val saved = LocalDl.current.badge(trackId).saveLayer()
    val secondary = saved?.first ?: if (duration > 0) progress.bufferedMs.toFloat() / duration else 0f
    val secondaryColor = saved?.second ?: P.textFaint
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        DotProgressBar(
            progress = fraction,
            secondary = secondary,
            secondaryColor = secondaryColor,
            onSeek = { f -> if (duration > 0) Graph.player.seekTo((f * duration).toLong()) },
            height = 26.dp,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatMs(progress.positionMs), style = Type.clock, color = P.text)
            Spacer(Modifier.weight(1f))
            Text(formatMs(duration), style = Type.clock, color = P.textDim)
        }
    }
}

@Composable
private fun Transport(state: PlayerUiState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(
            Ic.Shuffle,
            { Graph.player.toggleShuffle() },
            tint = if (state.shuffle) P.accent else P.textDim,
            contentDescription = "Shuffle",
        )
        Box(
            Modifier.size(56.dp).clip(CircleShape).clickable { Graph.player.previous() },
            contentAlignment = Alignment.Center,
        ) {
            DotIcon(DotGlyphs.PREV, P.text, Modifier.size(24.dp))
        }
        Box(
            Modifier
                .size(82.dp)
                .clip(CircleShape)
                .background(P.inverse)
                .clickable { Graph.player.togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            if (state.isBuffering && state.playWhenReady) {
                DotRing(0f, Modifier.size(64.dp), color = P.accent, offColor = P.inverse, spinning = true, dots = 16)
            }
            DotIcon(
                if (state.playWhenReady) DotGlyphs.PAUSE else DotGlyphs.PLAY,
                P.onInverse,
                Modifier.size(28.dp),
            )
        }
        Box(
            Modifier.size(56.dp).clip(CircleShape).clickable { Graph.player.next() },
            contentAlignment = Alignment.Center,
        ) {
            DotIcon(DotGlyphs.NEXT, P.text, Modifier.size(24.dp))
        }
        IconBtn(
            if (state.repeatMode == Player.REPEAT_MODE_ONE) Ic.RepeatOne else Ic.Repeat,
            { Graph.player.cycleRepeat() },
            tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) P.textDim else P.accent,
            contentDescription = "Repeat",
        )
    }
}

private class ChipSpec(val color: Color, val label: String, val labelColor: Color, val onClick: () -> Unit)

/**
 * Offline status of the current track, on the same red → orange → yellow → green scale as
 * the seek bar. Tap to save a streamed track or retry a failed download.
 */
@Composable
private fun SaveStatusChip(track: Track) {
    val badge = LocalDl.current.badge(track.id)
    val spec = when (badge) {
        DlBadge.Done -> ChipSpec(P.saveGreen, "OFFLINE", P.saveGreen) {
            val size = Graph.library.localAudio(track.id)?.sizeBytes ?: 0L
            Graph.toast("Saved on this phone · ${formatBytes(size)} · plays without a connection")
        }
        is DlBadge.Running -> ChipSpec(
            P.saveColor(badge.progress),
            "SAVING ${(badge.progress * 100).toInt()}%",
            P.text,
        ) {}
        DlBadge.Queued -> ChipSpec(P.saveRed, "QUEUED", P.text) {}
        is DlBadge.Waiting -> ChipSpec(
            P.saveRed,
            if (badge.wifiOnly) "WAITING FOR WI-FI" else "WAITING FOR NETWORK",
            P.text,
        ) {}
        is DlBadge.Failed -> ChipSpec(P.saveRed, "FAILED · RETRY", P.saveRed) {
            Graph.downloads.retry(track.id)
            Graph.toast("Retrying: ${badge.error ?: "download"}")
        }
        DlBadge.None -> ChipSpec(P.outline, "STREAMING · SAVE", P.textDim) { Actions.download(listOf(track)) }
    }
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .border(1.dp, spec.color, CircleShape)
            .clickable(onClick = spec.onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (badge) {
            DlBadge.Done -> Box(Modifier.size(8.dp).clip(CircleShape).background(P.saveGreen))
            is DlBadge.Running -> DotRing(badge.progress, Modifier.size(16.dp), color = spec.color)
            DlBadge.Queued, is DlBadge.Waiting ->
                DotRing(0f, Modifier.size(16.dp), spinning = true, color = P.saveRed)
            is DlBadge.Failed -> Text("!", style = Type.labelBold, color = P.saveRed)
            DlBadge.None -> Icon(Ic.Download, contentDescription = null, tint = P.textDim, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(spec.label, style = Type.labelBold, color = spec.labelColor)
    }
}

@Composable
private fun QueueList(state: PlayerUiState) {
    val sheets = LocalSheets.current
    val dl = LocalDl.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.currentIndex - 2).coerceAtLeast(0))
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    // Keyed by position: the same song can be in the queue twice. Any queue change resets it.
    val selection = rememberSelection<Int>(state.queue)
    val firstSuggested = state.suggested.filter { it > state.currentIndex }.minOrNull()
    val chosen = { state.queue.filterIndexed { i, _ -> i in selection } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = if (selection.active) 80.dp else 8.dp),
        ) {
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${state.queue.size} TRACKS", style = Type.label, color = P.textDim, modifier = Modifier.weight(1f))
                    IconBtn(
                        Ic.PlaylistAdd,
                        { sheets(Actions.newPlaylistSheet(state.queue)) },
                        bordered = true,
                        size = 44.dp,
                        contentDescription = "Save queue as playlist",
                    )
                    Spacer(Modifier.width(8.dp))
                    IconBtn(Ic.Download, { Actions.download(state.queue) }, bordered = true, size = 44.dp, contentDescription = "Download all")
                    Spacer(Modifier.width(8.dp))
                    PillButton("Clear", { Graph.player.clearQueue() }, icon = Ic.Delete)
                }
            }
            item(key = "autoplay") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("AUTOPLAY", style = Type.labelBold, color = P.text)
                        Text(
                            if (settings.autoplay) "Suggestions keep playing when the queue ends" else "Stops when the queue ends",
                            style = Type.label,
                            color = P.textDim,
                        )
                    }
                    NothingSwitch(settings.autoplay, { on -> Graph.settings.update { it.copy(autoplay = on) } })
                }
            }
            itemsIndexed(state.queue, key = { i, t -> "$i:${t.id}" }) { index, track ->
                Column {
                    if (index == firstSuggested) {
                        SectionLabel(
                            "Suggested · autoplay",
                            Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
                        )
                    }
                    TrackRow(
                        track = track,
                        badge = dl.badge(track.id),
                        artwork = Graph.library.artworkFor(track),
                        isCurrent = index == state.currentIndex,
                        isPlaying = state.isPlaying,
                        selected = selection.rowState(index),
                        onLongClick = { selection.toggle(index) },
                        onClick = { if (selection.active) selection.toggle(index) else Graph.player.jumpTo(index) },
                        onMore = {
                            sheets(
                                Actions.trackSheet(
                                    track,
                                    extra = buildList {
                                        if (index > 0) add(SheetAction("Move up", Ic.ChevronUp) { Graph.player.move(index, index - 1) })
                                        if (index < state.queue.size - 1) {
                                            add(SheetAction("Move down", Ic.ChevronDown) { Graph.player.move(index, index + 1) })
                                        }
                                        add(SheetAction("Remove from queue", Ic.Close, destructive = true) { Graph.player.removeAt(index) })
                                    },
                                )
                            )
                        },
                        trailing = {
                            IconBtn(Ic.Close, { Graph.player.removeAt(index) }, tint = P.textFaint, size = 36.dp, iconSize = 18.dp)
                        },
                    )
                }
            }
        }

        SelectionBar(
            selection = selection,
            total = state.queue.size,
            onSelectAll = { selection.toggleAll(state.queue.indices.toList()) },
            onAddToPlaylist = { sheets(Actions.addToPlaylistSheet(chosen()) { selection.clear() }) },
            onMore = {
                val indices = selection.keys.toList()
                val done = { selection.clear() }
                sheets(
                    Actions.selectionSheet(
                        chosen(),
                        extra = listOf(
                            SheetAction("Remove from queue", Ic.Close, destructive = true) {
                                Graph.player.removeAll(indices)
                                done()
                            },
                        ),
                        onDone = done,
                    )
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

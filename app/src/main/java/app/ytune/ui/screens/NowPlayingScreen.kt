package app.ytune.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.calvin.reorderable.ReorderableItem
import app.ytune.ui.components.DragHandle
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
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
import app.ytune.tr
import app.ytune.trCount
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
import app.ytune.ui.components.LyricsView
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
            IconBtn(Ic.ChevronDown, onClose, contentDescription = tr("Close", "Fermer"))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (showQueue) tr("QUEUE", "FILE D'ATTENTE") else tr("NOW PLAYING", "EN LECTURE"),
                    style = Type.labelBold,
                    color = P.accent,
                )
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
                contentDescription = tr("Queue", "File d'attente"),
            )
        }

        val track = state.current
        if (track == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(tr("NOTHING PLAYING", "RIEN EN LECTURE"), style = Type.displaySmall, color = P.textFaint)
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
                        val mode = when {
                            settings.lyricsView -> ArtMode.LYRICS
                            settings.dotArtwork -> ArtMode.DOTS
                            else -> ArtMode.PHOTO
                        }
                        // Tap the cover: dots → photo → lyrics → dots.
                        val cycle = {
                            Graph.settings.update { s ->
                                when {
                                    s.lyricsView -> s.copy(lyricsView = false, dotArtwork = true)
                                    s.dotArtwork -> s.copy(dotArtwork = false)
                                    else -> s.copy(lyricsView = true)
                                }
                            }
                        }
                        Crossfade(targetState = mode == ArtMode.LYRICS, label = "lyrics") { lyrics ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (lyrics) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(28.dp))
                                            .background(P.surface)
                                            .border(1.dp, P.outline, RoundedCornerShape(28.dp)),
                                    ) {
                                        LyricsView(track, onTap = cycle, modifier = Modifier.fillMaxSize())
                                        ModeTabs(ArtMode.LYRICS, Modifier.align(Alignment.BottomEnd).padding(14.dp))
                                    }
                                } else {
                                    CoverArt(
                                        url = Graph.library.artworkFor(track),
                                        mode = mode,
                                        playing = state.isPlaying,
                                        modifier = Modifier.size(side),
                                        onToggle = cycle,
                                    )
                                }
                            }
                        }
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
                ModeChip(
                    onOptions = {
                        sheets(SheetSpec(title = tr("Playback mode", "Mode de lecture"), content = { ModeOptions() }))
                    },
                )
                Spacer(Modifier.weight(1f))
                SaveStatusChip(track)
            }
        }
    }
}

private enum class ArtMode { DOTS, PHOTO, LYRICS }

@Composable
private fun CoverArt(url: String?, mode: ArtMode, playing: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(P.surface)
            .border(1.dp, P.outline, RoundedCornerShape(28.dp))
            .clickable(onClick = onToggle),
    ) {
        Crossfade(targetState = mode == ArtMode.DOTS, label = "art") { showDots ->
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
        ModeTabs(mode, Modifier.align(Alignment.BottomEnd).padding(14.dp))
    }
}

/** "DOTS  PHOTO  LYRICS", the current one lit: shows what a tap on the cover cycles through. */
@Composable
private fun ModeTabs(current: ArtMode, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ArtMode.entries.forEach { m ->
            val label = when (m) {
                ArtMode.DOTS -> tr("DOTS", "POINTS")
                ArtMode.PHOTO -> tr("PHOTO", "PHOTO")
                ArtMode.LYRICS -> tr("LYRICS", "PAROLES")
            }
            Text(label, style = Type.label, color = if (m == current) P.text else P.textFaint)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleBlock(track: Track) {
    val sheets = LocalSheets.current
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
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
        // Go to album, add to playlist, download… for the song that's playing.
        IconBtn(
            Ic.More,
            { sheets(Actions.trackSheet(track)) },
            tint = P.textDim,
            contentDescription = tr("More", "Plus d'options"),
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
            contentDescription = tr("Shuffle", "Aléatoire"),
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
            contentDescription = tr("Repeat", "Répéter"),
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
        DlBadge.Done -> ChipSpec(P.saveGreen, tr("OFFLINE", "HORS LIGNE"), P.saveGreen) {
            val size = Graph.library.localAudio(track.id)?.sizeBytes ?: 0L
            Graph.toast(
                tr(
                    "Saved on this phone · ${formatBytes(size)} · plays without a connection",
                    "Enregistré sur ce téléphone · ${formatBytes(size)} · lecture sans connexion",
                )
            )
        }
        is DlBadge.Running -> ChipSpec(
            P.saveColor(badge.progress),
            tr("SAVING ${(badge.progress * 100).toInt()}%", "ENREGISTREMENT ${(badge.progress * 100).toInt()} %"),
            P.text,
        ) {}
        DlBadge.Queued -> ChipSpec(P.saveRed, tr("QUEUED", "EN ATTENTE"), P.text) {}
        is DlBadge.Waiting -> ChipSpec(
            P.saveRed,
            if (badge.wifiOnly) tr("WAITING FOR WI-FI", "ATTENTE DU WI-FI") else tr("WAITING FOR NETWORK", "ATTENTE DU RÉSEAU"),
            P.text,
        ) {}
        is DlBadge.Failed -> ChipSpec(P.saveRed, tr("FAILED · RETRY", "ÉCHEC · RÉESSAYER"), P.saveRed) {
            Graph.downloads.retry(track.id)
            Graph.toast(
                tr(
                    "Retrying: ${badge.error ?: "download"}",
                    "Nouvelle tentative : ${badge.error ?: "téléchargement"}",
                )
            )
        }
        DlBadge.None -> ChipSpec(P.outline, tr("STREAMING · SAVE", "STREAMING · ENREGISTRER"), P.textDim) {
            Actions.download(listOf(track))
        }
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

/** A queue row; the key stays the same while the song moves (nth copy of the same song). */
private data class QueueEntry(val key: String, val track: Track, val suggested: Boolean)

private fun entriesOf(state: PlayerUiState): List<QueueEntry> {
    val seen = HashMap<String, Int>()
    return state.queue.mapIndexed { i, t ->
        val n = (seen[t.id] ?: 0) + 1
        seen[t.id] = n
        QueueEntry("${t.id}#$n", t, i in state.suggested)
    }
}

@Composable
private fun QueueList(state: PlayerUiState) {
    val sheets = LocalSheets.current
    val dl = LocalDl.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.currentIndex - 2).coerceAtLeast(0))
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    // Keyed by position: the same song can be in the queue twice. Any queue change resets it.
    val selection = rememberSelection<Int>(state.queue)
    val chosen = { state.queue.filterIndexed { i, _ -> i in selection } }

    // The player's order, and the order on screen (which moves live while dragging).
    val stateEntries = remember(state.queue, state.suggested) { entriesOf(state) }
    val indexOf = remember(stateEntries) { stateEntries.withIndex().associate { it.value.key to it.index } }
    // The drag library keeps the first callbacks it's given, so they read the queue through this.
    val latest = rememberUpdatedState(stateEntries)
    val entries = remember { mutableStateListOf<QueueEntry>().apply { addAll(stateEntries) } }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragStart by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(stateEntries) {
        if (dragging == null && entries.toList() != stateEntries) {
            entries.clear()
            entries.addAll(stateEntries)
        }
    }
    val currentKey = stateEntries.getOrNull(state.currentIndex)?.key
    val firstSuggestedKey = entries
        .drop(entries.indexOfFirst { it.key == currentKey } + 1)
        .firstOrNull { it.suggested }?.key

    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = entries.indexOfFirst { it.key == from.key }
        val b = entries.indexOfFirst { it.key == to.key }
        if (a >= 0 && b >= 0 && a != b) {
            entries.add(b, entries.removeAt(a))
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    /** Drop: one move in the player; if the queue changed underneath meanwhile, just re-sync. */
    fun commitDrag() {
        val key = dragging ?: return
        dragging = null
        val from = dragStart.indexOf(key)
        val to = entries.indexOfFirst { it.key == key }
        val now = latest.value
        if (now.map { it.key } == dragStart && from >= 0 && to >= 0) {
            if (from != to) Graph.player.move(from, to)
        } else {
            entries.clear()
            entries.addAll(now)
        }
    }

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
                    Text(
                        trCount(state.queue.size, "TRACK", "TRACKS", "TITRE", "TITRES"),
                        style = Type.label,
                        color = P.textDim,
                        modifier = Modifier.weight(1f),
                    )
                    IconBtn(
                        Ic.PlaylistAdd,
                        { sheets(Actions.newPlaylistSheet(state.queue)) },
                        bordered = true,
                        size = 44.dp,
                        contentDescription = tr("Save queue as playlist", "Enregistrer la file en playlist"),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconBtn(
                        Ic.Download,
                        { Actions.download(state.queue) },
                        bordered = true,
                        size = 44.dp,
                        contentDescription = tr("Download all", "Tout télécharger"),
                    )
                    Spacer(Modifier.width(8.dp))
                    PillButton(tr("Clear", "Vider"), { Graph.player.clearQueue() }, icon = Ic.Delete)
                }
            }
            item(key = "autoplay") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("AUTOPLAY", "LECTURE AUTO"), style = Type.labelBold, color = P.text)
                        Text(
                            if (settings.autoplay) {
                                tr(
                                    "Suggestions keep playing when the queue ends",
                                    "Des suggestions prennent le relais à la fin de la file",
                                )
                            } else {
                                tr("Stops when the queue ends", "La lecture s'arrête à la fin de la file")
                            },
                            style = Type.label,
                            color = P.textDim,
                        )
                    }
                    NothingSwitch(settings.autoplay, { on -> Graph.settings.update { it.copy(autoplay = on) } })
                }
            }
            entries.forEach { entry ->
                if (entry.key == firstSuggestedKey) {
                    item(key = "suggested-header") {
                        SectionLabel(
                            tr("Suggested · autoplay", "Suggestions · lecture auto"),
                            Modifier.animateItem().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
                        )
                    }
                }
                item(key = entry.key) {
                    ReorderableItem(reorder, key = entry.key) { isDragging ->
                        val index = indexOf[entry.key] ?: return@ReorderableItem
                        val track = entry.track
                        val lift by animateFloatAsState(if (isDragging) 1f else 0f, label = "lift")
                        val handle = Modifier.draggableHandle(
                            enabled = !selection.active,
                            onDragStarted = {
                                dragStart = latest.value.map { it.key }
                                dragging = entry.key
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragStopped = { commitDrag() },
                        )
                        Box(
                            Modifier
                                .graphicsLayer {
                                    scaleX = 1f + 0.03f * lift
                                    scaleY = 1f + 0.03f * lift
                                    shadowElevation = 14.dp.toPx() * lift
                                    shape = RoundedCornerShape(18.dp)
                                    clip = lift > 0f
                                }
                                .background(lerp(P.background, P.surfaceHigh, lift)),
                        ) {
                            TrackRow(
                                track = track,
                                badge = dl.badge(track.id),
                                artwork = Graph.library.artworkFor(track),
                                isCurrent = entry.key == currentKey,
                                isPlaying = state.isPlaying,
                                selected = selection.rowState(index),
                                leading = { DragHandle(handle, active = isDragging, enabled = !selection.active) },
                                onLongClick = { selection.toggle(index) },
                                onClick = { if (selection.active) selection.toggle(index) else Graph.player.jumpTo(index) },
                                onMore = {
                                    sheets(
                                        Actions.trackSheet(
                                            track,
                                            extra = buildList {
                                                if (index > 0) add(SheetAction(tr("Move up", "Monter"), Ic.ChevronUp) { Graph.player.move(index, index - 1) })
                                                if (index < state.queue.size - 1) {
                                                    add(SheetAction(tr("Move down", "Descendre"), Ic.ChevronDown) { Graph.player.move(index, index + 1) })
                                                }
                                                add(
                                                    SheetAction(tr("Remove from queue", "Retirer de la file"), Ic.Close, destructive = true) {
                                                        Graph.player.removeAt(index)
                                                    }
                                                )
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
                            SheetAction(tr("Remove from queue", "Retirer de la file"), Ic.Close, destructive = true) {
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

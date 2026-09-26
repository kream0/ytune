package app.ytune.ui.components

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ytune.Graph
import app.ytune.data.Track
import app.ytune.lyrics.LyricLine
import app.ytune.lyrics.Lyrics
import app.ytune.lyrics.LyricsResult
import app.ytune.ui.theme.P
import app.ytune.ui.theme.Type
import kotlinx.coroutines.delay
import java.io.IOException

private sealed interface LyricsUi {
    data object Loading : LyricsUi
    data object Offline : LyricsUi
    data class Ready(val result: LyricsResult) : LyricsUi
}

/**
 * Spotify-style lyrics in place of the cover: synced lines scroll by themselves, the line being
 * sung fills in karaoke-style, sung lines stay lit and upcoming ones are dimmed. A tap anywhere
 * calls [onTap] (next cover mode); long-press a line to jump there. Scrolling by hand pauses the
 * auto-scroll for a few seconds.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricsView(track: Track, onTap: () -> Unit, modifier: Modifier = Modifier) {
    var attempt by remember(track.id) { mutableIntStateOf(0) }
    val ui by produceState<LyricsUi>(LyricsUi.Loading, track.id, attempt) {
        value = LyricsUi.Loading
        value = try {
            LyricsUi.Ready(Graph.lyrics.get(track))
        } catch (e: IOException) {
            LyricsUi.Offline
        }
    }
    val tap = remember { MutableInteractionSource() }
    Box(
        modifier.combinedClickable(interactionSource = tap, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = ui) {
            LyricsUi.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DotLoader()
                Spacer(Modifier.height(12.dp))
                Text("LOOKING FOR LYRICS", style = Type.label, color = P.textDim)
            }
            LyricsUi.Offline -> Message("NO CONNECTION", "Lyrics need the internet the first time.") {
                PillButton("Retry", { attempt++ }, icon = Ic.Refresh)
            }
            is LyricsUi.Ready -> when (val r = s.result) {
                LyricsResult.Missing -> Message("NO LYRICS", "None found for this song. Tap to show the cover.")
                LyricsResult.Instrumental -> Message("♪ ♪ ♪", "Instrumental")
                is LyricsResult.Found ->
                    if (r.lyrics.synced) SyncedLyrics(r.lyrics, onTap) else PlainLyrics(r.lyrics, onTap)
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = Type.displaySmall, color = P.text)
        Spacer(Modifier.height(8.dp))
        Text(body, style = Type.body, color = P.textDim, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

private val lyricStyle = TextStyle(
    fontFamily = Type.titleLarge.fontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 25.sp,
    lineHeight = 32.sp,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SyncedLyrics(lyrics: Lyrics, onTap: () -> Unit) {
    val position = rememberSmoothPosition()
    val active by remember(lyrics) { derivedStateOf { lyrics.indexAt(position.value) } }
    val listState = rememberLazyListState()
    var touchedAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start || it is DragInteraction.Stop) touchedAt = SystemClock.uptimeMillis()
        }
    }
    // Follow the song, unless you've just scrolled yourself; then catch up a few seconds later.
    LaunchedEffect(active, touchedAt) {
        val idle = SystemClock.uptimeMillis() - touchedAt
        if (idle < RESUME_AFTER_MS) delay(RESUME_AFTER_MS - idle)
        if (listState.isScrollInProgress) return@LaunchedEffect
        listState.animateScrollToItem(active.coerceAtLeast(0))
    }

    BoxWithConstraints(Modifier.fillMaxSize().fadingEdges()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // The line being sung sits about a third of the way down.
            contentPadding = PaddingValues(top = maxHeight * 0.3f, bottom = maxHeight * 0.65f),
        ) {
            itemsIndexed(lyrics.lines, key = { i, _ -> i }) { index, line ->
                val state = when {
                    index == active -> LineState.Active
                    index < active -> LineState.Sung
                    else -> LineState.Upcoming
                }
                LyricRow(
                    line = line,
                    state = state,
                    progress = { lyrics.progress(index, position.value) },
                    onTap = onTap,
                    onLongPress = { Graph.player.seekTo(line.timeMs) },
                )
            }
        }
    }
}

private enum class LineState { Sung, Active, Upcoming }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LyricRow(
    line: LyricLine,
    state: LineState,
    progress: () -> Float,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val lit = P.text
    val dim = P.text.copy(alpha = 0.28f)
    val base by animateColorAsState(
        when (state) {
            LineState.Sung -> P.text.copy(alpha = 0.6f)
            LineState.Active -> dim
            LineState.Upcoming -> dim
        },
        animationSpec = tween(350),
        label = "lyric",
    )
    val modifier = Modifier
        .fillMaxWidth()
        .combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onTap,
            onLongClick = onLongPress,
        )
        .padding(horizontal = 22.dp, vertical = 9.dp)
    if (line.isBreak) {
        // Instrumental break: three dots that light up as it goes by.
        Box(modifier) {
            KaraokeText("•  •  •", lyricStyle, base, if (state == LineState.Active) lit else base, progress)
        }
    } else {
        Box(modifier) {
            KaraokeText(line.text, lyricStyle, base, if (state == LineState.Active) lit else base, progress)
        }
    }
}

/** Text drawn twice: [dim] underneath, [bright] on top clipped to the sung part. */
@Composable
private fun KaraokeText(text: String, style: TextStyle, dim: Color, bright: Color, progress: () -> Float) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box {
        Text(text, style = style, color = dim, onTextLayout = { layout = it })
        if (bright != dim) {
            Text(
                text,
                style = style,
                color = bright,
                modifier = Modifier.drawWithContent {
                    val l = layout ?: return@drawWithContent
                    val f = progress()
                    if (f <= 0f) return@drawWithContent
                    if (f >= 1f) {
                        drawContent()
                        return@drawWithContent
                    }
                    val exact = text.length * f
                    val char = exact.toInt().coerceIn(0, text.length)
                    val row = l.getLineForOffset(char)
                    val path = Path()
                    for (i in 0 until row) {
                        path.addRect(Rect(l.getLineLeft(i), l.getLineTop(i), l.getLineRight(i), l.getLineBottom(i)))
                    }
                    val x0 = l.getHorizontalPosition(char, usePrimaryDirection = true)
                    val x1 = if (char < text.length && l.getLineForOffset(char + 1) == row) {
                        l.getHorizontalPosition(char + 1, usePrimaryDirection = true)
                    } else {
                        x0
                    }
                    val x = x0 + (x1 - x0) * (exact - char)
                    path.addRect(Rect(l.getLineLeft(row), l.getLineTop(row), x, l.getLineBottom(row)))
                    clipPath(path) { this@drawWithContent.drawContent() }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlainLyrics(lyrics: Lyrics, onTap: () -> Unit) {
    Box(Modifier.fillMaxSize().fadingEdges()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 36.dp)) {
            item(key = "label") {
                Text(
                    "NOT SYNCED",
                    style = Type.label,
                    color = P.textFaint,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                )
            }
            itemsIndexed(lyrics.lines, key = { i, _ -> i }) { _, line ->
                Text(
                    line.text,
                    style = lyricStyle.copy(fontSize = 21.sp, lineHeight = 28.sp),
                    color = P.text.copy(alpha = 0.75f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onTap,
                        )
                        .padding(horizontal = 22.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/**
 * Playback position that moves every frame: the player reports it every 250 ms, and in
 * between it's extrapolated from the frame clock while playing.
 */
@Composable
private fun rememberSmoothPosition(): State<Long> {
    val progress by Graph.player.progress.collectAsStateWithLifecycle()
    val player by Graph.player.state.collectAsStateWithLifecycle()
    val position = remember { mutableLongStateOf(progress.positionMs) }
    LaunchedEffect(progress, player.isPlaying) {
        val base = progress.positionMs
        position.longValue = base
        if (!player.isPlaying) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { now -> position.longValue = base + (now - start) }
        }
    }
    return position
}

/** Fades the top and bottom edges out, so lines scroll in and out softly. */
private fun Modifier.fadingEdges() = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.12f to Color.Black,
                0.85f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

private const val RESUME_AFTER_MS = 3_500L

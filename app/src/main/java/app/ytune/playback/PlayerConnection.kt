package app.ytune.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.ytune.Graph
import app.ytune.data.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

data class PlayerUiState(
    val connected: Boolean = false,
    val current: Track? = null,
    val currentIndex: Int = -1,
    val queue: List<Track> = emptyList(),
    /** Queue positions added by autoplay (YouTube's suggestions) rather than by you. */
    val suggested: Set<Int> = emptySet(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playWhenReady: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
)

data class PlayerProgress(val positionMs: Long = 0, val bufferedMs: Long = 0)

/** UI-side handle on [PlaybackService] through a Media3 [MediaController]. */
class PlayerConnection(private val context: Context) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlayerProgress())
    val progress: StateFlow<PlayerProgress> = _progress.asStateFlow()

    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var ticker: Job? = null

    fun connect() {
        if (controller != null || future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null) {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            refresh(queueChanged = true)
            val actions = pending.toList()
            pending.clear()
            actions.forEach { it(c) }
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        future = null
        ticker?.cancel()
        _state.value = _state.value.copy(connected = false)
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED))
        }
    }

    private fun refresh(queueChanged: Boolean) {
        val c = controller ?: return
        val count = c.mediaItemCount
        val rebuild = queueChanged || _state.value.queue.size != count
        val queue = if (rebuild) (0 until count).map { MediaItems.toTrack(c.getMediaItemAt(it)) } else _state.value.queue
        val suggested = if (rebuild) {
            (0 until count).filterTo(HashSet()) { MediaItems.isSuggested(c.getMediaItemAt(it)) }
        } else {
            _state.value.suggested
        }
        val current = c.currentMediaItem?.let { MediaItems.toTrack(it) }
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: ((current?.durationSec ?: 0L) * 1000)
        _state.value = PlayerUiState(
            connected = true,
            current = current,
            currentIndex = if (count > 0) c.currentMediaItemIndex else -1,
            queue = queue,
            suggested = suggested,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            playWhenReady = c.playWhenReady,
            durationMs = duration,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            error = c.playerError?.let { it.cause?.message ?: it.errorCodeName },
        )
        _progress.value = PlayerProgress(c.currentPosition, c.bufferedPosition)
        if (c.isPlaying) startTicker() else ticker?.cancel()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = Graph.scope.launch {
            while (isActive) {
                controller?.let { _progress.value = PlayerProgress(it.currentPosition, it.bufferedPosition) }
                delay(250)
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending += block
            connect()
        }
    }

    private fun item(track: Track) = MediaItems.build(track, Graph.library.artworkFor(track))

    // ------------------------------------------------------------------ commands

    fun playAll(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        withController { c ->
            val start = if (shuffle) Random.nextInt(tracks.size) else startIndex.coerceIn(0, tracks.size - 1)
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(tracks.map(::item), start, 0L)
            c.prepare()
            c.play()
        }
    }

    /** Plays [track] right now without throwing away the rest of the queue. */
    fun playNow(track: Track) = withController { c ->
        val existing = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == track.id }
        when {
            existing != null -> c.seekToDefaultPosition(existing)
            c.mediaItemCount == 0 -> c.setMediaItem(item(track))
            else -> {
                val at = c.currentMediaItemIndex + 1
                c.addMediaItem(at, item(track))
                c.seekToDefaultPosition(at)
            }
        }
        c.prepare()
        c.play()
    }

    fun playNext(tracks: List<Track>) = withController { c ->
        if (tracks.isEmpty()) return@withController
        val wasEmpty = c.mediaItemCount == 0
        val at = if (wasEmpty) 0 else c.currentMediaItemIndex + 1
        c.addMediaItems(at, tracks.map(::item))
        if (wasEmpty) c.prepare()
    }

    /** Adds to the end of what you queued: before autoplay's upcoming suggestions, if any. */
    fun enqueue(tracks: List<Track>) = withController { c ->
        if (tracks.isEmpty()) return@withController
        val wasEmpty = c.mediaItemCount == 0
        val beforeSuggestions = (c.currentMediaItemIndex + 1 until c.mediaItemCount)
            .firstOrNull { MediaItems.isSuggested(c.getMediaItemAt(it)) }
        if (beforeSuggestions != null) c.addMediaItems(beforeSuggestions, tracks.map(::item))
        else c.addMediaItems(tracks.map(::item))
        if (wasEmpty) c.prepare()
    }

    fun togglePlay() = withController { c ->
        val active = c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playbackState != Player.STATE_IDLE
        if (active) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController {
        it.seekTo(positionMs)
        _progress.value = _progress.value.copy(positionMs = positionMs)
    }

    fun jumpTo(index: Int) = withController { c ->
        if (index !in 0 until c.mediaItemCount) return@withController
        c.seekToDefaultPosition(index)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun removeAt(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    /** Removes several queue entries at once (highest index first, so the others don't shift). */
    fun removeAll(indices: Collection<Int>) = withController { c ->
        indices.distinct().sortedDescending().forEach { if (it in 0 until c.mediaItemCount) c.removeMediaItem(it) }
    }

    fun move(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun clearQueue() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}

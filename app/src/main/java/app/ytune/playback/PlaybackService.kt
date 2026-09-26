@file:OptIn(UnstableApi::class)

package app.ytune.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.ytune.Graph
import app.ytune.MainActivity
import app.ytune.R
import app.ytune.data.DownloadStrategy
import app.ytune.glyph.GlyphNowPlaying
import app.ytune.glyph.GlyphSupport
import app.ytune.glyph.NowPlayingInfo
import app.ytune.yt.YouTube
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the ExoPlayer + MediaSession. Media3 wires the session to the system, which is what
 * makes Bluetooth headsets work: Nothing Ear pinch/tap gestures arrive as AVRCP media keys
 * (play, pause, next, previous) and are dispatched to this session, even with the screen off.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveJob: Job? = null
    private var glyph: GlyphNowPlaying? = null

    private companion object {
        /** Less music than this buffered ahead counts as struggling. */
        const val LOW_BUFFER_MS = 15_000L
        /** Suggestions appended each time the queue is about to run out. */
        const val SUGGESTIONS_PER_BATCH = 10
    }

    private var suggestJob: Job? = null
    /** Song we last fetched suggestions for, so each song is only asked about once. */
    private var suggestedFor: String? = null

    /** Items we already retried once with a fresh stream URL. */
    private val retried = mutableSetOf<String>()
    private var consecutiveFailures = 0

    override fun onCreate() {
        super.onCreate()

        val remote = CacheDataSource.Factory()
            .setCache(StreamCache.get(this))
            .setUpstreamDataSourceFactory(ChunkedDataSource.Factory(OkHttpDataSource.Factory(Graph.http)))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val local = DefaultDataSource.Factory(this)
        val dataSourceFactory = ResolvingDataSource.Factory(
            RoutingDataSource.Factory(local, remote),
            TrackResolver(Graph.library, Graph.settings),
        )

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when earbuds disconnect
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_ytune)
            }
        )

        restoreQueue()
        publishNowPlaying()

        if (GlyphSupport.isSupported) {
            glyph = GlyphNowPlaying(this, scope).also { it.start() }
        }

        scope.launch {
            Graph.settings.state
                .map { Triple(it.mode, it.strategy, it.lookahead) }
                .distinctUntilChanged()
                .collect { autoDownload() }
        }

        scope.launch {
            Graph.settings.state.map { it.autoplay }.distinctUntilChanged().collect { on ->
                if (on) topUpWithSuggestions() else dropUpcomingSuggestions()
            }
        }

        scope.launch {
            var known: Set<String>? = null
            Graph.library.data
                .map { it.audio.keys }
                .distinctUntilChanged()
                .collect { keys ->
                    val saved = known?.let { keys - it }.orEmpty()
                    known = keys
                    if (saved.isNotEmpty()) onSaved(saved)
                }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            saveQueueNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveQueueNow()
        glyph?.release()
        glyph = null
        Graph.nowPlaying.value = null
        scope.cancel()
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ listener

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publishNowPlaying()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            OpenedFrom.retainOnly(setOfNotNull(mediaItem?.mediaId, nextItemId()))
            autoDownload()
            scheduleSave()
            topUpWithSuggestions()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                autoDownload()
                scheduleSave()
                topUpWithSuggestions()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = scheduleSave()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = scheduleSave()

        override fun onRepeatModeChanged(repeatMode: Int) {
            scheduleSave()
            topUpWithSuggestions()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // A restored queue isn't prepared yet: prepare lazily on the first "play"
            // (from the UI, the notification or an earbud tap).
            if (playWhenReady && player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                player.prepare()
            }
            if (playWhenReady) topUpWithSuggestions()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                consecutiveFailures = 0
                player.currentMediaItem?.mediaId?.let { retried.remove(it) }
            }
            // Ran off the end (suggestions weren't in yet, e.g. offline then): try once more.
            if (playbackState == Player.STATE_ENDED) topUpWithSuggestions(retry = true)
            // Stalled on the network although the song is saved: carry on from the file.
            if (playbackState == Player.STATE_BUFFERING) {
                val item = player.currentMediaItem
                if (item != null && streamingSavedTrack(item)) switchToDisk(player.currentMediaItemIndex)
            }
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    private fun handleError(error: PlaybackException) {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId

        // Most failures are expired / rejected stream URLs: re-extract once and retry in place.
        if (retried.add(id)) {
            YouTube.invalidate(id)
            player.prepare()
            return
        }
        retried.remove(id)
        consecutiveFailures++

        val reason = error.cause?.message ?: error.errorCodeName
        Graph.toast("Can't play “${item.mediaMetadata.title}” — $reason")

        if (consecutiveFailures < 3 && player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
        } else {
            player.pause()
        }
    }

    /** Snapshot for the Glyph Matrix (title, play state, position). */
    private fun publishNowPlaying() {
        val item = player.currentMediaItem
        Graph.nowPlaying.value = item?.let {
            val track = MediaItems.toTrack(it)
            NowPlayingInfo(
                id = track.id,
                title = track.title,
                artist = track.artist,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: (track.durationSec * 1000),
                sampledAt = SystemClock.elapsedRealtime(),
            )
        }
    }

    // ------------------------------------------------------------------ stream -> file

    /** Songs just finished downloading. */
    private fun onSaved(ids: Set<String>) {
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val current = player.currentMediaItem
        if (current != null && current.mediaId in ids && streamingSavedTrack(current) && streamStruggling()) {
            switchToDisk(index)
        }
        // The next song may already be preloading from the network: point it at the file.
        val next = player.nextMediaItemIndex
        if (next != C.INDEX_UNSET && next != index) {
            val item = player.getMediaItemAt(next)
            if (item.mediaId in ids && streamingSavedTrack(item)) switchToDisk(next)
        }
    }

    private fun streamingSavedTrack(item: MediaItem): Boolean =
        !MediaItems.isDiskReload(item) && OpenedFrom.isNetwork(item.mediaId) && Graph.library.isDownloaded(item.mediaId)

    /**
     * Whether the stream is (about to be) stalling. A healthy one is left alone, since swapping
     * sources costs a tiny gap; if it stalls later, the buffering check above moves it.
     */
    private fun streamStruggling(): Boolean {
        if (player.playbackState == Player.STATE_BUFFERING) return true
        val duration = player.duration
        if (duration != C.TIME_UNSET && player.bufferedPosition >= duration - 1_000) return false
        return player.bufferedPosition - player.currentPosition < LOW_BUFFER_MS
    }

    /** Re-opens the item at [index] from its downloaded file, keeping the position if it's playing. */
    private fun switchToDisk(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        val item = player.getMediaItemAt(index)
        val isCurrent = index == player.currentMediaItemIndex
        val position = player.currentPosition
        player.replaceMediaItem(index, item.buildUpon().setUri(MediaItems.diskUriFor(item.mediaId)).build())
        if (isCurrent) player.seekTo(index, position)
    }

    private fun nextItemId(): String? =
        player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { player.getMediaItemAt(it).mediaId }

    // ------------------------------------------------------------------ autoplay

    /**
     * Autoplay: when at most one song is left, append YouTube's suggestions for the current one
     * (never songs already in the queue). Runs in both stream modes; suggestions that play get
     * saved like anything else in "stream + download".
     */
    private fun topUpWithSuggestions(retry: Boolean = false) {
        if (!Graph.settings.current.autoplay || player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (!player.playWhenReady && !retry) return // e.g. a restored queue nobody pressed play on
        val seed = player.currentMediaItem?.mediaId ?: return
        if (upcomingCount() > 1 || suggestJob?.isActive == true) return
        if (seed == suggestedFor && !retry) return
        suggestedFor = seed
        suggestJob = scope.launch {
            val found = try {
                withContext(Dispatchers.IO) { YouTube.suggestionsFor(seed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            // The user may have moved on or switched autoplay off meanwhile.
            if (!Graph.settings.current.autoplay || player.mediaItemCount == 0) return@launch
            val inQueue = (0 until player.mediaItemCount).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
            val picks = found.filter { it.id !in inQueue }.take(SUGGESTIONS_PER_BATCH)
            if (picks.isEmpty()) return@launch
            val ended = player.playbackState == Player.STATE_ENDED
            val first = player.mediaItemCount
            player.addMediaItems(picks.map { MediaItems.build(it, Graph.library.artworkFor(it), suggested = true) })
            if (ended) {
                player.seekTo(first, 0)
                player.play()
            }
        }
    }

    /** Autoplay switched off: take out the suggestions that haven't played yet. */
    private fun dropUpcomingSuggestions() {
        suggestJob?.cancel()
        suggestedFor = null
        val from = player.currentMediaItemIndex + 1
        (player.mediaItemCount - 1 downTo from)
            .filter { MediaItems.isSuggested(player.getMediaItemAt(it)) }
            .forEach { player.removeMediaItem(it) }
    }

    /** Songs still to come after the current one (0, 1 or "2+"), in play order. */
    private fun upcomingCount(): Int {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return 0
        var index = player.currentMediaItemIndex
        var n = 0
        while (n < 2) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) break
            n++
        }
        return n
    }

    // ------------------------------------------------------------------ auto download

    /** "Stream + download" mode: mirror the queue to disk, progressively or all at once. */
    private fun autoDownload() {
        val settings = Graph.settings.current
        if (!settings.downloadWhileStreaming) return
        val count = player.mediaItemCount
        if (count == 0) return

        val indices = when (settings.strategy) {
            // What you queued, all of it; autoplay suggestions only as they come up (below).
            DownloadStrategy.ALL_AT_ONCE ->
                (0 until count).filterNot { MediaItems.isSuggested(player.getMediaItemAt(it)) } +
                    upcoming(settings.lookahead)
            DownloadStrategy.PROGRESSIVE -> upcoming(settings.lookahead)
        }
        val tracks = indices.distinct().map { MediaItems.toTrack(player.getMediaItemAt(it)) }
        Graph.downloads.enqueue(tracks, auto = true)
    }

    /** The current song and the next [lookahead] ones, in play order. */
    private fun upcoming(lookahead: Int): List<Int> {
        val count = player.mediaItemCount
        val timeline = player.currentTimeline
        val result = mutableListOf<Int>()
        var index = player.currentMediaItemIndex.coerceIn(0, count - 1)
        result += index
        while (result.size <= lookahead) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) break
            result += index
        }
        return result
    }

    // ------------------------------------------------------------------ queue persistence

    private fun restoreQueue() {
        val saved = Graph.queueStore.load() ?: return
        val items = saved.tracks.map { MediaItems.build(it, Graph.library.artworkFor(it)) }
        val index = saved.index.coerceIn(0, items.size - 1)
        player.setMediaItems(items, index, saved.positionMs.coerceAtLeast(0))
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeatMode
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1500)
            val snapshot = snapshotQueue()
            launch(Dispatchers.IO) { Graph.queueStore.save(snapshot) }
        }
    }

    private fun saveQueueNow() {
        if (!::player.isInitialized) return
        saveJob?.cancel()
        Graph.queueStore.save(snapshotQueue())
    }

    private fun snapshotQueue(): SavedQueue = SavedQueue(
        tracks = (0 until player.mediaItemCount).map { MediaItems.toTrack(player.getMediaItemAt(it)) },
        index = player.currentMediaItemIndex.coerceAtLeast(0),
        positionMs = player.currentPosition.coerceAtLeast(0),
        shuffle = player.shuffleModeEnabled,
        repeatMode = player.repeatMode,
    )

    // ------------------------------------------------------------------ session callback

    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { MediaItems.withUri(it) }.toMutableList())

        /** Earbud "play" while the app is not running → pick up where we left off. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = Graph.queueStore.load()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            val items = saved.tracks.map { MediaItems.build(it, Graph.library.artworkFor(it)) }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    items,
                    saved.index.coerceIn(0, items.size - 1),
                    saved.positionMs.coerceAtLeast(0),
                )
            )
        }
    }
}

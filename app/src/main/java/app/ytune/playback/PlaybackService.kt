@file:OptIn(UnstableApi::class)

package app.ytune.playback

import android.app.PendingIntent
import android.content.Intent
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
import app.ytune.yt.YouTube
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

    /** Items we already retried once with a fresh stream URL. */
    private val retried = mutableSetOf<String>()
    private var consecutiveFailures = 0

    override fun onCreate() {
        super.onCreate()

        val remote = CacheDataSource.Factory()
            .setCache(StreamCache.get(this))
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Graph.http))
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

        scope.launch {
            Graph.settings.state
                .map { Triple(it.mode, it.strategy, it.lookahead) }
                .distinctUntilChanged()
                .collect { autoDownload() }
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
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            autoDownload()
            scheduleSave()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                autoDownload()
                scheduleSave()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = scheduleSave()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = scheduleSave()

        override fun onRepeatModeChanged(repeatMode: Int) = scheduleSave()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // A restored queue isn't prepared yet: prepare lazily on the first "play"
            // (from the UI, the notification or an earbud tap).
            if (playWhenReady && player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                player.prepare()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                consecutiveFailures = 0
                player.currentMediaItem?.mediaId?.let { retried.remove(it) }
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

    // ------------------------------------------------------------------ auto download

    /** "Stream + download" mode: mirror the queue to disk, progressively or all at once. */
    private fun autoDownload() {
        val settings = Graph.settings.current
        if (!settings.downloadWhileStreaming) return
        val count = player.mediaItemCount
        if (count == 0) return

        val indices = when (settings.strategy) {
            DownloadStrategy.ALL_AT_ONCE -> (0 until count).toList()
            DownloadStrategy.PROGRESSIVE -> {
                val timeline = player.currentTimeline
                val result = mutableListOf<Int>()
                var index = player.currentMediaItemIndex.coerceIn(0, count - 1)
                result += index
                while (result.size <= settings.lookahead) {
                    index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
                    if (index == C.INDEX_UNSET) break
                    result += index
                }
                result
            }
        }
        val tracks = indices.map { MediaItems.toTrack(player.getMediaItemAt(it)) }
        Graph.downloads.enqueue(tracks, auto = true)
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

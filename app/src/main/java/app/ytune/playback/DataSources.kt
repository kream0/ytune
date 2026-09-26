@file:OptIn(UnstableApi::class)

package app.ytune.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.ytune.data.Library
import app.ytune.data.Settings
import app.ytune.yt.YouTube
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Process-wide stream cache (SimpleCache must be a singleton per folder). */
object StreamCache {
    private const val MAX_BYTES = 512L * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "stream-cache"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    fun sizeBytes(context: Context): Long = get(context).cacheSpace

    fun clear(context: Context) {
        val c = get(context)
        c.keys.toList().forEach { key -> runCatching { c.removeResource(key) } }
    }
}

/**
 * Turns `ytune://track/<id>` into something playable: the downloaded file if we have one,
 * otherwise a freshly extracted YouTube audio URL (with the headers YouTube expects).
 * Runs on ExoPlayer's loading thread, so blocking network calls are fine here.
 */
class TrackResolver(
    private val library: Library,
    private val settings: Settings,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != MediaItems.SCHEME) return dataSpec
        val id = uri.lastPathSegment ?: throw IOException("Malformed track uri: $uri")

        library.localAudio(id)?.let { local ->
            OpenedFrom.disk(id)
            return dataSpec.withUri(Uri.fromFile(File(local.path)))
        }

        val stream = YouTube.resolve(id, settings.current.quality)
        OpenedFrom.network(id)
        return dataSpec.buildUpon()
            .setUri(Uri.parse(stream.url))
            .setKey("yt:$id:${stream.itag}")
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + mapOf("User-Agent" to stream.userAgent))
            .build()
    }
}

/**
 * Which tracks the player last opened from the network rather than a downloaded file. A stream
 * that's already open keeps reading from the network even after the song finishes downloading,
 * so the playback service uses this to move it onto the file.
 */
object OpenedFrom {
    private val network: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun network(id: String) {
        network.add(id)
    }

    fun disk(id: String) {
        network.remove(id)
    }

    fun isNetwork(id: String): Boolean = id in network

    /** Forget everything but these (the current and next songs); the rest will be reopened anyway. */
    fun retainOnly(ids: Set<String>) {
        network.retainAll(ids)
    }
}

/**
 * Reads a remote file as consecutive bounded range requests ([chunkSize] bytes each), the way
 * the downloader does. YouTube throttles one long open-ended request to roughly playback speed,
 * so any hiccup on the network turned into buffering, while short ranged requests come in at
 * full speed. Playback still starts as soon as the first bytes arrive.
 */
class ChunkedDataSource(private val upstream: DataSource, private val chunkSize: Long) : DataSource {
    private var spec: DataSpec? = null
    private var position = 0L // absolute position of the next byte
    private var end = C.LENGTH_UNSET.toLong() // absolute end of the requested range, if bounded
    private var total = C.LENGTH_UNSET.toLong() // whole file size, from Content-Range
    private var chunkRequested = 0L
    private var chunkRead = 0L
    private var chunkOpen = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else C.LENGTH_UNSET.toLong()
        total = C.LENGTH_UNSET.toLong()
        openChunk()
        return when {
            end != C.LENGTH_UNSET.toLong() -> end - position
            total != C.LENGTH_UNSET.toLong() -> (total - position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    /** Opens the next range; false when there's nothing left to read. */
    private fun openChunk(): Boolean {
        val s = spec ?: return false
        var length = chunkSize
        if (end != C.LENGTH_UNSET.toLong()) length = minOf(length, end - position)
        if (total != C.LENGTH_UNSET.toLong()) length = minOf(length, total - position)
        if (length <= 0) return false
        upstream.open(s.subrange(position - s.position, length))
        chunkOpen = true
        chunkRequested = length
        chunkRead = 0
        if (total == C.LENGTH_UNSET.toLong()) total = totalFrom(upstream.responseHeaders)
        return true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (!chunkOpen) return C.RESULT_END_OF_INPUT
            val n = upstream.read(buffer, offset, length)
            if (n != C.RESULT_END_OF_INPUT) {
                position += n
                chunkRead += n
                return n
            }
            // This range is done: move on to the next one, unless the file ended early.
            upstream.close()
            chunkOpen = false
            if (total == C.LENGTH_UNSET.toLong() && chunkRead < chunkRequested) return C.RESULT_END_OF_INPUT
            if (chunkRead == 0L || !openChunk()) return C.RESULT_END_OF_INPUT
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null
        if (chunkOpen) {
            chunkOpen = false
            upstream.close()
        }
    }

    /** "bytes 0-2097151/3456789" → 3456789. */
    private fun totalFrom(headers: Map<String, List<String>>): Long =
        headers.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value?.firstOrNull()?.substringAfter('/', "")?.toLongOrNull()
            ?: C.LENGTH_UNSET.toLong()

    class Factory(private val upstream: DataSource.Factory, private val chunkSize: Long = 2L * 1024 * 1024) :
        DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource(), chunkSize)
    }
}

/** Sends local files straight to disk and everything else through the (cached) network stack. */
class RoutingDataSource(
    private val local: DataSource,
    private val remote: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme
        val source = if (scheme == "file" || scheme == "content") local else remote
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IOException("Data source not opened")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(
        private val local: DataSource.Factory,
        private val remote: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RoutingDataSource(local.createDataSource(), remote.createDataSource())
    }
}

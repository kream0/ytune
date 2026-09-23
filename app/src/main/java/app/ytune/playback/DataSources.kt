@file:OptIn(UnstableApi::class)

package app.ytune.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
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
            return dataSpec.withUri(Uri.fromFile(File(local.path)))
        }

        val stream = YouTube.resolve(id, settings.current.quality)
        return dataSpec.buildUpon()
            .setUri(Uri.parse(stream.url))
            .setKey("yt:$id:${stream.itag}")
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + mapOf("User-Agent" to stream.userAgent))
            .build()
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

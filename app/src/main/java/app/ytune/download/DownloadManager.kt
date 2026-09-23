package app.ytune.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import app.ytune.data.Library
import app.ytune.data.LocalAudio
import app.ytune.data.Settings
import app.ytune.data.StreamMode
import app.ytune.data.Track
import app.ytune.yt.ResolvedStream
import app.ytune.yt.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

enum class DlStatus { QUEUED, WAITING_NETWORK, RUNNING, DONE, FAILED, CANCELED }

data class DlTask(
    val track: Track,
    val status: DlStatus,
    val bytes: Long = 0,
    val total: Long = -1,
    val error: String? = null,
    /** Queued by "stream + download" rather than by an explicit tap on Download. */
    val auto: Boolean = false,
) {
    val progress: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    val isActive: Boolean
        get() = status == DlStatus.QUEUED || status == DlStatus.RUNNING || status == DlStatus.WAITING_NETWORK
}

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/**
 * Downloads audio with plain ranged HTTP requests (2 MB chunks, like yt-dlp's chunked mode,
 * which keeps YouTube from throttling). Partial files survive app restarts and resume.
 * Task bookkeeping happens on the main thread; transfers run on [Dispatchers.IO].
 */
class DownloadManager(
    private val context: Context,
    private val library: Library,
    private val settings: Settings,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _tasks = MutableStateFlow<Map<String, DlTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DlTask>> = _tasks.asStateFlow()

    private val jobs = HashMap<String, Job>()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    init {
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { pump() }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    scope.launch { pump() }
                }
            })
        }
        scope.launch {
            settings.state.map { it.parallel to it.wifiOnly }.distinctUntilChanged().collect { pump() }
        }
        scope.launch {
            // Leaving "stream + download" drops downloads it queued that haven't started yet.
            settings.state.map { it.mode }.distinctUntilChanged().drop(1).collect { mode ->
                if (mode == StreamMode.STREAM) dropPendingAuto()
            }
        }
    }

    // ------------------------------------------------------------------ public API

    /** Queues tracks that aren't downloaded yet. Returns how many were newly queued. */
    fun enqueue(tracks: List<Track>, auto: Boolean = false): Int {
        var added = 0
        _tasks.update { current ->
            added = 0
            val next = LinkedHashMap(current)
            for (track in tracks.distinctBy { it.id }) {
                if (library.isDownloaded(track.id)) continue
                val existing = next[track.id]
                if (existing != null && existing.isActive) {
                    if (!auto && existing.auto) next[track.id] = existing.copy(auto = false)
                    continue
                }
                next.remove(track.id)
                next[track.id] = DlTask(track, DlStatus.QUEUED, auto = auto)
                added++
            }
            next
        }
        if (added > 0) {
            DownloadService.start(context)
            pump()
        }
        return added
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        mutate(id) { it.copy(status = DlStatus.CANCELED) }
        deletePartials(id)
        pump()
    }

    fun retry(id: String) {
        mutate(id) { it.copy(status = DlStatus.QUEUED, error = null) }
        DownloadService.start(context)
        pump()
    }

    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        _tasks.update { it - id }
        deletePartials(id)
        pump()
    }

    fun cancelAll() {
        _tasks.value.values.filter { it.isActive }.forEach { cancel(it.track.id) }
    }

    fun clearFinished() {
        _tasks.update { m -> m.filterValues { it.isActive } }
    }

    // ------------------------------------------------------------------ scheduling

    private fun dropPendingAuto() {
        _tasks.update { m ->
            m.filterValues { !(it.auto && (it.status == DlStatus.QUEUED || it.status == DlStatus.WAITING_NETWORK)) }
        }
    }

    private fun networkOk(): Pair<Boolean, Boolean> {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false to false
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return online to unmetered
    }

    private fun pump() {
        val s = settings.current
        val (online, unmetered) = networkOk()
        val canRun = online && (!s.wifiOnly || unmetered)
        var running = _tasks.value.values.count { it.status == DlStatus.RUNNING }
        for (task in _tasks.value.values.toList()) {
            val id = task.track.id
            when (task.status) {
                DlStatus.QUEUED, DlStatus.WAITING_NETWORK -> {
                    if (!canRun) {
                        if (task.status != DlStatus.WAITING_NETWORK) mutate(id) { it.copy(status = DlStatus.WAITING_NETWORK) }
                    } else if (running < s.parallel) {
                        running++
                        start(task.track)
                    } else if (task.status == DlStatus.WAITING_NETWORK) {
                        mutate(id) { it.copy(status = DlStatus.QUEUED) }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun start(track: Track) {
        val id = track.id
        mutate(id) { it.copy(status = DlStatus.RUNNING, error = null) }
        jobs[id] = scope.launch {
            val self = coroutineContext[Job]
            val result = runCatching { withContext(Dispatchers.IO) { download(track) } }
            if (jobs[id] === self) jobs.remove(id)
            result.onSuccess { audio ->
                library.addDownloaded(track, audio)
                mutate(id) { it.copy(status = DlStatus.DONE, bytes = audio.sizeBytes, total = audio.sizeBytes) }
            }
            result.onFailure { e ->
                if (e !is CancellationException) {
                    Log.w(TAG, "Download failed for $id", e)
                    mutate(id) { it.copy(status = DlStatus.FAILED, error = e.message ?: e.javaClass.simpleName) }
                }
            }
            pump()
        }
    }

    private fun mutate(id: String, block: (DlTask) -> DlTask) {
        _tasks.update { m -> m[id]?.let { m + (id to block(it)) } ?: m }
    }

    // ------------------------------------------------------------------ transfer

    private suspend fun download(track: Track): LocalAudio {
        val quality = settings.current.quality
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val stream = YouTube.resolve(track.id, quality, forceRefresh = attempt > 0)
                val file = transfer(track.id, stream)
                val art = fetchArtwork(track)
                return LocalAudio(
                    trackId = track.id,
                    path = file.absolutePath,
                    mimeType = stream.mimeType,
                    sizeBytes = file.length(),
                    bitrate = stream.bitrate,
                    artPath = art?.absolutePath,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.cause is ContentNotAvailableException) throw e // private, removed, region-locked…
                attempt++
                if (attempt > 3) throw e
                if (e is HttpStatusException) YouTube.invalidate(track.id)
                delay(1500L * attempt)
            }
        }
    }

    private suspend fun transfer(id: String, stream: ResolvedStream): File {
        val ctx = currentCoroutineContext()
        val dir = library.musicDir
        val target = File(dir, "$id.${stream.extension}")
        val part = File(dir, "$id.${stream.itag}.part")
        dir.listFiles()?.filter { it.name.startsWith("$id.") && it.name.endsWith(".part") && it != part }
            ?.forEach { it.delete() }

        var total = stream.contentLength
        var offset = part.length()
        if (total > 0 && offset > total) {
            part.delete()
            offset = 0
        }
        report(id, offset, total, force = true)

        val buffer = ByteArray(64 * 1024)
        var lastReport = 0L
        FileOutputStream(part, true).use { out ->
            var done = total > 0 && offset >= total
            while (!done) {
                ctx.ensureActive()
                val end = if (total > 0) minOf(offset + CHUNK - 1, total - 1) else offset + CHUNK - 1
                val request = Request.Builder()
                    .url(stream.url)
                    .header("User-Agent", stream.userAgent)
                    .header("Range", "bytes=$offset-$end")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.code == 416) {
                        done = true
                    } else {
                        if (!response.isSuccessful) throw HttpStatusException(response.code)
                        if (response.code == 200 && offset > 0) {
                            // Server ignored the Range header: start over with the full body.
                            out.channel.truncate(0)
                            offset = 0
                        }
                        if (total <= 0) {
                            total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                                ?: response.body?.contentLength()?.takeIf { response.code == 200 && it > 0 }
                                ?: -1L
                        }
                        val body = response.body ?: throw IOException("Empty response body")
                        var read = 0L
                        body.byteStream().use { input ->
                            while (true) {
                                ctx.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                offset += n
                                read += n
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 300) {
                                    lastReport = now
                                    report(id, offset, total)
                                }
                            }
                        }
                        done = response.code == 200 || read == 0L || (total > 0 && offset >= total)
                    }
                }
            }
        }

        if (total > 0 && part.length() < total) throw IOException("Incomplete download")
        target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        report(id, target.length(), target.length(), force = true)
        return target
    }

    private fun report(id: String, bytes: Long, total: Long, force: Boolean = false) {
        if (!force && bytes <= 0) return
        mutate(id) { it.copy(bytes = bytes, total = total) }
    }

    private fun fetchArtwork(track: Track): File? {
        val url = track.thumbnail ?: return null
        return runCatching {
            val file = File(library.artDir, "${track.id}.jpg")
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                file.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            file
        }.getOrNull()
    }

    private fun deletePartials(id: String) {
        scope.launch(Dispatchers.IO) {
            library.musicDir.listFiles()
                ?.filter { it.name.startsWith("$id.") && it.name.endsWith(".part") }
                ?.forEach { it.delete() }
        }
    }

    companion object {
        private const val TAG = "Downloads"
        private const val CHUNK = 2L * 1024 * 1024
    }
}

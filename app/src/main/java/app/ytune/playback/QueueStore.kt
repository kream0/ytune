package app.ytune.playback

import android.content.Context
import app.ytune.data.JsonFile
import app.ytune.data.Track
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class SavedQueue(
    val tracks: List<Track> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)

/** Persists the play queue so it survives app restarts and powers "resume" from earbuds. */
class QueueStore(context: Context) {
    private val file = JsonFile(File(context.filesDir, "queue.json"), SavedQueue.serializer()) { SavedQueue() }

    fun load(): SavedQueue? = file.read().takeIf { it.tracks.isNotEmpty() }

    fun save(queue: SavedQueue) {
        if (queue.tracks.isEmpty()) file.delete() else file.write(queue)
    }
}

package app.ytune.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ytune.data.PlaylistRef
import app.ytune.data.Track
import app.ytune.data.formatDuration
import app.ytune.download.DlStatus
import app.ytune.download.DlTask
import app.ytune.ui.theme.P
import app.ytune.ui.theme.Type
import coil.compose.AsyncImage

// ---------------------------------------------------------------------- shared UI state

sealed interface DlBadge {
    data object None : DlBadge
    data object Done : DlBadge
    data object Queued : DlBadge
    data object Failed : DlBadge
    data class Running(val progress: Float) : DlBadge
}

/** Snapshot of download state for list rows (provided once at the root). */
@Immutable
data class DlSnapshot(val downloaded: Set<String> = emptySet(), val tasks: Map<String, DlTask> = emptyMap()) {
    fun badge(id: String): DlBadge {
        if (id in downloaded) return DlBadge.Done
        val t = tasks[id] ?: return DlBadge.None
        return when (t.status) {
            DlStatus.RUNNING -> DlBadge.Running(t.progress)
            DlStatus.QUEUED, DlStatus.WAITING_NETWORK -> DlBadge.Queued
            DlStatus.FAILED -> DlBadge.Failed
            DlStatus.DONE -> DlBadge.Done
            DlStatus.CANCELED -> DlBadge.None
        }
    }
}

val LocalDl = compositionLocalOf { DlSnapshot() }

data class SheetAction(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

data class SheetSpec(
    val title: String,
    val subtitle: String? = null,
    val actions: List<SheetAction> = emptyList(),
    val content: (@Composable () -> Unit)? = null,
)

/** Opens a bottom sheet; provided by the root screen. */
val LocalSheets = compositionLocalOf<(SheetSpec) -> Unit> { {} }

// ---------------------------------------------------------------------- rows

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(P.surfaceHigh)) {
        DotGrid(Modifier.fillMaxSize(), spacing = 8.dp, radius = 1.dp)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun DownloadBadge(badge: DlBadge, modifier: Modifier = Modifier) {
    when (badge) {
        DlBadge.None -> Unit
        DlBadge.Done -> Box(modifier.size(14.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(P.accent))
        }
        DlBadge.Queued -> DotRing(0f, modifier.size(14.dp), spinning = true, color = P.textDim)
        DlBadge.Failed -> Text("!", style = Type.labelBold, color = P.accent, modifier = modifier)
        is DlBadge.Running -> DotRing(badge.progress, modifier.size(14.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    badge: DlBadge,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    artwork: String? = track.thumbnail,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .padding(start = 16.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            Artwork(artwork, Modifier.fillMaxSize())
            if (isCurrent) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    DotEqualizer(isPlaying, Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = Type.title,
                color = if (isCurrent) P.accent else P.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (badge != DlBadge.None) {
                    DownloadBadge(badge)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    listOf(track.artist.uppercase(), formatDuration(track.durationSec))
                        .filter { it.isNotBlank() }
                        .joinToString("  ·  "),
                    style = Type.label,
                    color = P.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        if (onMore != null) {
            IconBtn(Ic.More, onMore, tint = P.textDim, contentDescription = "More")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistRow(
    ref: PlaylistRef,
    onClick: () -> Unit,
    onAddAll: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    extra: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Stacked-card look to tell playlists apart from songs.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(40.dp).height(2.dp).clip(CircleShape).background(P.outline))
            Spacer(Modifier.height(2.dp))
            Box(Modifier.width(46.dp).height(2.dp).clip(CircleShape).background(P.textFaint))
            Spacer(Modifier.height(2.dp))
            Artwork(ref.thumbnail, Modifier.size(52.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ref.title, style = Type.title, color = P.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val parts = buildList {
                add(if (ref.isAlbum) "ALBUM" else "PLAYLIST")
                if (ref.uploader.isNotBlank()) add(ref.uploader.uppercase())
                if (ref.count >= 0) add("${ref.count} TRACKS")
                if (extra != null) add(extra)
            }
            Text(
                parts.joinToString("  ·  "),
                style = Type.label,
                color = P.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconBtn(Ic.PlaylistAdd, onAddAll, bordered = true, size = 38.dp, iconSize = 20.dp, contentDescription = "Add whole playlist to queue")
        IconBtn(Ic.More, onMore, tint = P.textDim, contentDescription = "More")
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 120.dp, height = 60.dp)) {
            DotGrid(Modifier.fillMaxSize(), color = P.outline, spacing = 12.dp, radius = 2.dp)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = Type.displaySmall, color = P.text)
        Spacer(Modifier.height(8.dp))
        Text(body, style = Type.body, color = P.textDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

@Composable
fun SmallIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(18.dp))
}

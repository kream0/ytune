package app.ytune.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ytune.Graph
import app.ytune.data.StreamMode
import app.ytune.ui.theme.P
import app.ytune.ui.theme.Type

enum class PillStyle { Filled, Accent, Outline }

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: PillStyle = PillStyle.Outline,
    enabled: Boolean = true,
) {
    val (bg, fg, border) = when (style) {
        PillStyle.Filled -> Triple(P.inverse, P.onInverse, P.inverse)
        PillStyle.Accent -> Triple(P.accent, Color.White, P.accent)
        PillStyle.Outline -> Triple(Color.Transparent, P.text, P.outline)
    }
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.labelBold, color = if (enabled) fg else fg.copy(alpha = 0.4f), maxLines = 1)
    }
}

@Composable
fun IconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = P.text,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    bordered: Boolean = false,
    contentDescription: String? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .then(if (bordered) Modifier.border(1.dp, P.outline, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Pill segmented control; the selected segment is inverted (white on black / black on white). */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .border(1.dp, P.outline, CircleShape)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSelected = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (isSelected) P.inverse else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = Type.labelBold,
                    color = if (isSelected) P.onInverse else P.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Nothing-style switch: outlined pill, red when on. */
@Composable
fun NothingSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val x by animateDpAsState(if (checked) 22.dp else 3.dp, label = "switch")
    Box(
        modifier
            .size(width = 46.dp, height = 26.dp)
            .clip(CircleShape)
            .background(if (checked) P.accent else Color.Transparent)
            .border(1.dp, if (checked) P.accent else P.outline, CircleShape)
            .clickable { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = x, y = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else P.textDim),
        )
    }
}

@Composable
fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", enabled = value > range.first) { onChange(value - 1) }
        Text(
            value.toString(),
            style = Type.clock,
            color = P.text,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        StepButton("+", enabled = value < range.last) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .border(1.dp, P.outline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.title, color = if (enabled) P.text else P.textFaint)
    }
}

/** Small red dot + mono caps label. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = P.textDim) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(P.accent))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = Type.label, color = color)
    }
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.display, color = P.text, modifier = Modifier.weight(1f), maxLines = 1)
        trailing()
    }
}

/**
 * The "STREAM" / "STREAM + DL" toggle. Tapping the label flips the mode; the ⋯ opens the
 * options sheet (progressive vs. all-at-once).
 */
@Composable
fun ModeChip(onOptions: () -> Unit, modifier: Modifier = Modifier) {
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val on = settings.downloadWhileStreaming
    Row(
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .border(1.dp, if (on) P.accent else P.outline, CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clickable {
                    Graph.settings.update {
                        it.copy(mode = if (on) StreamMode.STREAM else StreamMode.STREAM_AND_DOWNLOAD)
                    }
                    Graph.toast(
                        if (on) "Stream only" else "Stream + download · ${Graph.settings.current.strategy.label.lowercase()}"
                    )
                }
                .padding(start = 12.dp, end = 8.dp)
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) P.accent else P.textFaint))
            Spacer(Modifier.width(8.dp))
            Text(settings.mode.label, style = Type.labelBold, color = P.text)
        }
        Box(Modifier.width(1.dp).height(18.dp).background(P.outline))
        Box(
            Modifier
                .clickable(onClick = onOptions)
                .padding(horizontal = 8.dp)
                .height(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Ic.ChevronDown, contentDescription = "Mode options", tint = P.textDim, modifier = Modifier.size(18.dp))
        }
    }
}

/** Body of the mode options sheet. */
@Composable
fun ModeOptions() {
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    Column {
        Text("Playback", style = Type.label, color = P.textDim)
        Spacer(Modifier.height(8.dp))
        Segmented(
            options = StreamMode.entries.map { it.label },
            selected = settings.mode.ordinal,
            onSelect = { i -> Graph.settings.update { it.copy(mode = StreamMode.entries[i]) } },
        )
        Spacer(Modifier.height(18.dp))
        Text("Download strategy", style = Type.label, color = P.textDim)
        Spacer(Modifier.height(8.dp))
        Segmented(
            options = app.ytune.data.DownloadStrategy.entries.map { it.label },
            selected = settings.strategy.ordinal,
            onSelect = { i -> Graph.settings.update { it.copy(strategy = app.ytune.data.DownloadStrategy.entries[i]) } },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (settings.downloadWhileStreaming) settings.strategy.description
            else "Stream only: nothing is saved unless you tap Download.",
            style = Type.body,
            color = P.textDim,
        )
    }
}

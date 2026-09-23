package app.ytune.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ytune.ui.theme.P
import app.ytune.ui.theme.Type

/** Bottom sheet overlay: scrim + card sliding up from the bottom. [onShow] swaps in a follow-up sheet. */
@Composable
fun SheetHost(spec: SheetSpec?, onShow: (SheetSpec) -> Unit, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf<SheetSpec?>(null) }
    if (spec != null) last = spec

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = spec != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = spec != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            val shown = last ?: return@AnimatedVisibility
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(P.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                // Grab handle, as three dots.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    repeat(3) {
                        Box(Modifier.padding(horizontal = 3.dp).size(5.dp).clip(CircleShape).background(P.textFaint))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(shown.title, style = Type.title.copy(fontSize = Type.title.fontSize * 1.15f), color = P.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!shown.subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(shown.subtitle.uppercase(), style = Type.label, color = P.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                shown.content?.let {
                    // Keyed so each sheet's content starts fresh (e.g. two name fields in a row).
                    key(shown) { CompositionLocalProvider(LocalSheetClose provides onDismiss) { it() } }
                    Spacer(Modifier.height(8.dp))
                }
                shown.actions.forEach { action ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                val follow = action.next?.invoke()
                                if (follow != null) {
                                    onShow(follow)
                                } else {
                                    onDismiss()
                                    action.onClick()
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = if (action.destructive) P.accent else P.text
                        if (action.icon != null) {
                            Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        } else {
                            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(action.label.uppercase(), style = Type.labelBold.copy(fontSize = Type.label.fontSize * 1.1f), color = tint)
                    }
                }
            }
        }
    }
    // Registered only while open so it outranks handlers of screens underneath (e.g. the player).
    if (spec != null) BackHandler(onBack = onDismiss)
}

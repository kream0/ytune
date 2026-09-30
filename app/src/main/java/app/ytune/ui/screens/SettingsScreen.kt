package app.ytune.ui.screens

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ytune.BuildConfig
import app.ytune.Graph
import app.ytune.data.AudioQuality
import app.ytune.data.DownloadStrategy
import app.ytune.data.Language
import app.ytune.data.StreamMode
import app.ytune.data.ThemeMode
import app.ytune.data.formatBytes
import app.ytune.glyph.GlyphLink
import app.ytune.glyph.GlyphStatus
import app.ytune.glyph.GlyphSupport
import app.ytune.glyph.GlyphTest
import app.ytune.playback.StreamCache
import app.ytune.tr
import app.ytune.trCount
import app.ytune.ui.components.GlyphMatrixPreview
import app.ytune.ui.components.PillStyle
import app.ytune.update.UpdateState
import java.text.DateFormat
import java.util.Date
import app.ytune.ui.components.Ic
import app.ytune.ui.components.LocalSheets
import app.ytune.ui.components.NothingSwitch
import app.ytune.ui.components.PillButton
import app.ytune.ui.components.ScreenHeader
import app.ytune.ui.components.SectionLabel
import app.ytune.ui.components.Segmented
import app.ytune.ui.components.SheetAction
import app.ytune.ui.components.SheetSpec
import app.ytune.ui.components.Stepper
import app.ytune.ui.theme.P
import app.ytune.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val library by Graph.library.data.collectAsStateWithLifecycle()
    val sheets = LocalSheets.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        cacheBytes = withContext(Dispatchers.IO) { runCatching { StreamCache.sizeBytes(context) }.getOrDefault(0L) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(tr("SETTINGS", "RÉGLAGES"))

        Section(tr("Playback", "Lecture")) {
            Block(
                "Mode",
                if (settings.downloadWhileStreaming) {
                    tr("Everything you play is also saved for offline.", "Tout ce que vous écoutez est aussi enregistré pour l'écoute hors ligne.")
                } else {
                    tr("Play from YouTube, save nothing automatically.", "Lecture depuis YouTube, rien n'est enregistré automatiquement.")
                },
            ) {
                Segmented(
                    options = StreamMode.entries.map { it.label },
                    selected = settings.mode.ordinal,
                    onSelect = { i -> Graph.settings.update { it.copy(mode = StreamMode.entries[i]) } },
                )
            }
            Block(tr("Download strategy", "Stratégie de téléchargement"), settings.strategy.description) {
                Segmented(
                    options = DownloadStrategy.entries.map { it.label },
                    selected = settings.strategy.ordinal,
                    onSelect = { i -> Graph.settings.update { it.copy(strategy = DownloadStrategy.entries[i]) } },
                )
            }
            Line(
                tr("Look-ahead", "Anticipation"),
                tr(
                    "Tracks pre-downloaded ahead of the one playing (progressive mode)",
                    "Titres téléchargés à l'avance après celui en cours (mode progressif)",
                ),
            ) {
                Stepper(settings.lookahead, 1..5) { v -> Graph.settings.update { it.copy(lookahead = v) } }
            }
            Block(tr("Audio quality", "Qualité audio"), settings.quality.description) {
                Segmented(
                    options = AudioQuality.entries.map { it.label },
                    selected = settings.quality.ordinal,
                    onSelect = { i -> Graph.settings.update { it.copy(quality = AudioQuality.entries[i]) } },
                )
            }
            Line(
                tr("Autoplay suggestions", "Suggestions en lecture auto"),
                tr(
                    "When the queue runs out, keep playing YouTube's suggestions for the last song. Works when streaming and with STREAM + DL",
                    "Quand la file d'attente est finie, enchaîner sur les suggestions YouTube pour le dernier titre. " +
                        "Fonctionne en streaming et avec STREAM + DL",
                ),
            ) {
                NothingSwitch(settings.autoplay, { on -> Graph.settings.update { it.copy(autoplay = on) } })
            }
            Line(
                tr("Dot-matrix artwork", "Pochette en points"),
                tr(
                    "Render cover art as dots on the player. Tap the cover to cycle dots / photo / lyrics",
                    "Afficher la pochette en points dans le lecteur. Touchez la pochette pour alterner points / photo / paroles",
                ),
            ) {
                NothingSwitch(settings.dotArtwork, { on -> Graph.settings.update { it.copy(dotArtwork = on) } })
            }
        }

        Section(tr("Appearance", "Apparence")) {
            Block(tr("Theme", "Thème"), settings.theme.description) {
                Segmented(
                    options = ThemeMode.entries.map { it.label },
                    selected = settings.theme.ordinal,
                    onSelect = { i -> Graph.settings.update { it.copy(theme = ThemeMode.entries[i]) } },
                )
            }
            Block(tr("Language", "Langue"), tr("The app's menus and messages.", "Les menus et les messages de l'app.")) {
                Segmented(
                    options = Language.entries.map { it.label },
                    selected = settings.language.ordinal,
                    onSelect = { i -> Graph.settings.update { it.copy(language = Language.entries[i]) } },
                )
            }
        }

        Section(tr("Downloads", "Téléchargements")) {
            Line(tr("Wi-Fi only", "Wi-Fi uniquement"), tr("Hold downloads while on mobile data", "Suspendre les téléchargements en données mobiles")) {
                NothingSwitch(settings.wifiOnly, { on -> Graph.settings.update { it.copy(wifiOnly = on) } })
            }
            Line(tr("Parallel downloads", "Téléchargements simultanés"), tr("How many tracks download at the same time", "Nombre de titres téléchargés en même temps")) {
                Stepper(settings.parallel, 1..4) { v -> Graph.settings.update { it.copy(parallel = v) } }
            }
            Line(
                tr("Offline library", "Bibliothèque hors ligne"),
                "${trCount(library.audio.size, "track", "tracks", "titre", "titres")} · ${formatBytes(Graph.library.totalBytes(library))}",
            ) {
                PillButton(tr("Delete", "Supprimer"), {
                    sheets(
                        SheetSpec(
                            title = tr("Delete all downloads?", "Supprimer tous les téléchargements ?"),
                            subtitle = trCount(library.audio.size, "file", "files", "fichier", "fichiers"),
                            actions = listOf(
                                SheetAction(tr("Delete everything", "Tout supprimer"), Ic.Delete, destructive = true) {
                                    Graph.downloads.cancelAll()
                                    Graph.library.deleteAllDownloads()
                                    Graph.toast(tr("Downloads deleted", "Téléchargements supprimés"))
                                },
                            ),
                        )
                    )
                }, enabled = library.audio.isNotEmpty())
            }
            Line(
                tr("Stream cache", "Cache de streaming"),
                tr("${formatBytes(cacheBytes)} of 512 MB · makes replays instant", "${formatBytes(cacheBytes)} sur 512 Mo · réécoutes instantanées"),
            ) {
                PillButton(tr("Clear", "Vider"), {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { StreamCache.clear(context) } }
                        cacheBytes = withContext(Dispatchers.IO) { runCatching { StreamCache.sizeBytes(context) }.getOrDefault(0L) }
                        Graph.toast(tr("Cache cleared", "Cache vidé"))
                    }
                })
            }
        }

        Section("Glyph Matrix") {
            GlyphSection(settings.glyphMatrix)
        }

        Section(tr("Updates", "Mises à jour")) {
            UpdatesSection(settings.autoUpdate)
        }

        Section(tr("Controls", "Commandes")) {
            Text(
                tr(
                    "Playback runs in a media session, so the notification, lock screen and any Bluetooth " +
                        "headset control it. On Nothing Ear: pinch / tap to play-pause, double to skip, " +
                        "triple to go back (whatever you mapped in the Nothing X app). Taking the buds " +
                        "out pauses, and pressing play with the app closed resumes your last queue.",
                    "La lecture passe par une session multimédia : la notification, l'écran de verrouillage " +
                        "et tout casque Bluetooth la contrôlent. Avec les Nothing Ear : pincez / touchez pour " +
                        "lecture-pause, deux fois pour passer au suivant, trois fois pour revenir en arrière " +
                        "(selon vos réglages dans l'app Nothing X). Retirer les écouteurs met en pause, et " +
                        "appuyer sur lecture, app fermée, reprend votre dernière file d'attente.",
                ),
                style = Type.body,
                color = P.textDim,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        Section(tr("About", "À propos")) {
            Text(
                tr(
                    "YTune ${BuildConfig.VERSION_NAME}\nExtraction: NewPipeExtractor (GPLv3) · Playback: AndroidX Media3\n" +
                        "Fonts: Doto, Space Grotesk, Space Mono (OFL) · Glyph Matrix SDK © Nothing",
                    "YTune ${BuildConfig.VERSION_NAME}\nExtraction : NewPipeExtractor (GPLv3) · Lecture : AndroidX Media3\n" +
                        "Polices : Doto, Space Grotesk, Space Mono (OFL) · Glyph Matrix SDK © Nothing",
                ),
                style = Type.label,
                color = P.textFaint,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(18.dp))
    SectionLabel(title, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    Column(Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun Block(title: String, description: String, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, style = Type.title, color = P.text)
        Spacer(Modifier.height(8.dp))
        control()
        Spacer(Modifier.height(6.dp))
        Text(description, style = Type.label, color = P.textDim)
    }
}

@Composable
private fun Line(title: String, description: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.title, color = P.text)
            Spacer(Modifier.height(2.dp))
            Text(description, style = Type.label, color = P.textDim)
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

@Composable
private fun GlyphSection(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val matrix = GlyphSupport.matrixSize.takeIf { it > 0 } ?: 25
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        GlyphMatrixPreview(matrix, Modifier.size(150.dp))
    }
    if (!GlyphSupport.isSupported) {
        Text(
            tr(
                "Preview only: the Glyph Matrix is on Nothing Phone (3) and Phone (4a) Pro. " +
                    "This phone: ${Build.MANUFACTURER} ${Build.MODEL}.",
                "Aperçu seulement : la Glyph Matrix équipe les Nothing Phone (3) et Phone (4a) Pro. " +
                    "Ce téléphone : ${Build.MANUFACTURER} ${Build.MODEL}.",
            ),
            style = Type.label,
            color = P.textDim,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        return
    }
    val status by GlyphLink.status.collectAsStateWithLifecycle()
    val testing by GlyphTest.running.collectAsStateWithLifecycle()

    Line(
        tr("Scroll the title on the Glyph Matrix", "Faire défiler le titre sur la Glyph Matrix"),
        tr(
            "While music plays, the back of the phone shows title · artist and a progress bar",
            "Pendant la lecture, le dos du téléphone affiche titre · artiste et une barre de progression",
        ),
    ) {
        NothingSwitch(enabled, { on -> Graph.settings.update { it.copy(glyphMatrix = on) } })
    }
    if (GlyphSupport.hasGlyphTouch) {
        Line(
            tr("YTune Glyph Toy", "Glyph Toy YTune"),
            tr(
                "Add it to the Glyph Button carousel · long-press the Glyph Button to play / pause",
                "Ajoutez-le au carrousel du Glyph Button · appui long sur le Glyph Button pour lecture / pause",
            ),
        ) {
            PillButton(tr("Add", "Ajouter"), {
                if (!GlyphSupport.openToysManager(context)) {
                    Graph.toast(tr("Open Settings › Glyph Interface › Glyph Toys and add YTune", "Ouvrez Paramètres › Glyph Interface › Glyph Toys et ajoutez YTune"))
                }
            })
        }
    } else {
        Line(
            "Always-on Glyph Toy",
            tr(
                "Also show it with the phone face down: Settings › Glyph Interface › Flip to Glyph › " +
                    "Always-on Glyph Toy › YTune",
                "Affichez-le aussi téléphone retourné : Paramètres › Glyph Interface › Flip to Glyph › " +
                    "Always-on Glyph Toy › YTune",
            ),
        ) {
            PillButton(tr("Open", "Ouvrir"), {
                if (!GlyphSupport.openToysManager(context)) {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    Graph.toast("Glyph Interface › Flip to Glyph › Always-on Glyph Toy › YTune")
                }
            })
        }
    }
    Line(
        tr("Test the matrix", "Tester la matrice"),
        if (testing) {
            tr("Look at the back of the phone…", "Regardez le dos du téléphone…")
        } else {
            tr("Scrolls a test message for 8 seconds", "Fait défiler un message de test pendant 8 secondes")
        },
    ) {
        PillButton(if (testing) tr("Testing", "En cours") else tr("Test", "Tester"), { scope.launch { GlyphTest.run(context) } }, enabled = !testing)
    }
    GlyphDiagnostics(status)
}

/** What the Glyph service told us, so a dark matrix can be explained (and reported). */
@Composable
private fun GlyphDiagnostics(s: GlyphStatus) {
    fun mark(ok: Boolean?) = when (ok) {
        true -> "OK"
        false -> tr("NO", "NON")
        null -> "–"
    }
    val (hint, problem) = when {
        s.error != null -> s.error to true
        s.serviceFound == false ->
            tr(
                "Nothing's Glyph service isn't reachable. Update the phone (Settings › System › System update).",
                "Le service Glyph de Nothing est injoignable. Mettez à jour le téléphone (Paramètres › Système › Mise à jour du système).",
            ) to true
        s.registered == false ->
            tr(
                "The Glyph service refused YTune. Check that Glyph Interface is on and the phone is up to date.",
                "Le service Glyph a refusé YTune. Vérifiez que Glyph Interface est activée et que le téléphone est à jour.",
            ) to true
        s.connected && s.appFrames + s.toyFrames > 0 ->
            tr(
                "Connected and sending. If the matrix stays dark, check that Glyph Interface is on; " +
                    "notifications and other Glyph effects take priority over apps.",
                "Connecté, envoi en cours. Si la matrice reste éteinte, vérifiez que Glyph Interface est activée ; " +
                    "les notifications et autres effets Glyph passent avant les apps.",
            ) to false
        s.connected -> tr("Connected.", "Connecté.") to false
        s.linking -> tr("Waiting for Nothing's Glyph service to answer…", "En attente de réponse du service Glyph de Nothing…") to false
        else -> tr("Tap Test, or play something, to connect.", "Touchez Tester ou lancez la lecture pour établir la connexion.") to false
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            "${GlyphSupport.deviceName} · ${GlyphSupport.model} · Android ${Build.VERSION.RELEASE}\n" +
                tr(
                    "Service ${mark(s.serviceFound)} · link ${mark(s.connected)} · access ${mark(s.registered)}\n",
                    "Service ${mark(s.serviceFound)} · liaison ${mark(s.connected)} · accès ${mark(s.registered)}\n",
                ) +
                tr("Frames: app ${s.appFrames} · toy ${s.toyFrames}", "Images : app ${s.appFrames} · toy ${s.toyFrames}") +
                (if (s.toyBound) tr(" · toy active", " · toy actif") else "") +
                (s.lastToyEvent?.let { tr(" · last event $it", " · dernier évènement $it") } ?: ""),
            style = Type.label,
            color = P.textFaint,
        )
        Spacer(Modifier.height(6.dp))
        Text(hint, style = Type.label, color = if (problem) P.saveRed else P.textDim)
    }
}

@Composable
private fun UpdatesSection(autoUpdate: Boolean) {
    val context = LocalContext.current
    val state by Graph.updater.state.collectAsStateWithLifecycle()
    val last = Graph.updater.lastChecked
    val status = when (val s = state) {
        UpdateState.Idle, UpdateState.UpToDate ->
            if (last > 0) {
                val checked = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))
                tr("Up to date · checked $checked", "À jour · vérifié le $checked")
            } else {
                tr("Not checked yet", "Pas encore vérifié")
            }
        UpdateState.Checking -> tr("Checking…", "Vérification…")
        is UpdateState.Available -> tr("v${s.remote.versionName} available", "v${s.remote.versionName} disponible")
        is UpdateState.Downloading -> {
            val percent = (s.progress * 100).toInt()
            tr("Downloading v${s.remote.versionName} · $percent%", "Téléchargement de la v${s.remote.versionName} · $percent %")
        }
        is UpdateState.Ready -> tr("v${s.remote.versionName} ready to install", "v${s.remote.versionName} prête à installer")
        is UpdateState.Failed -> tr("Update check failed: ${s.message}", "Échec de la vérification : ${s.message}")
    }
    Line("YTune ${Graph.updater.currentVersion}", status) {
        when (val s = state) {
            is UpdateState.Ready -> PillButton(tr("Install", "Installer"), { (context as? Activity)?.let { Graph.updater.install(it) } }, style = PillStyle.Accent)
            is UpdateState.Available -> PillButton(tr("Get", "Obtenir"), { Graph.updater.startDownload() })
            UpdateState.Checking, is UpdateState.Downloading -> Unit
            else -> PillButton(tr("Check", "Vérifier"), { Graph.updater.check(manual = true) })
        }
    }
    Line(
        tr("Auto-download updates", "Téléchargement auto des mises à jour"),
        tr(
            "When a new release is published, download it in the background, then ask before installing",
            "Quand une nouvelle version sort, la télécharger en arrière-plan, puis demander avant de l'installer",
        ),
    ) {
        NothingSwitch(autoUpdate, { on -> Graph.settings.update { it.copy(autoUpdate = on) } })
    }
}

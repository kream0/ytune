package app.ytune.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.content.IntentCompat
import app.ytune.BuildConfig
import app.ytune.Graph
import app.ytune.MainActivity
import app.ytune.data.AppJson
import app.ytune.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** `version.json`, published next to the APK by CI. */
@Serializable
data class RemoteVersion(
    val versionCode: Int,
    val versionName: String,
    val commit: String = "",
    val branch: String = "",
    val notes: String = "",
    val sha256: String = "",
    val size: Long = 0,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val remote: RemoteVersion) : UpdateState
    data class Downloading(val remote: RemoteVersion, val progress: Float) : UpdateState
    data class Ready(val remote: RemoteVersion, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-updater for the sideloaded build: polls the rolling `latest` GitHub release, downloads
 * newer APKs in the background (verifying the SHA-256), then hands them to the system
 * installer, which shows its usual "Do you want to update this app?" prompt.
 */
class Updater(
    private val context: Context,
    private val http: OkHttpClient,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("ytune_updater", Context.MODE_PRIVATE)
    private val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    val currentVersion: String get() = "${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})"
    val lastChecked: Long get() = prefs.getLong(KEY_LAST_CHECK, 0)

    /** Version the user said "later" to; we don't nag again for it this session. */
    var dismissedVersion: Int = -1

    init {
        // Drop APKs for builds we're already running (left over after a successful update).
        scope.launch(Dispatchers.IO) {
            dir.listFiles()?.forEach { f ->
                val code = f.name.removePrefix("ytune-").substringBefore('.').toIntOrNull()
                if (code == null || code <= BuildConfig.VERSION_CODE) f.delete()
            }
        }
    }

    /** Called on app start: checks at most every few hours unless [force]. */
    fun checkIfDue(force: Boolean = false) {
        val due = System.currentTimeMillis() - lastChecked > CHECK_INTERVAL_MS
        if (force || due) check(manual = force)
    }

    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            val remote = try {
                withContext(Dispatchers.IO) { fetchRemote() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
                _state.value = if (manual) UpdateState.Failed(e.message ?: "Couldn't reach GitHub") else UpdateState.Idle
                return@launch
            }
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

            if (remote.versionCode <= BuildConfig.VERSION_CODE) {
                _state.value = UpdateState.UpToDate
                if (manual) Graph.toast("You're on the latest build")
                return@launch
            }
            existingDownload(remote)?.let {
                _state.value = UpdateState.Ready(remote, it)
                return@launch
            }
            if (manual || settings.current.autoUpdate) download(remote) else _state.value = UpdateState.Available(remote)
        }
    }

    fun startDownload() {
        val s = _state.value
        if (s is UpdateState.Available && job?.isActive != true) {
            job = scope.launch { download(s.remote) }
        }
    }

    private suspend fun download(remote: RemoteVersion) {
        _state.value = UpdateState.Downloading(remote, 0f)
        val target = File(dir, "ytune-${remote.versionCode}.apk")
        try {
            withContext(Dispatchers.IO) {
                dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
                val request = Request.Builder().url(BASE_URL + "ytune.apk").cacheControl(CacheControl.FORCE_NETWORK).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: remote.size
                    val digest = MessageDigest.getInstance("SHA-256")
                    val part = File(dir, target.name + ".part")
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var lastEmit = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (total > 0 && now - lastEmit > 250) {
                                    lastEmit = now
                                    _state.value = UpdateState.Downloading(remote, (done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (remote.sha256.isNotBlank() && !sha.equals(remote.sha256, ignoreCase = true)) {
                        part.delete()
                        throw IOException("Checksum mismatch (the release may have changed mid-download)")
                    }
                    target.delete()
                    if (!part.renameTo(target)) throw IOException("Couldn't save the update")
                }
            }
            _state.value = UpdateState.Ready(remote, target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update download failed", e)
            _state.value = UpdateState.Failed(e.message ?: "Download failed")
        }
    }

    /**
     * Starts the system install flow. The first time, Android asks the user to allow YTune to
     * install apps; after that each update is a single confirmation.
     */
    fun install(activity: Activity) {
        val ready = _state.value as? UpdateState.Ready ?: return
        val pm = activity.packageManager
        if (!pm.canRequestPackageInstalls()) {
            Graph.toast("Allow YTune to install updates, then tap Install again")
            activity.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        try {
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(activity.packageName)
                setSize(ready.file.length())
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                ready.file.inputStream().use { input ->
                    session.openWrite("ytune.apk", 0, ready.file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getActivity(
                    activity,
                    sessionId,
                    Intent(activity, MainActivity::class.java).setAction(MainActivity.ACTION_INSTALL_STATUS),
                    flags,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "install failed", e)
            Graph.toast("Couldn't start the installer: ${e.message}")
        }
    }

    /** Result of [install], delivered to MainActivity by the package installer. */
    fun onInstallStatus(activity: Activity, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { activity.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // we're being replaced
            PackageInstaller.STATUS_FAILURE_ABORTED -> Graph.toast("Update cancelled")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Graph.toast("Update failed: $msg")
            }
        }
    }

    private fun fetchRemote(): RemoteVersion {
        val request = Request.Builder()
            .url(BASE_URL + "version.json")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) throw IOException("No release published yet")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val text = response.body?.string() ?: throw IOException("Empty response")
            return AppJson.decodeFromString(RemoteVersion.serializer(), text)
        }
    }

    private fun existingDownload(remote: RemoteVersion): File? =
        File(dir, "ytune-${remote.versionCode}.apk").takeIf { it.exists() && it.length() > 0 }

    companion object {
        private const val TAG = "Updater"
        private const val KEY_LAST_CHECK = "lastCheck"
        private const val CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L
        val BASE_URL: String = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/latest/"
    }
}

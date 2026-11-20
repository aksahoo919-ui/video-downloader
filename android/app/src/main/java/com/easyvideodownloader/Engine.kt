package com.easyvideodownloader

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class Phase { STARTING, READY, FAILED }
enum class Tone { NORMAL, MUTED, ERROR, SUCCESS }

/** Everything the screen shows. */
data class UiState(
    val phase: Phase = Phase.STARTING,
    val busy: Boolean = false,          // downloads running
    val updating: Boolean = false,      // manual update check running
    val status: String = "Getting ready...",
    val tone: Tone = Tone.NORMAL,
    val detail: String = "",
    val progress: Int? = null,          // null = busy without a known percentage
    val engine: String = "",
    val lastFile: Uri? = null,
    val doneCount: Int = 0,             // goes up after a fully successful batch (clears the link box)
)

/** Runs the download engine (yt-dlp) and keeps the state that the screen and notification show. */
object Engine {
    private const val TAG = "Engine"
    private const val UPDATE_RETRY_GAP_MS = 30 * 60 * 1000L  // after a failed download, check at most this often
    private const val PROGRESS_TEMPLATE =
        "download:@@P|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|" +
            "%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s|%(info.title)s"

    val state = MutableStateFlow(UiState())
    val log = MutableStateFlow("")
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var initRunning = false
    @Volatile private var cancelled = false
    @Volatile private var currentProcessId: String? = null
    private var jobCounter = 0

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun set(block: (UiState) -> UiState) = state.update(block)

    fun say(text: String, tone: Tone = Tone.NORMAL) = set { it.copy(status = text, tone = tone) }

    fun appendLog(line: String) = log.update { (it + line + "\n").takeLast(60_000) }

    // ------------------------------------------------------------------
    // Setup: unpack the bundled Python, yt-dlp and FFmpeg (no internet needed)
    // ------------------------------------------------------------------
    fun init(context: Context) {
        app = context.applicationContext
        synchronized(lock) {
            if (initRunning || state.value.phase == Phase.READY) return
            initRunning = true
        }
        set { it.copy(phase = Phase.STARTING, status = "Getting ready...", tone = Tone.NORMAL, progress = null) }
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                refreshEngineVersion()
                set {
                    it.copy(phase = Phase.READY, status = "Ready. Paste a link and press Download.",
                        tone = Tone.NORMAL, progress = 0, detail = "")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "init failed", e)
                appendLog("Setup problem: $e")
                set {
                    it.copy(phase = Phase.FAILED, tone = Tone.ERROR, progress = 0,
                        status = "The app could not get ready. Press Try again.\n(${e.message ?: e})")
                }
            } finally {
                initRunning = false
            }
        }
    }

    private fun refreshEngineVersion() {
        val version = try {
            YoutubeDL.getInstance().versionName(app) ?: YoutubeDL.getInstance().version(app)
        } catch (e: Exception) {
            null
        }
        set {
            it.copy(engine = if (version.isNullOrBlank()) "Download engine: built-in version"
            else "Download engine: version $version")
        }
    }

    // ------------------------------------------------------------------
    // Starting and stopping
    // ------------------------------------------------------------------
    /** Marks the engine busy. Returns false if it cannot start right now. */
    fun tryStart(): Boolean {
        synchronized(lock) {
            val s = state.value
            if (s.busy || s.updating || s.phase != Phase.READY) return false
            cancelled = false
            set { it.copy(busy = true, status = "Starting...", tone = Tone.NORMAL, progress = null, detail = "") }
            return true
        }
    }

    fun abortStart(message: String) =
        set { it.copy(busy = false, status = message, tone = Tone.ERROR, progress = 0, detail = "") }

    fun cancel() {
        if (!state.value.busy) return
        cancelled = true
        say("Stopping...", Tone.MUTED)
        currentProcessId?.let {
            try {
                YoutubeDL.getInstance().destroyProcessById(it)
            } catch (e: Exception) {
                Log.w(TAG, "could not stop process", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Downloading (called from DownloadService on a background thread)
    // ------------------------------------------------------------------
    private data class Result(val ok: Boolean, val message: String = "", val updateMayHelp: Boolean = false)

    fun runBatch(urls: List<String>, audio: Boolean, choice: Choice) {
        var succeeded = 0
        val failed = mutableListOf<Pair<String, String>>()
        try {
            for ((index, url) in urls.withIndex()) {
                if (cancelled) break
                val prefix = if (urls.size > 1) "(${index + 1} of ${urls.size}) " else ""
                var result = downloadOne(url, audio, choice, prefix)
                if (!result.ok && !cancelled && updateAfterError(result.updateMayHelp, prefix)) {
                    say(prefix + "Trying again with the updated download engine...")
                    result = downloadOne(url, audio, choice, prefix)
                }
                if (cancelled) break
                if (result.ok) {
                    succeeded++
                } else {
                    failed += url to result.message
                    appendLog("FAILED: $url\n   -> ${result.message}")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "batch failed", e)
            appendLog("Unexpected problem: $e")
            if (failed.isEmpty() && succeeded == 0) failed += "" to "Something went wrong (${e.message ?: e})."
        }
        finishBatch(succeeded, failed)
    }

    private fun finishBatch(succeeded: Int, failed: List<Pair<String, String>>) {
        val total = succeeded + failed.size
        val where = "Downloads › ${Saver.FOLDER}"
        set {
            val base = it.copy(busy = false, detail = "")
            when {
                cancelled -> base.copy(status = "Download stopped.", tone = Tone.MUTED, progress = 0)
                failed.isEmpty() -> base.copy(
                    status = if (total == 1) "Done! Your download is saved in $where."
                    else "Done! All $total downloads are saved in $where.",
                    tone = Tone.SUCCESS, progress = 100, doneCount = it.doneCount + 1,
                )
                succeeded > 0 -> base.copy(
                    status = "$succeeded of $total downloaded. ${failed.size} could not be downloaded - " +
                        "tap Show details to see why.",
                    tone = Tone.ERROR, progress = 100,
                )
                else -> base.copy(status = "Could not download: " + failed[0].second, tone = Tone.ERROR, progress = 0)
            }
        }
    }

    private fun buildRequest(url: String, audio: Boolean, choice: Choice, folder: File) =
        YoutubeDLRequest(url).apply {
            addOption("--newline")
            addOption("--ignore-config")
            addOption("--no-playlist")
            addOption("--windows-filenames")  // keeps names valid on every kind of phone storage
            addOption("--no-mtime")
            addOption("--concurrent-fragments", 4)
            addOption("--retries", 5)
            addOption("--fragment-retries", 3)
            addOption("--socket-timeout", 30)
            addOption("--progress-template", PROGRESS_TEMPLATE)
            addOption("-P", folder.absolutePath)
            addOption("-o", "%(title).150B.%(ext)s")
            if (audio) {
                val codec = choice.codec ?: "mp3"
                addOption("-f", if (codec == "m4a") "bestaudio[ext=m4a]/bestaudio/best" else "bestaudio/best")
                addOption("-x")
                addOption("--audio-format", codec)
                choice.bitrate?.let { addOption("--audio-quality", it + "K") }
            } else {
                // Highest resolution first (optionally capped), then the most widely playable codecs.
                val res = choice.height?.let { "res:$it" } ?: "res"
                addOption("-f", "bestvideo*+bestaudio/best")
                addOption("-S", "$res,vcodec:h264,acodec:aac")
                addOption("--merge-output-format", "mp4")
            }
        }

    private fun downloadOne(url: String, audio: Boolean, choice: Choice, prefix: String): Result {
        set { it.copy(status = prefix + "Looking up the video...", tone = Tone.NORMAL, progress = null, detail = "") }
        appendLog("--- $url")
        val workDir = File(app.cacheDir, "downloads/${System.currentTimeMillis()}").apply { mkdirs() }
        val processId = synchronized(lock) { "job-${++jobCounter}" }
        val parser = LineParser(prefix, audio)
        currentProcessId = processId
        try {
            YoutubeDL.getInstance().execute(buildRequest(url, audio, choice, workDir), processId, true) { _, _, line ->
                parser.feed(line)
            }
            if (cancelled) return Result(false, "Download stopped.")

            val files = workDir.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && ".temp." !in it.name }
                .toList()
            if (files.isEmpty()) return Result(false, "The download finished, but no file was found.")

            set { it.copy(status = prefix + "Saving to your Downloads folder...", progress = null, detail = "") }
            for (file in files) {
                val uri = try {
                    Saver.save(app, file)
                } catch (e: Exception) {
                    Log.e(TAG, "save failed", e)
                    appendLog("Could not save ${file.name}: $e")
                    return Result(false, "Could not save the file to your Downloads folder (${e.message}).")
                }
                appendLog("Saved: ${Saver.FOLDER}/${file.name}")
                if (uri != null) set { it.copy(lastFile = uri) }
            }
            return Result(true)
        } catch (e: YoutubeDL.CanceledException) {
            return Result(false, "Download stopped.")
        } catch (e: InterruptedException) {
            return Result(false, "Download stopped.")
        } catch (e: YoutubeDLException) {
            if (cancelled) return Result(false, "Download stopped.")
            val raw = parser.lastError.ifEmpty { e.message?.lines()?.lastOrNull { l -> l.isNotBlank() } ?: "" }
            if (raw.isBlank()) return Result(false, "Something went wrong with this download.", updateMayHelp = true)
            return Result(false, Errors.friendly(raw), Errors.updateMightHelp(raw))
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            appendLog("Problem: $e")
            return Result(false, "Something went wrong with this download (${e.message ?: e}).")
        } finally {
            currentProcessId = null
            workDir.deleteRecursively()
        }
    }

    /** Turns yt-dlp's output lines into the status text, progress bar and details log. */
    private class LineParser(val prefix: String, val audio: Boolean) {
        var lastError = ""
        private var part = 0          // which stream we are on (video = 1, sound = 2)
        private var lastFraction = 0.0

        fun feed(rawLine: String) {
            val line = rawLine.trim()
            if (line.isEmpty()) return
            if (line.startsWith("@@P|")) {
                val f = line.split("|", limit = 7)
                if (f.size < 7) return
                val done = Format.number(f[1])
                val total = Format.number(f[2]) ?: Format.number(f[3])
                val speed = Format.number(f[4])
                val eta = Format.number(f[5])
                val title = f[6]
                val fraction = if (done != null && total != null && total > 0) done / total else null
                if (part == 0 || (fraction != null && fraction < lastFraction - 0.5)) part++
                if (fraction != null) lastFraction = fraction
                val doing = when {
                    audio -> "Downloading audio"
                    part <= 1 -> "Downloading video"
                    else -> "Downloading sound"
                }
                val bits = mutableListOf<String>()
                if (fraction != null) bits += "${(fraction * 100).toInt()}%"
                if (total != null) bits += "${Format.size(done)} of ${Format.size(total)}"
                else if (done != null) bits += Format.size(done)
                if (speed != null) bits += Format.size(speed) + "/s"
                if (eta != null && fraction != null && fraction < 1) bits += Format.time(eta) + " left"
                Engine.set {
                    it.copy(
                        status = "$prefix$doing: ${title.take(70)}", tone = Tone.NORMAL,
                        progress = fraction?.let { fr -> (fr * 100).toInt().coerceIn(0, 100) },
                        detail = bits.joinToString("   |   "),
                    )
                }
                return
            }
            appendLog(line)
            when {
                line.startsWith("ERROR:") -> lastError = line
                line.startsWith("[Merger]") || line.startsWith("[VideoRemuxer]") ||
                    line.startsWith("[VideoConvertor]") || line.startsWith("[FixupM") -> Engine.set {
                    it.copy(status = prefix + "Almost done: putting video and sound together...",
                        progress = null, detail = "")
                }
                line.startsWith("[ExtractAudio]") -> Engine.set {
                    it.copy(status = prefix + "Almost done: converting the audio...", progress = null, detail = "")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Updates: only after a failed download (or when the person asks)
    // ------------------------------------------------------------------
    private fun updateAfterError(updateMayHelp: Boolean, prefix: String): Boolean {
        if (!updateMayHelp) return false
        val prefs = prefs(app)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_update_check", 0L) < UPDATE_RETRY_GAP_MS) return false
        set {
            it.copy(status = prefix + "That did not work. Checking for a newer download engine...",
                tone = Tone.NORMAL, progress = null, detail = "")
        }
        prefs.edit().putLong("last_update_check", now).apply()
        return try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(app)
            appendLog("[update] result: $status")
            if (status == YoutubeDL.UpdateStatus.DONE) {
                refreshEngineVersion()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            appendLog("[update] failed: ${e.message}")
            false
        }
    }

    fun manualUpdate() {
        synchronized(lock) {
            val s = state.value
            if (s.busy || s.updating || s.phase != Phase.READY) return
            set { it.copy(updating = true, status = "Checking for updates...", tone = Tone.NORMAL, progress = null) }
        }
        scope.launch {
            val message = try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(app)
                prefs(app).edit().putLong("last_update_check", System.currentTimeMillis()).apply()
                appendLog("[update] result: $status")
                refreshEngineVersion()
                if (status == YoutubeDL.UpdateStatus.DONE) "Updated the download engine to the newest version."
                else "You already have the newest download engine."
            } catch (e: Exception) {
                appendLog("[update] failed: ${e.message}")
                "Could not check for updates right now. Please check your internet connection."
            }
            set {
                it.copy(updating = false, status = "Ready. Paste a link and press Download.",
                    tone = Tone.NORMAL, progress = 0, detail = "")
            }
            messages.emit(message)
        }
    }
}

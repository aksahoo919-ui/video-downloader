package com.easyvideodownloader

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.easyvideodownloader.databinding.ActivityMainBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { Engine.prefs(this) }
    private var audio = false
    private var qualityIndex = 0
    private var prefilled = false
    private var renderedDoneCount = -1
    private var pendingUrls: List<String> = emptyList()

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val askStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startDownload(pendingUrls)
            else Engine.say("The app needs permission to save files to your Downloads folder.", Tone.ERROR)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        Engine.init(this)

        audio = prefs.getString("mode", "video") == "audio"
        b.modeGroup.check(if (audio) R.id.audio_button else R.id.video_button)
        setupQuality()
        b.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                audio = id == R.id.audio_button
                prefs.edit().putString("mode", if (audio) "audio" else "video").apply()
                setupQuality()
            }
        }
        b.qualityInput.setOnItemClickListener { _, _, position, _ ->
            qualityIndex = position
            prefs.edit().putInt(qualityKey(), position).apply()
        }

        b.pasteButton.setOnClickListener { pasteFromClipboard() }
        b.clearButton.setOnClickListener { b.links.setText("") }
        b.downloadButton.setOnClickListener { onDownloadClicked() }
        b.openFileButton.setOnClickListener { openLastFile() }
        b.openDownloadsButton.setOnClickListener { openDownloadsFolder() }
        b.detailsButton.setOnClickListener {
            val show = b.logText.visibility != View.VISIBLE
            b.logText.visibility = if (show) View.VISIBLE else View.GONE
            b.detailsButton.text = if (show) "Hide details" else "Show details"
        }
        b.updateButton.setOnClickListener { Engine.manualUpdate() }

        handleShare(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Engine.state.collect { render(it) } }
                launch { Engine.log.collect { b.logText.text = it } }
                launch {
                    Engine.messages.collect { message ->
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Like the Windows app: if a link is already copied, put it in the box straight away.
        if (hasFocus && !prefilled && b.links.text.isNullOrBlank()) {
            prefilled = true
            val text = clipboardText()
            if (text != null && URL_START.containsMatchIn(text)) b.links.setText(text)
        }
    }

    // ------------------------------------------------------------------

    private fun qualityKey() = if (audio) "quality_audio" else "quality_video"

    private fun setupQuality() {
        val labels = Choices.forMode(audio).map { it.label }.toTypedArray()
        qualityIndex = prefs.getInt(qualityKey(), Choices.defaultIndex(audio)).coerceIn(0, labels.size - 1)
        b.qualityInput.setSimpleItems(labels)
        b.qualityInput.setText(labels[qualityIndex], false)
    }

    private fun render(s: UiState) {
        val ready = s.phase == Phase.READY
        val idle = ready && !s.busy && !s.updating

        b.statusText.text = s.status
        b.statusText.setTextColor(
            when (s.tone) {
                Tone.ERROR -> ContextCompat.getColor(this, R.color.accent)
                Tone.SUCCESS -> ContextCompat.getColor(this, R.color.ok_green)
                Tone.MUTED -> ContextCompat.getColor(this, R.color.muted)
                Tone.NORMAL -> MaterialColors.getColor(b.statusText, com.google.android.material.R.attr.colorOnSurface)
            }
        )

        val p = s.progress
        if (p == null) {
            if (!b.progress.isIndeterminate) {
                b.progress.visibility = View.INVISIBLE
                b.progress.isIndeterminate = true
                b.progress.visibility = View.VISIBLE
            }
        } else {
            if (b.progress.isIndeterminate) {
                b.progress.visibility = View.INVISIBLE
                b.progress.isIndeterminate = false
                b.progress.visibility = View.VISIBLE
            }
            b.progress.setProgressCompat(p, true)
        }

        b.detailText.text = s.detail
        b.detailText.visibility = if (s.detail.isEmpty()) View.GONE else View.VISIBLE

        when {
            s.phase == Phase.FAILED -> {
                b.downloadButton.text = "Try again"
                b.downloadButton.isEnabled = true
            }
            s.busy -> {
                b.downloadButton.text = "Stop"
                b.downloadButton.isEnabled = true
            }
            else -> {
                b.downloadButton.text = "Download"
                b.downloadButton.isEnabled = idle
            }
        }
        for (v in listOf(b.links, b.pasteButton, b.clearButton, b.videoButton, b.audioButton, b.qualityInput)) {
            v.isEnabled = idle
        }
        b.updateButton.isEnabled = idle
        b.openFileButton.visibility = if (s.lastFile != null && !s.busy) View.VISIBLE else View.INVISIBLE
        b.engineText.text = s.engine

        if (renderedDoneCount < 0) {
            renderedDoneCount = s.doneCount
        } else if (s.doneCount > renderedDoneCount) {
            renderedDoneCount = s.doneCount
            b.links.setText("")
        }
    }

    private fun onDownloadClicked() {
        val s = Engine.state.value
        when {
            s.phase == Phase.FAILED -> { Engine.init(this); return }
            s.busy -> { Engine.cancel(); return }
            s.phase != Phase.READY || s.updating -> return
        }
        val urls = b.links.text.toString().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
        if (urls.isEmpty()) {
            Engine.say("Please paste a video link first (step 1).", Tone.ERROR)
            return
        }
        urls.firstOrNull { !URL_START.matches(it) }?.let {
            Engine.say("This does not look like a link: ${it.take(60)}\nA link starts with https://", Tone.ERROR)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingUrls = urls
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("asked_notifications", false)
        ) {
            // Only to show progress while the app is in the background; downloads work either way.
            prefs.edit().putBoolean("asked_notifications", true).apply()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        startDownload(urls)
    }

    private fun startDownload(urls: List<String>) {
        if (urls.isEmpty() || !Engine.tryStart()) return
        try {
            DownloadService.start(this, ArrayList(urls), audio, qualityIndex)
        } catch (e: Exception) {
            Engine.abortStart("Could not start the download (${e.message ?: e}).")
        }
    }

    private fun clipboardText(): String? = try {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
    } catch (e: Exception) {
        null
    }

    private fun pasteFromClipboard() {
        val text = clipboardText()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Nothing is copied yet. Copy a video link first.", Toast.LENGTH_SHORT).show()
            return
        }
        addLinks(text)
    }

    private fun addLinks(text: String) {
        val existing = b.links.text.toString().trim()
        b.links.setText(if (existing.isEmpty()) text else existing + "\n" + text)
        b.links.setSelection(b.links.text?.length ?: 0)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val urls = Regex("https?://\\S+").findAll(text).map { it.value }.toList()
        if (urls.isEmpty()) return
        prefilled = true
        addLinks(urls.joinToString("\n"))
        intent.action = null  // don't add the same link again when the screen is recreated
    }

    private fun openLastFile() {
        val uri = Engine.state.value.lastFile ?: return
        val type = contentResolver.getType(uri) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(view, "Open with"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app on this phone can open this file.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDownloadsFolder() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Open your Files app and look in Downloads › ${Saver.FOLDER}.", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private val URL_START = Regex("(?i)^https?://\\S+$")
    }
}

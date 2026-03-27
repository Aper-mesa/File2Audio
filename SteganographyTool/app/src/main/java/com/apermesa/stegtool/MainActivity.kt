package com.apermesa.stegtool

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnEmbedMedia: Button
    private lateinit var btnEmbedFile: Button
    private lateinit var btnExtract: Button

    // ── Activity Result Launchers ────────────────────────────────────────────

    private val pickMediaLauncher =
        registerForActivityResult(PickMultipleVisualMedia()) { uris ->
            if (uris.isEmpty()) { showStatus(getString(R.string.status_cancelled)); return@registerForActivityResult }
            runEmbed(uris)
        }

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isEmpty()) { showStatus(getString(R.string.status_cancelled)); return@registerForActivityResult }
            runEmbed(uris)
        }

    private val pickMp3Launcher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) { showStatus(getString(R.string.status_cancelled)); return@registerForActivityResult }
            runExtract(uri)
        }

    // ── Permission launcher (API ≤ 28 only) ─────────────────────────────────

    /** What to do after storage permission is granted. */
    private var pendingAction: (() -> Unit)? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                pendingAction?.invoke()
            } else {
                showStatus("Permission denied.")
            }
            pendingAction = null
        }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnEmbedMedia = findViewById(R.id.btnEmbedMedia)
        btnEmbedFile = findViewById(R.id.btnEmbedFile)
        btnExtract = findViewById(R.id.btnExtract)

        btnEmbedMedia.setOnClickListener {
            withPermission {
                pickMediaLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
            }
        }

        btnEmbedFile.setOnClickListener {
            withPermission {
                pickFileLauncher.launch("*/*")
            }
        }

        btnExtract.setOnClickListener {
            withPermission {
                pickMp3Launcher.launch("audio/mpeg")
            }
        }
    }

    // ── Core operations ──────────────────────────────────────────────────────

    private fun runEmbed(uris: List<android.net.Uri>) {
        setBusy(true)
        showStatus(getString(R.string.status_processing_multi, uris.size))
        lifecycleScope.launch {
            try {
                val outFile = withContext(Dispatchers.IO) {
                    AudioEmbedder.embedFiles(this@MainActivity, uris)
                }
                showStatus(getString(R.string.status_done_embed, outFile.absolutePath))
            } catch (e: Exception) {
                showStatus(getString(R.string.status_error, e.message ?: e.javaClass.simpleName))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun runExtract(uri: android.net.Uri) {
        setBusy(true)
        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    AudioEmbedder.extractFiles(this@MainActivity, uri)
                }
                if (files.isEmpty()) {
                    showStatus("No embedded files found (or not embedded with this tool).")
                } else {
                    val dir = files.first().parent ?: ""
                    showStatus(getString(R.string.status_done_extract, files.size, dir))
                    openFolder()
                }
            } catch (e: Exception) {
                showStatus(getString(R.string.status_error, e.message ?: e.javaClass.simpleName))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun openFolder() {
        // Opens Downloads/File2Audio in the system file manager.
        // Uses the ExternalStorage documents provider URI which AOSP Files and
        // most OEM file managers understand.  Falls through silently if no
        // compatible app is installed.
        val uri = android.net.Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3ADownloads%2FFile2Audio"
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No file manager handles this — status text already shows the path.
        }
    }

    // ── Permission helper ────────────────────────────────────────────────────

    /**
     * On API ≤ 28, request READ/WRITE_EXTERNAL_STORAGE before proceeding.
     * On API 29+, SAF URIs + getExternalFilesDir are permission-free, so run directly.
     */
    private fun withPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            action()
            return
        }
        val needed = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            action()
        } else {
            pendingAction = action
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    // ── UI helpers ───────────────────────────────────────────────────────────

    private fun showStatus(msg: String) {
        tvStatus.text = msg
    }

    private fun setBusy(busy: Boolean) {
        btnEmbedMedia.isEnabled = !busy
        btnEmbedFile.isEnabled = !busy
        btnExtract.isEnabled = !busy
        if (busy) showStatus(getString(R.string.status_processing))
    }
}

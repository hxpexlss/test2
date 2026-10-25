package de.clipdl

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import de.clipdl.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var processId: String? = null
    private var lastSavedUri: Uri? = null

    private val timeRegex = Regex("""^(\d+:)?(\d{1,2}:)?\d+(\.\d+)?$""")
    private val urlRegex = Regex("""https?://\S+""")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handleShareIntent(intent)

        binding.buttonDownload.setOnClickListener { startDownload() }
        binding.buttonCancel.setOnClickListener { cancelDownload() }
        binding.buttonUpdate.setOnClickListener { updateYtDlp() }
        binding.buttonOpen.setOnClickListener { openLastFile() }

        lifecycleScope.launch {
            setStatus(getString(R.string.status_init))
            try {
                (application as ClipApp).ready.await()
                setStatus(getString(R.string.status_ready))
            } catch (e: Exception) {
                setStatus(getString(R.string.status_init_failed, e.message))
            }
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        urlRegex.find(text)?.let { binding.inputUrl.setText(it.value) }
    }

    private fun startDownload() {
        val url = binding.inputUrl.text.toString().trim()
        val start = binding.inputStart.text.toString().trim()
        val end = binding.inputEnd.text.toString().trim()

        if (!urlRegex.matches(url)) {
            binding.inputUrlLayout.error = getString(R.string.error_url)
            return
        }
        binding.inputUrlLayout.error = null
        if (start.isNotEmpty() && !timeRegex.matches(start)) {
            binding.inputStartLayout.error = getString(R.string.error_time)
            return
        }
        binding.inputStartLayout.error = null
        if (end.isNotEmpty() && !timeRegex.matches(end)) {
            binding.inputEndLayout.error = getString(R.string.error_time)
            return
        }
        binding.inputEndLayout.error = null
        if (start.isNotEmpty() && end.isNotEmpty() && toSeconds(end) <= toSeconds(start)) {
            binding.inputEndLayout.error = getString(R.string.error_order)
            return
        }

        val bitrate = when (binding.groupQuality.checkedButtonId) {
            R.id.quality128 -> "128K"
            R.id.quality192 -> "192K"
            else -> "320K"
        }

        val workDir = File(cacheDir, "dl").apply {
            deleteRecursively()
            mkdirs()
        }

        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("-f", "bestaudio/best")
            addOption("-x")
            addOption("--audio-format", "mp3")
            addOption("--audio-quality", bitrate)
            addOption("--embed-metadata")
            addOption("--newline")
            if (start.isNotEmpty() || end.isNotEmpty()) {
                val s = start.ifEmpty { "0" }
                val e = end.ifEmpty { "inf" }
                addOption("--download-sections", "*$s-$e")
            }
            val suffix = if (start.isNotEmpty() || end.isNotEmpty()) {
                " [${start.ifEmpty { "0" }}-${end.ifEmpty { "Ende" }}]".replace(':', '.')
            } else ""
            addOption("-o", "${workDir.absolutePath}/%(title).150B$suffix.%(ext)s")
        }

        val id = UUID.randomUUID().toString()
        processId = id
        setRunning(true)
        binding.log.text = ""
        lastSavedUri = null
        binding.buttonOpen.visibility = View.GONE

        lifecycleScope.launch {
            try {
                (application as ClipApp).ready.await()
                setStatus(getString(R.string.status_downloading))
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request, id) { progress, eta, line ->
                        runOnUiThread {
                            if (progress > 0) {
                                binding.progress.isIndeterminate = false
                                binding.progress.setProgressCompat(progress.toInt(), true)
                            }
                            if (line.isNotBlank()) appendLog(line)
                            if (eta > 0) {
                                setStatus(getString(R.string.status_progress, progress.toInt(), eta))
                            }
                        }
                    }
                }
                val mp3 = workDir.listFiles()?.filter { it.extension.equals("mp3", true) }
                    ?.maxByOrNull { it.lastModified() }
                    ?: throw IllegalStateException(getString(R.string.error_no_file))
                setStatus(getString(R.string.status_saving))
                val uri = withContext(Dispatchers.IO) { saveToMusic(mp3) }
                mp3.delete()
                lastSavedUri = uri
                binding.buttonOpen.visibility = View.VISIBLE
                setStatus(getString(R.string.status_done, mp3.name))
            } catch (e: YoutubeDL.CanceledException) {
                setStatus(getString(R.string.status_canceled))
            } catch (e: Exception) {
                if (processId == null) {
                    setStatus(getString(R.string.status_canceled))
                } else {
                    appendLog(e.message ?: e.toString())
                    setStatus(getString(R.string.status_error))
                }
            } finally {
                processId = null
                setRunning(false)
            }
        }
    }

    private fun cancelDownload() {
        val id = processId ?: return
        processId = null
        YoutubeDL.getInstance().destroyProcessById(id)
    }

    private fun updateYtDlp() {
        setRunning(true)
        setStatus(getString(R.string.status_updating))
        lifecycleScope.launch {
            try {
                (application as ClipApp).ready.await()
                val status = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(this@MainActivity)
                }
                val version = YoutubeDL.getInstance().versionName(this@MainActivity)
                setStatus(getString(R.string.status_updated, status?.name ?: "?", version ?: "?"))
            } catch (e: Exception) {
                setStatus(getString(R.string.status_update_failed, e.message))
            } finally {
                setRunning(false)
            }
        }
    }

    /** Kopiert die Datei nach Musik/ClipDL und gibt die Uri zurück. */
    private fun saveToMusic(file: File): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/ClipDL")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values)
                ?: throw IllegalStateException(getString(R.string.error_save))
            contentResolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "ClipDL"
            ).apply { mkdirs() }
            val target = File(dir, file.name)
            file.copyTo(target, overwrite = true)
            android.media.MediaScannerConnection.scanFile(
                this, arrayOf(target.absolutePath), arrayOf("audio/mpeg"), null
            )
            return FileProvider.getUriForFile(this, "$packageName.files", target)
        }
    }

    private fun openLastFile() {
        val uri = lastSavedUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/mpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            setStatus(getString(R.string.error_no_player))
        }
    }

    private fun setRunning(running: Boolean) {
        binding.buttonDownload.isEnabled = !running
        binding.buttonUpdate.isEnabled = !running
        binding.buttonCancel.visibility = if (running) View.VISIBLE else View.GONE
        binding.progress.visibility = if (running) View.VISIBLE else View.INVISIBLE
        binding.progress.isIndeterminate = true
        if (running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setStatus(text: String) {
        binding.status.text = text
    }

    private fun appendLog(line: String) {
        binding.log.append(line.trimEnd() + "\n")
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toSeconds(t: String): Double =
        t.split(':').fold(0.0) { acc, part -> acc * 60 + part.toDouble() }
}

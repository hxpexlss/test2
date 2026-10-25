package de.clipdl

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ClipApp : Application() {

    /** Wird abgeschlossen, sobald yt-dlp und ffmpeg entpackt und bereit sind. */
    val ready = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(this@ClipApp)
                FFmpeg.getInstance().init(this@ClipApp)
                ready.complete(Unit)
            } catch (e: Exception) {
                ready.completeExceptionally(e)
            }
        }
    }
}

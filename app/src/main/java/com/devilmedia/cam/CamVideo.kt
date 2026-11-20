package com.devilmedia.cam

import android.content.Context
import android.net.Uri
import android.widget.FrameLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** One shared VLC engine for the whole app. VLC plays CCTV streams that other players reject. */
object Vlc {
    @Volatile private var lib: LibVLC? = null
    fun get(context: Context): LibVLC = lib ?: synchronized(this) {
        lib ?: LibVLC(context.applicationContext, arrayListOf("--no-stats", "--no-sub-autodetect-file")).also { lib = it }
    }
}

/** A video picture that plays a recorder stream (live or recorded). */
class CamVideo(context: Context) : FrameLayout(context) {
    private val layout = VLCVideoLayout(context)
    private var mp: MediaPlayer? = null
    private var sawPicture = false

    var onFirstFrame: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        addView(layout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isFocusable = false
        isClickable = false
    }

    val isActive: Boolean get() = mp != null

    /** How far into the stream we are, in milliseconds. */
    val positionMs: Long get() = mp?.time?.coerceAtLeast(0L) ?: 0L

    var paused: Boolean
        get() = mp?.isPlaying == false
        set(v) {
            val p = mp ?: return
            if (v) p.pause() else p.play()
        }

    fun play(url: String, user: String, password: String, live: Boolean) {
        stop()
        sawPicture = false
        val lib = Vlc.get(context)
        val player = MediaPlayer(lib)
        player.attachViews(layout, null, false, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
        val media = Media(lib, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":rtsp-tcp")
            addOption(":rtsp-user=$user")
            addOption(":rtsp-pwd=$password")
            addOption(":network-caching=${if (live) 400 else 1500}")
            addOption(":no-audio")
        }
        player.media = media
        media.release()
        player.setEventListener { e ->
            if (mp !== player) return@setEventListener
            when (e.type) {
                MediaPlayer.Event.Vout -> if (e.voutCount > 0 && !sawPicture) {
                    sawPicture = true
                    onFirstFrame?.invoke()
                }
                MediaPlayer.Event.EncounteredError -> onError?.invoke("the recorder didn't send video")
                MediaPlayer.Event.EndReached -> onError?.invoke(if (live) "the video stopped" else "end of recording")
            }
        }
        mp = player
        player.play()
    }

    fun stop() {
        val p = mp ?: return
        mp = null
        try {
            p.setEventListener(null)
            p.stop()
            p.detachViews()
            p.release()
        } catch (_: Exception) {
        }
    }
}

package com.devilmedia.cam

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/** Builds video players for recorder streams. */
object Players {

    @OptIn(UnstableApi::class)
    fun create(context: Context, url: String, live: Boolean): ExoPlayer {
        // Small buffers keep live video close to real time.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (live) 500 else 2000,
                if (live) 2000 else 15000,
                if (live) 250 else 1000,
                if (live) 250 else 1000
            )
            .build()
        val player = ExoPlayer.Builder(context).setLoadControl(load).build()
        val source = RtspMediaSource.Factory()
            // TCP is the most reliable way through home Wi-Fi.
            .setForceUseRtpTcp(true)
            .setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
        player.setMediaSource(source)
        player.volume = 0f
        player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        player.playWhenReady = true
        player.prepare()
        return player
    }
}

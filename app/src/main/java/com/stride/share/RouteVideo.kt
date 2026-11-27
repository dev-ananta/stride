package com.stride.share

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.view.Surface
import java.io.File

/**
 * Renders a short vertical MP4 for Instagram Reels / Stories: the route draws itself while the
 * numbers count up, then holds. No audio track — add music inside Instagram.
 *
 * Frames are drawn with the same [ShareCard.draw] used for the PNG, straight onto the H.264
 * encoder's input surface, and given exact 30 fps timestamps.
 */
object RouteVideo {
    private const val FPS = 30

    fun render(spec: CardSpec, data: CardData, out: File, drawSeconds: Float = 4f, holdSeconds: Float = 2.5f, onProgress: (Float) -> Unit = {}) {
        val w = spec.size.w; val h = spec.size.h
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface: Surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var written = 0

        fun drain(endOfStream: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) {
                            // Exact timestamps regardless of how fast we rendered.
                            info.presentationTimeUs = written * 1_000_000L / FPS
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                            written++
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            val drawFrames = (drawSeconds * FPS).toInt()
            val total = drawFrames + (holdSeconds * FPS).toInt()
            for (i in 0 until total) {
                val canvas = try { surface.lockHardwareCanvas() } catch (_: Exception) { surface.lockCanvas(null) }
                try {
                    ShareCard.draw(canvas, spec, data, (i.toFloat() / drawFrames).coerceAtMost(1f), opaque = true)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drain(false)
                Thread.sleep(6) // let the encoder keep up so no frames get dropped
                onProgress((i + 1f) / total)
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            try { codec.stop() } catch (_: Exception) { }
            codec.release()
            surface.release()
            try { if (track >= 0) muxer.stop() } catch (_: Exception) { }
            muxer.release()
        }
        check(written > 0) { "The video encoder produced no frames" }
    }
}

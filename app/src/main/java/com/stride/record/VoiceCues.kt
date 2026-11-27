package com.stride.record

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.stride.domain.Fmt
import com.stride.domain.Sport
import com.stride.domain.Units
import java.util.Locale

/**
 * Spoken split announcements using the phone's built-in text-to-speech (works offline).
 * Music is ducked while speaking, then restored.
 */
class VoiceCues(ctx: Context) {
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ready = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .build()

    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { audio.abandonAudioFocusRequest(focus) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { audio.abandonAudioFocusRequest(focus) }
        })
    }

    fun say(text: String) {
        if (!ready) return
        audio.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "cue-${System.nanoTime()}")
    }

    fun split(index: Int, splitMs: Long, totalMs: Long, distanceM: Double, sport: Sport, units: Units) {
        val unitWord = if (units == Units.METRIC) "Kilometre" else "Mile"
        val splitSpeed = Fmt.splitMeters(units) / (splitMs / 1000.0).coerceAtLeast(1.0)
        val avgSpeed = distanceM / (totalMs / 1000.0).coerceAtLeast(1.0)
        val text = if (sport.usesPace) {
            "$unitWord $index. Split pace ${spokenPace(splitSpeed, units)}. Average ${spokenPace(avgSpeed, units)}."
        } else {
            "$unitWord $index. Split ${spokenSpeed(splitSpeed, units)}. Average ${spokenSpeed(avgSpeed, units)}."
        }
        say(text)
    }

    private fun spokenPace(speed: Double, u: Units): String {
        val sec = (Fmt.splitMeters(u) / speed).toLong()
        return "${sec / 60} ${if (sec / 60 == 1L) "minute" else "minutes"} ${sec % 60}"
    }

    private fun spokenSpeed(speed: Double, u: Units): String =
        String.format(Locale.US, "%.1f %s", if (u == Units.METRIC) speed * 3.6 else speed * 2.236936,
            if (u == Units.METRIC) "kilometres per hour" else "miles per hour")

    fun shutdown() { tts.stop(); tts.shutdown(); audio.abandonAudioFocusRequest(focus) }
}

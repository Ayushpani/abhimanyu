package dev.abhimanyu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Earcons from the design: rising = listening, falling = heard you, double = done,
 * low buzz = failed. Each has a matching vibration so it can be felt in a pocket.
 */
class Tones(context: Context) {

    private val vibrator = (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val rising = sweep(listOf(Seg(620.0, 880.0, 110)))
    private val falling = sweep(listOf(Seg(880.0, 620.0, 110)))
    private val done = sweep(listOf(Seg(784.0, 784.0, 70), Seg(0.0, 0.0, 50), Seg(1046.0, 1046.0, 90)))
    private val failed = sweep(listOf(Seg(180.0, 160.0, 260)), amplitude = 0.35)

    fun listening() = play(rising, longArrayOf(0, 18))
    fun heard() = play(falling, null)
    fun done() = play(done, longArrayOf(0, 15, 60, 15))
    fun failed() = play(failed, longArrayOf(0, 120))

    private fun play(pcm: ShortArray, vibration: LongArray?) {
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { t.release() }
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        }
        if (vibration != null && vibrator.hasVibrator()) {
            runCatching { vibrator.vibrate(VibrationEffect.createWaveform(vibration, -1)) }
        }
    }

    private data class Seg(val fromHz: Double, val toHz: Double, val ms: Int)

    private fun sweep(segs: List<Seg>, amplitude: Double = 0.25): ShortArray {
        val total = segs.sumOf { it.ms * RATE / 1000 }
        val out = ShortArray(total)
        var idx = 0
        var phase = 0.0
        for (s in segs) {
            val n = s.ms * RATE / 1000
            for (i in 0 until n) {
                val f = s.fromHz + (s.toHz - s.fromHz) * i / n
                phase += 2 * PI * f / RATE
                // 8 ms fade in/out to avoid clicks
                val edge = min(1.0, min(i, n - i) / (RATE * 0.008))
                out[idx++] = if (f == 0.0) 0.toShort() else (sin(phase) * amplitude * edge * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    companion object { private const val RATE = 24000 }
}

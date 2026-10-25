package de.oventa.tvlauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Dezente UI-Klaenge. Die Toene werden beim ersten Start synthetisiert
 * (keine fremden Audiodateien) und im Cache als WAV abgelegt.
 * Geteilt von Overlay und Activity (gleicher Prozess).
 */
object Sounds {
    @Volatile var enabled = true

    private const val RATE = 44100
    private const val VERSION = 1
    private const val TICK = 0
    private const val SELECT = 1
    private const val OPEN = 2
    private const val CLOSE = 3

    private var pool: SoundPool? = null
    private val ids = IntArray(4)
    private val ready = java.util.Collections.synchronizedSet(HashSet<Int>())

    fun init(ctx: Context) {
        synchronized(this) {
            if (pool != null) return
            val p = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                ).build()
            pool = p
            p.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) ready.add(sampleId) }
            val app = ctx.applicationContext
            Thread {
                try {
                    val gens = listOf(::genTick, ::genSelect, ::genOpen, ::genClose)
                    gens.forEachIndexed { i, gen ->
                        val f = File(app.cacheDir, "ui_${i}_v$VERSION.wav")
                        if (!f.exists()) f.writeBytes(wav(gen()))
                        ids[i] = p.load(f.absolutePath, 1)
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; name = "ui-sounds" }.start()
        }
    }

    /** Fokuswechsel, nur nach echter Pfeiltaste (nicht bei automatischem Fokus). */
    fun nav() {
        if (SystemClock.uptimeMillis() - LauncherMemory.lastNavKey < 350) play(TICK, 0.32f)
    }
    fun select() = play(SELECT, 0.55f)
    fun open() = play(OPEN, 0.45f)
    fun close() = play(CLOSE, 0.40f)

    private fun play(i: Int, vol: Float) {
        if (!enabled || ids[i] == 0 || ids[i] !in ready) return
        try { pool?.play(ids[i], vol, vol, 1, 0, 1f) } catch (_: Exception) { }
    }

    // ---------- Synthese ----------

    /** Kurzer, heller "Tick" wie bei tvOS. */
    private fun genTick(): FloatArray = synth(0.028) { t ->
        val env = attack(t, 0.0008) * exp(-t / 0.0055)
        env * (0.8 * sin(2 * PI * 2350 * t) + 0.2 * sin(2 * PI * 4700 * t))
    }

    /** Zwei weiche Toene aufwaerts (G5 -> D6). */
    private fun genSelect(): FloatArray = synth(0.16) { t ->
        val a = attack(t, 0.002) * exp(-t / 0.028) * sin(2 * PI * 784 * t)
        val t2 = t - 0.045
        val b = if (t2 > 0) attack(t2, 0.002) * exp(-t2 / 0.045) * sin(2 * PI * 1175 * t2) else 0.0
        0.55 * a + 0.55 * b
    }

    /** Leises Aufgleiten (Einstellungen oeffnen). */
    private fun genOpen(): FloatArray = sweep(520.0, 920.0)

    /** Leises Abgleiten (Einstellungen schliessen). */
    private fun genClose(): FloatArray = sweep(880.0, 500.0)

    private fun sweep(from: Double, to: Double): FloatArray {
        val dur = 0.12
        var phase = 0.0
        return synth(dur) { t ->
            val f = from + (to - from) * (t / dur)
            phase += 2 * PI * f / RATE
            0.45 * sin(PI * t / dur) * sin(phase)
        }
    }

    private fun attack(t: Double, a: Double) = if (t < a) t / a else 1.0

    private inline fun synth(seconds: Double, fn: (Double) -> Double): FloatArray {
        val n = (seconds * RATE).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            // Weiches Ausblenden am Ende gegen Knackser
            val fade = if (i > n - 64) (n - i) / 64.0 else 1.0
            (fn(t) * fade).toFloat().coerceIn(-1f, 1f)
        }
    }

    private fun wav(samples: FloatArray): ByteArray {
        val data = samples.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        samples.forEach { b.putShort((it * 32767).toInt().toShort()) }
        return b.array()
    }
}

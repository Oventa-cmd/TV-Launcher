package de.oventa.tvlauncher

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Eigener Bildschirmschoner: grosse Uhr, Datum und Temperatur ueber den Launcher-Hintergruenden.
 * Hintergruende wechseln mit Ueberblendung und langsamer Bewegung, die Uhr wandert
 * jede Minute leicht an eine andere Stelle (Schutz vor Einbrennen).
 */
class Screensaver : DreamService() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var back: ImageView
    private lateinit var front: ImageView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var weather: TextView
    private lateinit var block: LinearLayout
    private var wall = 0
    private val showWeather by lazy { Prefs(this).weather }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        back = wallView(); front = wallView()
        root.addView(back); root.addView(front)
        // Leichte Abdunklung, damit die Schrift gut lesbar bleibt
        root.addView(View(this).apply { setBackgroundColor(0x59000000) }, match())

        val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
        clock = text(130f, Color.WHITE, light).apply { letterSpacing = -0.02f }
        date = text(26f, 0xCCFFFFFF.toInt(), Typeface.create("sans-serif", Typeface.NORMAL))
        weather = text(26f, 0xCCFFFFFF.toInt(), Typeface.create("sans-serif", Typeface.NORMAL))
        block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(clock)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(date)
                addView(weather.apply { setPadding(dp(18), 0, 0, 0) })
            })
            alpha = 0f
        }
        root.addView(block, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        wall = Prefs(this).wallpaper.coerceIn(0, WALLPAPERS.size - 1)
        front.setImageResource(WALLPAPERS[wall])
        kenBurns(front)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        tick()
        block.post { moveBlock(first = true) }
        main.postDelayed(::nextWallpaper, WALL_MS)
        if (showWeather) scope.launch {
            Weather.refreshIfStale()
            updateWeather()
        }
    }

    override fun onDreamingStopped() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private fun tick() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale.GERMANY).format(now)
        date.text = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(now)
        updateWeather()
        // Exakt zur naechsten vollen Minute
        main.postDelayed(::tick, 60_000L - now.time % 60_000L + 50)
        if (now.time / 60_000L % 2L == 0L) moveBlock(first = false)
    }

    private fun updateWeather() {
        val w = if (showWeather) Weather.current.value else null
        weather.text = if (w != null) "${w.symbol}  ${w.tempC}°  Aachen" else ""
        if (showWeather && w == null) scope.launch { Weather.refreshIfStale(); val n = Weather.current.value; if (n != null) weather.text = "${n.symbol}  ${n.tempC}°  Aachen" }
    }

    /** Block an eine zufaellige Stelle im unteren linken Bereich gleiten lassen. */
    private fun moveBlock(first: Boolean) {
        val parent = block.parent as? View ?: return
        val maxX = (parent.width * 0.45f).coerceAtLeast(0f)
        val maxY = (parent.height - block.height - dp(60)).toFloat().coerceAtLeast(0f)
        val x = dp(70) + Random.nextFloat() * (maxX - dp(70)).coerceAtLeast(1f)
        val y = maxY * (0.45f + Random.nextFloat() * 0.55f)
        if (first) {
            block.translationX = x; block.translationY = y
            block.animate().alpha(1f).setDuration(1200).start()
        } else {
            block.animate().alpha(0f).setDuration(1500).withEndAction {
                block.translationX = x; block.translationY = y
                block.animate().alpha(1f).setDuration(1500).start()
            }.start()
        }
    }

    private fun nextWallpaper() {
        wall = (wall + 1) % WALLPAPERS.size
        back.setImageDrawable(front.drawable)
        back.scaleX = front.scaleX; back.scaleY = front.scaleY
        back.translationX = front.translationX
        front.alpha = 0f
        front.setImageResource(WALLPAPERS[wall])
        kenBurns(front)
        front.animate().alpha(1f).setDuration(3000).setInterpolator(LinearInterpolator()).start()
        main.postDelayed(::nextWallpaper, WALL_MS)
    }

    private fun kenBurns(v: ImageView) {
        val left = Random.nextBoolean()
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, View.SCALE_X, 1.05f, 1.15f),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, 1.05f, 1.15f),
                ObjectAnimator.ofFloat(v, View.TRANSLATION_X, if (left) dp(30).toFloat() else -dp(30).toFloat(), if (left) -dp(30).toFloat() else dp(30).toFloat())
            )
            duration = WALL_MS + 4000
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun wallView() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = match()
    }

    private fun text(sp: Float, color: Int, tf: Typeface) = TextView(this).apply {
        textSize = sp; setTextColor(color); typeface = tf
        setShadowLayer(18f, 0f, 2f, 0x66000000)
        includeFontPadding = false
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val WALL_MS = 90_000L
    }
}

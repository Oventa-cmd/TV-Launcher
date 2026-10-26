package de.oventa.tvlauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Einmalige Anmeldung bei der Watchlist (klassische Views, damit die Fire-TV-Tastatur sicher funktioniert). */
class WatchlistLoginActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Watchlist.init(this)
        val dp = resources.displayMetrics.density
        fun box(focused: Boolean) = GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor(if (focused) 0x33FFFFFF else 0x1AFFFFFF)
            setStroke((1.5f * dp).toInt(), if (focused) Color.WHITE else 0x40FFFFFF)
        }
        fun field(hint: String, type: Int) = EditText(this).apply {
            this.hint = hint; inputType = type; setTextColor(Color.WHITE); setHintTextColor(0x88FFFFFF.toInt())
            textSize = 18f; setSingleLine(); background = box(false)
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            setOnFocusChangeListener { v, f -> v.background = box(f) }
            layoutParams = LinearLayout.LayoutParams((460 * dp).toInt(), -2).apply { topMargin = (12 * dp).toInt() }
        }
        val title = TextView(this).apply {
            text = "Watchlist verbinden"; textSize = 30f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        val sub = TextView(this).apply {
            text = "Mit deinem Konto aus „Als Nächstes“ anmelden.\nDas Passwort wird nicht gespeichert."
            textSize = 14f; setTextColor(0xAAFFFFFF.toInt()); setPadding(0, (6 * dp).toInt(), 0, (10 * dp).toInt())
        }
        val mail = field("E-Mail", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply { setText(Watchlist.email.value) }
        val pass = field("Passwort", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { imeOptions = EditorInfo.IME_ACTION_DONE }
        val msg = TextView(this).apply { textSize = 14f; setTextColor(0xFFFF8A8A.toInt()); setPadding(0, (10 * dp).toInt(), 0, 0) }
        val btn = Button(this).apply {
            text = "Anmelden"; isAllCaps = false; textSize = 17f; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { cornerRadius = 24 * dp; setColor(Color.WHITE) }
            layoutParams = LinearLayout.LayoutParams((460 * dp).toInt(), (52 * dp).toInt()).apply { topMargin = (18 * dp).toInt() }
            setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).setDuration(150).start() }
        }
        val logout = Button(this).apply {
            text = "Abmelden"; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE)
            background = box(false)
            setOnFocusChangeListener { v, f -> v.background = box(f) }
            layoutParams = LinearLayout.LayoutParams((460 * dp).toInt(), (46 * dp).toInt()).apply { topMargin = (10 * dp).toInt() }
            visibility = if (Watchlist.connected.value) View.VISIBLE else View.GONE
            setOnClickListener { Watchlist.logout(this@WatchlistLoginActivity); finish() }
        }
        fun submit() {
            msg.text = ""; btn.isEnabled = false; btn.text = "Verbinde …"
            scope.launch {
                val err = Watchlist.login(this@WatchlistLoginActivity, mail.text.toString(), pass.text.toString())
                if (err == null) { Watchlist.refresh(this@WatchlistLoginActivity, force = true); finish() }
                else { msg.text = err; btn.isEnabled = true; btn.text = "Anmelden" }
            }
        }
        btn.setOnClickListener { submit() }
        pass.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { btn.requestFocus(); true } else false }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.START
            addView(title); addView(sub); addView(mail); addView(pass); addView(msg); addView(btn); addView(logout)
        }
        val root = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1B1530.toInt(), 0xFF0B0D12.toInt()))
            addView(col)
        }
        setContentView(root)
        (if (mail.text.isEmpty()) mail else pass).requestFocus()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

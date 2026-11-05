package de.oventa.tvlauncher

import android.content.Context
import android.graphics.Color as AColor
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key

/** Suche nach Serien und Filmen (TMDB). Klassisches EditText, damit die Fire-TV-Tastatur sicher funktioniert. */
class SearchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sounds.init(this)
        Watchlist.init(this)
        val voice = intent?.getBooleanExtra("voice", false) == true
        setContent { SearchScreen(this, voice) }
    }

    override fun onResume() {
        super.onResume()
        HomeRedirectService.instance?.hideOverlay()
    }
}

@androidx.compose.runtime.Composable
private fun SearchScreen(act: ComponentActivity, startVoice: Boolean = false) {
    var query by remember { mutableStateOf("") }
    // Claude-Suche
    var aiQuery by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiAnswer by remember { mutableStateOf<String?>(null) }
    var aiResults by remember { mutableStateOf<List<Discover.Pick>?>(null) }
    val scope = rememberCoroutineScope()
    val micFocus = remember { FocusRequester() }
    var results by remember { mutableStateOf<List<Discover.Pick>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Discover.Pick?>(null) }
    val firstResult = remember { FocusRequester() }
    var edit by remember { mutableStateOf<EditText?>(null) }
    val onList = Watchlist.allKeys.value

    // Ohne Eingabe: Trends als Vorschlaege
    LaunchedEffect(Unit) { Watchlist.refresh(act); Discover.ensure(act, "top") }
    fun runAi(text: String) {
        val t = text.trim()
        if (t.isEmpty()) { aiAnswer = "Erst etwas eintippen oder 🎤 drücken"; return }
        aiQuery = t; aiBusy = true; aiAnswer = null; aiResults = null
        scope.launch {
            val r = Watchlist.smartSearch(act, t)
            if (aiQuery != t) return@launch
            if (r.error != null) { aiBusy = false; aiAnswer = "Claude-Suche: ${r.error}"; return@launch }
            val picks = Discover.resolve(act, r.items)
            if (aiQuery != t) return@launch
            aiBusy = false
            aiAnswer = "✨ " + r.answer.ifBlank { "Vorschläge von Claude" }
            aiResults = picks
            delay(120); if (picks.isNotEmpty()) try { firstResult.requestFocus() } catch (_: Exception) { }
        }
    }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            aiQuery = text.trim()
            edit?.setText(text.trim()); edit?.setSelection(edit?.text?.length ?: 0)
            hideKeyboard(act, edit)
            runAi(text)
        }
    }
    fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Was möchtest du schauen?")
        try { voiceLauncher.launch(i) } catch (_: Exception) { aiAnswer = "Keine Spracherkennung auf diesem Gerät gefunden" }
    }
    LaunchedEffect(Unit) { if (startVoice) { delay(400); listen() } }
    LaunchedEffect(query) {
        if (query.trim() != aiQuery) { aiQuery = ""; aiResults = null; aiBusy = false; aiAnswer = null }
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = null; loading = false; return@LaunchedEffect }
        loading = true
        delay(450)
        results = Discover.search(act, q)
        loading = false
    }
    val shown: List<Discover.Pick> = aiResults ?: results ?: (Discover.lists.value["top"] ?: emptyList()).take(16)
    val heading = when {
        aiBusy -> "✨ Claude denkt nach …"
        aiAnswer != null && aiResults != null && aiResults!!.isEmpty() -> "${aiAnswer} – keine Titel gefunden"
        aiAnswer != null -> aiAnswer!!
        query.trim().length < 2 -> "Gerade beliebt"
        loading -> "Suche …"
        results == null -> "Suche nicht möglich – Verbindung prüfen"
        shown.isEmpty() -> "Keine Treffer für „${query.trim()}“"
        else -> "${shown.size} Treffer"
    }

    val shownNow = androidx.compose.runtime.rememberUpdatedState(shown)
    BackHandler { if (detail != null) { detail = null; Sounds.close() } else act.finish() }
    LaunchedEffect(detail) {
        if (detail == null) { delay(80); if (shown.isNotEmpty() && query.isNotBlank()) try { firstResult.requestFocus() } catch (_: Exception) { } }
    }

    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B1530), Color(0xFF0B0D12), Color(0xFF0B1220))))) {
        if (detail == null) Column(Modifier.fillMaxSize().padding(start = 60.dp, end = 60.dp, top = 34.dp)) {
            Text("Suchen", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            AndroidView(
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth(0.62f).height(52.dp),
                factory = { ctx -> searchField(ctx, onText = { query = it }, onDown = {
                    hideKeyboard(ctx, edit); try { micFocus.requestFocus() } catch (_: Exception) { }
                }).also { e -> edit = e; if (!startVoice) { e.requestFocus(); e.postDelayed({ showKeyboard(ctx, e) }, 250) } } }
            )
            Row(Modifier.padding(top = 12.dp)) {
                PillButton("🎤  Sprechen", Modifier.focusRequester(micFocus)) { listen() }
                Spacer(Modifier.width(10.dp))
                PillButton("✨  Mit Claude suchen", Modifier) { runAi(query) }
            }
            Text(heading.uppercase(), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(bottom = 30.dp),
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.padding(start = 0.dp)
            ) {
                itemsIndexed(shown, key = { _, p -> p.key }) { i, p ->
                    WatchCard(
                        pickCard(p, p.key in onList),
                        if (i == 0) Modifier.focusRequester(firstResult) else Modifier,
                        onFocus = { },
                        onClick = { detail = p; Sounds.open() }
                    )
                }
            }
        }
        AnimatedVisibility(visible = detail != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
            detail?.let { d ->
                PickDetail(
                    pick = d,
                    installed = { Apps.isInstalled(act, it) },
                    onOpenApp = { pkg -> Apps.launch(act, pkg); act.finish() },
                    onStreamTV = if (StreamTV.isInstalled(act)) ({ uri -> if (StreamTV.open(act, uri)) act.finish() }) else null,
                    onClose = { detail = null; Sounds.close() }
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PillButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (f) Color.White else Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = if (f) 1f else 0.18f), shape)
            .onFocusChanged { f = it.isFocused }
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) { Sounds.select(); onClick(); true } else false
            }
            .focusable()
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(label, color = if (f) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun searchField(ctx: Context, onText: (String) -> Unit, onDown: () -> Unit): EditText {
    val dp = ctx.resources.displayMetrics.density
    fun bg(f: Boolean) = GradientDrawable().apply {
        cornerRadius = 26 * dp
        setColor(if (f) 0x33FFFFFF else 0x1AFFFFFF)
        setStroke((1.5f * dp).toInt(), if (f) AColor.WHITE else 0x40FFFFFF)
    }
    return EditText(ctx).apply {
        hint = "⌕  Serie oder Film suchen …"
        setHintTextColor(0x88FFFFFF.toInt()); setTextColor(AColor.WHITE); textSize = 17f
        setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        background = bg(true)
        setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
        setOnFocusChangeListener { v, f -> v.background = bg(f) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onText(s?.toString() ?: "") }
        })
        setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH || a == EditorInfo.IME_ACTION_DONE) { onDown(); true } else false }
        setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { onDown(); true } else false }
    }
}

private fun showKeyboard(ctx: Context, v: EditText) {
    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
}

private fun hideKeyboard(ctx: Context, v: EditText?) {
    if (v == null) return
    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
}

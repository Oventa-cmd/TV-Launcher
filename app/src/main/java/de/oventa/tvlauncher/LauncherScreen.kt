package de.oventa.tvlauncher

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.key
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val WALLPAPERS = listOf(
    R.drawable.wall_dark, R.drawable.wall_graphite, R.drawable.wall_blue, R.drawable.wall_sunset,
    R.drawable.wall_forest, R.drawable.wall_aurora, R.drawable.wall_bordeaux, R.drawable.wall_ocean
)
val WALL_NAMES = listOf("Dunkel", "Graphit", "Nachtblau", "Abendrot", "Wald", "Polarlicht", "Bordeaux", "Ozean")
val ROTATE_STEPS = listOf(0, 5, 15, 60)

private val White = Color.White
private val Muted = Color.White.copy(alpha = 0.66f)
private val Smooth = spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
private val Accent = Color(0xFF9CC8FF)
private const val INTRO_MS = 1700

/** Weiches Ueberschwingen ("fliegt rein und federt nach"). */
private val BackOut = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** Intro unabhaengig von der System-Animationsgeschwindigkeit (per ADB auf 0.5 gesetzt). */
private object FullSpeed : MotionDurationScale { override val scaleFactor = 1f }

private val NAV_KEYS = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/** Teilfortschritt [0..1] eines Abschnitts der Intro-Animation. */
private fun raw(p: Float, start: Float, len: Float) = ((p - start) / len).coerceIn(0f, 1f)
private fun seg(p: Float, start: Float, len: Float) = FastOutSlowInEasing.transform(raw(p, start, len))

/** Glasoptik ohne Weichzeichner (Fire OS 7 kann keinen Hintergrund-Blur): Verlauf + Lichtkante. */
private fun Modifier.glass(shape: Shape, focused: Boolean = false, strength: Float = 1f): Modifier = this
    .background(
        Brush.verticalGradient(
            listOf(
                White.copy(alpha = (if (focused) 0.30f else 0.15f) * strength),
                White.copy(alpha = (if (focused) 0.16f else 0.05f) * strength)
            )
        ),
        shape
    )
    .border(
        1.dp,
        Brush.verticalGradient(listOf(White.copy(alpha = if (focused) 0.65f else 0.38f), White.copy(alpha = 0.06f))),
        shape
    )

@Composable
fun LauncherScreen(state: LauncherState, isOverlay: Boolean = false) {
    val apps = state.apps.value
    val visible = state.visible.value
    val showInputs = state.showInputs.value
    val inputs = if (showInputs) state.inputs.value else emptyList()
    val wallpaper = state.wallpaper.value
    val resumeTick = state.tick.value
    val anims = state.animations.value
    val greetingOn = state.greeting.value
    val userName = state.userName.value
    val scope = rememberCoroutineScope()
    val rotateMin = state.wallRotate.value
    val weatherOn = state.weather.value
    // Hintergrund automatisch der Reihe nach wechseln
    LaunchedEffect(rotateMin) {
        if (rotateMin > 0) while (true) {
            delay(rotateMin * 60_000L)
            state.setWallpaper((state.wallpaper.value + 1) % WALLPAPERS.size)
        }
    }
    LaunchedEffect(weatherOn, resumeTick) {
        if (weatherOn) while (true) { Weather.refreshIfStale(); delay(5 * 60_000L) }
    }
    // Intro bei jedem Aufruf des Startbildschirms. Overlay und Activity teilen sich eine Intro
    // pro Aufruf, damit beim Wechsel zwischen beiden nichts doppelt abgespielt wird.
    val intro = remember { Animatable(if (anims && (isOverlay || !LauncherMemory.introRecent())) 0f else 1f) }
    val introP: () -> Float = { intro.value }
    var settingsOpen by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    LaunchedEffect(resumeTick) { Watchlist.init(ctx); Watchlist.refresh(ctx) }
    LaunchedEffect(resumeTick) {
        StreamTV.refresh(ctx)
        // Angefangene Folgen aus StreamTV einmal nach dem Start als ersten Reiter zeigen
        if (!LauncherMemory.stvShown && StreamTV.items.value.isNotEmpty()) { LauncherMemory.stvShown = true; LauncherMemory.wlTab.value = "stv" }
    }
    val wlShown = state.watchlistOn.value && (Watchlist.connected.value || StreamTV.items.value.isNotEmpty())
    val wlFirst = remember { FocusRequester() }
    var wlDetail by remember { mutableStateOf<Any?>(null) }
    var wlRowFocused by remember { mutableStateOf(false) }
    var focusedSub by remember { mutableStateOf("") }
    val wlTabs = remember(Watchlist.allKeys.value, Watchlist.connected.value, StreamTV.items.value.isNotEmpty()) { Discover.tabs() }
    val wlTab = LauncherMemory.wlTab.value.takeIf { t -> wlTabs.any { it.id == t } } ?: wlTabs.firstOrNull()?.id ?: "list"
    val wlCards = rowCards(wlTab, Watchlist.items.value, Discover.lists.value[wlTab], Watchlist.allKeys.value)
    LaunchedEffect(wlShown, wlTab, Watchlist.allKeys.value) { if (wlShown) Discover.ensure(ctx, wlTab) }
    // Reiter automatisch durchrotieren (pausiert, solange die Zeile den Fokus hat)
    val wlRotate = state.wlRotate.value
    LaunchedEffect(wlShown, wlRotate, wlRowFocused, wlTab) {
        if (!wlShown || wlRotate <= 0 || wlRowFocused) return@LaunchedEffect
        val i = wlTabs.indexOfFirst { it.id == wlTab }
        val next = wlTabs[(i + 1).mod(wlTabs.size)].id
        Discover.ensure(ctx, next)
        delay(wlRotate * 1000L)
        LauncherMemory.wlTab.value = next
    }
    var wlWasOpen by remember { mutableStateOf(false) }
    var wlFocused by remember { mutableStateOf(false) }
    LaunchedEffect(wlDetail) {
        if (wlDetail != null) wlWasOpen = true
        else if (wlWasOpen) { wlWasOpen = false; delay(80); try { wlFirst.requestFocus() } catch (_: Exception) { } }
    }
    var appsPage by remember { mutableStateOf(false) }
    var focusedLabel by remember { mutableStateOf(LauncherMemory.focusedLabel) }
    val firstTile = remember { FocusRequester() }
    val gear = remember { FocusRequester() }
    val firstPill = remember { FocusRequester() }

    val byPkg = apps.associateBy { it.packageName }
    val shown = visible.mapNotNull { byPkg[it] }

    // Zuletzt gewaehlte Kachel merken (geteilt zwischen Overlay und Activity), damit der Wechsel nahtlos ist
    val targetPkg = LauncherMemory.focusedPkg?.takeIf { p -> shown.any { it.packageName == p } } ?: shown.firstOrNull()?.packageName

    LaunchedEffect(shown.size, resumeTick, settingsOpen) {
        if (!settingsOpen && shown.isNotEmpty()) {
            delay(60)
            try { firstTile.requestFocus() } catch (_: Exception) { }
        }
    }

    LaunchedEffect(resumeTick) {
        if (anims && LauncherMemory.claimIntro(force = isOverlay)) {
            settingsOpen = false
            // Eigener Scope: ein zweiter Resume-Tick bricht die laufende Intro nicht ab
            scope.launch {
                intro.snapTo(0f)
                withContext(FullSpeed) { intro.animateTo(1f, tween(INTRO_MS, easing = LinearEasing)) }
            }
        } else if (!intro.isRunning && intro.value < 1f) {
            intro.snapTo(1f)
        }
    }

    // Erst wenn alles steht und die Intro durch ist, darf das Overlay verschwinden
    LaunchedEffect(shown.isNotEmpty(), resumeTick) {
        if (shown.isNotEmpty()) {
            delay(maxOf(700L, LauncherMemory.introRemaining(INTRO_MS) + 100L))
            state.ready()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key in NAV_KEYS) LauncherMemory.lastNavKey = SystemClock.uptimeMillis()
                when {
                    e.key == Key.Menu && e.type == KeyEventType.KeyUp -> {
                        settingsOpen = !settingsOpen
                        appsPage = false
                        if (settingsOpen) Sounds.open() else Sounds.close()
                        true
                    }
                    // Zurueck: schliesst die Einstellungen, verlaesst den Launcher aber nie
                    e.key == Key.Back -> {
                        if (e.type == KeyEventType.KeyUp && wlDetail != null) { wlDetail = null; Sounds.close() }
                        else if (e.type == KeyEventType.KeyUp && settingsOpen) {
                            if (appsPage) appsPage = false else settingsOpen = false
                            Sounds.close()
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        Wallpaper(wallpaper, anims)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.40f)))))

        // Waehrend die Detailansicht offen ist, wird der Startbildschirm ausgeblendet (kein Fokus dahinter)
        if (wlDetail == null) Column(Modifier.fillMaxSize().padding(start = 60.dp, end = 60.dp, top = 30.dp, bottom = 30.dp)) {
            TopBar(weather = if (weatherOn) Weather.current.value else null, onSettings = { settingsOpen = true; Sounds.open() }, gear = gear, firstTileRef = if (wlShown) wlFirst else if (shown.isNotEmpty()) firstTile else null)

            if (greetingOn) {
                Spacer(Modifier.height(if (wlShown) 12.dp else 22.dp))
                Greeting(introP, resumeTick)
            }

            if (wlShown) {
                Spacer(Modifier.height(14.dp))
                WatchlistRow(
                    tabs = wlTabs,
                    tab = wlTab,
                    onTab = { LauncherMemory.wlTab.value = it },
                    cards = wlCards,
                    loading = wlTab in Discover.loading.value,
                    introP = introP,
                    first = wlFirst,
                    up = gear,
                    down = if (shown.isNotEmpty()) firstTile else null,
                    onRowFocus = { wlRowFocused = it },
                    onSearch = { state.openSearch() },
                    onFocus = { c -> focusedLabel = c.title; focusedSub = c.subtitle; wlFocused = true },
                    onClick = { c ->
                        val t = c.target
                        if (t is StreamTV.Cont) { Sounds.open(); state.openStreamTV(t.uri) }   // direkt weiterschauen
                        else { wlDetail = t; Sounds.open() }
                    }
                )
            }

            Spacer(Modifier.weight(1f))

            Column(Modifier.padding(start = 6.dp).graphicsLayer { alpha = seg(introP(), 0.30f, 0.30f) }) {
                AnimatedContent(
                    targetState = focusedLabel,
                    transitionSpec = {
                        (fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 5 }) togetherWith fadeOut(tween(140))
                    },
                    label = "title"
                ) { t ->
                    Text(
                        t.ifEmpty { " " },
                        color = White, fontSize = if (wlFocused) 24.sp else 38.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = if (wlFocused) (-0.5).sp else (-1.2).sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (wlFocused && focusedSub.isNotBlank()) Text(focusedSub, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else if (!wlShown) Text("OK zum Öffnen · Menü-Taste für Einstellungen", color = Muted, fontSize = 13.sp)
            }

            Spacer(Modifier.height(14.dp))

            // Glas-Dock mit Apps und Eingaengen. Kein clip: vergroesserte Kacheln duerfen ueberstehen.
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val a = seg(introP(), 0.18f, 0.40f)
                        alpha = a
                        translationY = (1f - a) * 40.dp.toPx()
                    }
                    .glass(RoundedCornerShape(26.dp), strength = 0.9f)
                    .padding(horizontal = 22.dp, vertical = 14.dp)
            ) {
                if (shown.isEmpty()) {
                    Text("Keine Apps ausgewählt – Menü-Taste drücken.", color = Muted, fontSize = 16.sp, modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    TileRow(
                        shown = shown,
                        introP = introP,
                        targetPkg = targetPkg,
                        firstTile = firstTile,
                        gear = if (wlShown) wlFirst else gear,
                        downTo = if (inputs.isNotEmpty()) firstPill else null,
                        onFocus = { app -> wlFocused = false; focusedLabel = app.label; LauncherMemory.focusedPkg = app.packageName; LauncherMemory.focusedLabel = app.label },
                        onClick = { app -> state.launch(app.packageName) }
                    )
                }

                if (inputs.isNotEmpty()) {
                    Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(White.copy(alpha = 0.10f)))
                    // Feste Hoehe: das Wachsen der Eingaenge verschiebt nichts darueber
                    Row(Modifier.height(40.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("EINGÄNGE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(end = 6.dp))
                        inputs.forEachIndexed { i, inp ->
                            Pill(
                                inp.label,
                                (if (i == 0) Modifier.focusRequester(firstPill) else Modifier).focusProperties { up = firstTile }
                            ) { state.openInput(inp.id) }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = wlDetail != null,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180))
        ) {
            when (val d = wlDetail) {
                is Watchlist.Item -> WatchlistDetail(
                    item = Watchlist.items.value.firstOrNull { it.key == d.key } ?: d,
                    installed = { state.isInstalled(it) },
                    onOpenApp = { pkg -> wlDetail = null; state.launch(pkg) },
                    onStreamTV = if (StreamTV.installed.value) ({ uri -> wlDetail = null; state.openStreamTV(uri) }) else null,
                    onClose = { wlDetail = null; Sounds.close() }
                )
                is Discover.Pick -> PickDetail(
                    pick = d,
                    installed = { state.isInstalled(it) },
                    onOpenApp = { pkg -> wlDetail = null; state.launch(pkg) },
                    onStreamTV = if (StreamTV.installed.value) ({ uri -> wlDetail = null; state.openStreamTV(uri) }) else null,
                    onClose = { wlDetail = null; Sounds.close() }
                )
            }
        }

        AnimatedVisibility(
            visible = settingsOpen,
            enter = slideInHorizontally(tween(320)) { it } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(260)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            SettingsPanel(state, apps, visible, wallpaper, showInputs, appsPage) { appsPage = it; Sounds.open() }
        }
    }
}

/**
 * Kacheln passen sich der Breite an, damit alle vollstaendig sichtbar sind.
 * Erst wenn es zu viele werden, wird gescrollt.
 */
@Composable
private fun TileRow(
    shown: List<TvApp>,
    introP: () -> Float,
    targetPkg: String?,
    firstTile: FocusRequester,
    gear: FocusRequester,
    downTo: FocusRequester?,
    onFocus: (TvApp) -> Unit,
    onClick: (TvApp) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 18.dp
        val n = shown.size
        val fit = (maxWidth - gap * (n - 1)) / n
        val tileW: Dp = fit.coerceIn(84.dp, 150.dp)
        val scrolls = fit < 84.dp
        val tileH = tileW * 9f / 16f
        val rowMod = Modifier.fillMaxWidth().height(tileH + 22.dp)
        Row(
            if (scrolls) rowMod.horizontalScroll(rememberScrollState()) else rowMod,
            horizontalArrangement = if (scrolls) Arrangement.spacedBy(gap) else Arrangement.spacedBy(gap, Alignment.Start),
            verticalAlignment = Alignment.Bottom
        ) {
            shown.forEachIndexed { idx, app ->
                AppTile(
                    app = app,
                    width = tileW,
                    appear = { seg(introP(), 0.30f + idx * 0.06f, 0.36f) },
                    modifier = (if (app.packageName == targetPkg) Modifier.focusRequester(firstTile) else Modifier)
                        .focusProperties {
                            up = gear
                            if (downTo != null) down = downTo
                        },
                    onFocus = { onFocus(app) },
                    onClick = { onClick(app) }
                )
            }
        }
    }
}

/** Hintergrund mit langsamer "Ken Burns"-Bewegung (abschaltbar). */
@Composable
private fun Wallpaper(wallpaper: Int, anims: Boolean) {
    val drift = if (anims) {
        rememberInfiniteTransition(label = "drift").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(45_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "d"
        )
    } else null
    Crossfade(
        targetState = wallpaper.coerceIn(0, WALLPAPERS.size - 1),
        animationSpec = tween(1800),
        label = "wall",
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val d = drift?.value ?: return@graphicsLayer
            val s = 1.05f + 0.04f * d
            scaleX = s; scaleY = s
            translationX = (d - 0.5f) * 28.dp.toPx()
            translationY = (0.5f - d) * 10.dp.toPx()
        }
    ) { w ->
        Image(
            painter = painterResource(WALLPAPERS[w]),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * "Guten Abend" fliegt Buchstabe fuer Buchstabe von unten herein und federt nach,
 * danach laeuft ein Lichtschimmer darueber. Nach 6 s blendet die Begruessung aus.
 */
@Composable
private fun Greeting(p: () -> Float, tick: Int) {
    val hour by produceState(initialValue = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        while (true) { delay(60_000); value = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    }
    val title = when (hour) {
        in 5..10 -> "Guten Morgen"
        in 11..16 -> "Guten Mittag"
        in 17..21 -> "Guten Abend"
        else -> "Gute Nacht"
    }
    // Nach ein paar Sekunden sanft ausblenden; bei jedem Home-Druck wieder einblenden
    val hide = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        hide.snapTo(0f)
        delay(GREETING_VISIBLE_MS)
        hide.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val step = (0.32f / title.length.coerceAtLeast(1)).coerceAtMost(0.03f)
    Column(Modifier.graphicsLayer {
        val h = hide.value
        alpha = 1f - h
        translationY = -h * 14.dp.toPx()
    }) {
        Row(
            Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Lichtschimmer nach dem Hereinfliegen
                    val s = seg(p(), 0.50f, 0.42f)
                    if (s in 0.001f..0.999f) {
                        val band = size.width * 0.30f
                        val x = -band + (size.width + 2 * band) * s
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Accent, Color.Transparent),
                                start = Offset(x - band / 2, 0f), end = Offset(x + band / 2, size.height)
                            ),
                            blendMode = BlendMode.SrcAtop
                        )
                    }
                }
        ) {
            title.forEachIndexed { i, ch ->
                Text(
                    ch.toString(),
                    color = White, fontSize = 36.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.graphicsLayer {
                        val r = raw(p(), i * step, 0.30f)
                        val c = BackOut.transform(r)
                        alpha = (r * 2.2f).coerceAtMost(1f)
                        translationY = (1f - c) * 46.dp.toPx()
                        val s = 0.6f + 0.4f * c
                        scaleX = s; scaleY = s
                        rotationZ = (1f - c) * -10f
                    }
                )
            }
        }
    }
}

private const val GREETING_VISIBLE_MS = 6_000L

@Composable
private fun TopBar(weather: Weather.Now?, onSettings: () -> Unit, gear: FocusRequester, firstTileRef: FocusRequester?) {
    val now by produceState(initialValue = Date()) {
        while (true) { value = Date(); delay(5_000) }
    }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.GERMANY).format(now) }
    val date = remember(now) { SimpleDateFormat("EE d. MMM", Locale.GERMANY).format(now) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        var f by remember { mutableStateOf(false) }
        val s by animateFloatAsState(if (f) 1.12f else 1f, Smooth, label = "gear")
        Box(
            Modifier
                .size(36.dp)
                .focusRequester(gear)
                .focusProperties { down = firstTileRef ?: FocusRequester.Default }
                .onFocusChanged { f = it.isFocused; if (it.isFocused) Sounds.nav() }
                .clickable(remember { MutableInteractionSource() }, null) { onSettings() }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = s; scaleY = s }
                    .then(if (f) Modifier.background(White, CircleShape) else Modifier.glass(CircleShape)),
                contentAlignment = Alignment.Center
            ) {
                Text("⚙", color = if (f) Color.Black else White, fontSize = 17.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.glass(RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (weather != null) {
                Text(weather.symbol, color = White, fontSize = 15.sp)
                Spacer(Modifier.width(5.dp))
                Text("${weather.tempC}°", color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(16.dp).background(White.copy(alpha = 0.25f)))
            }
            Text(date, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(10.dp))
            Text(time, color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AppTile(app: TvApp, width: Dp, appear: () -> Float, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(
        when { pressed -> 1.03f; focused -> 1.14f; else -> 1f },
        if (pressed) tween(90) else Smooth, label = "scale"
    )
    val lift by animateDpAsState(if (focused) (-6).dp else 0.dp, spring(0.72f, Spring.StiffnessMediumLow), label = "lift")
    val elev by animateDpAsState(if (focused) 22.dp else 4.dp, tween(260), label = "elev")
    val sheen by animateFloatAsState(if (focused) 1f else 0f, tween(260), label = "sheen")
    val shape = RoundedCornerShape(12.dp)
    val height = width * 9f / 16f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(width).graphicsLayer {
            // Gestaffeltes Hereinfahren beim Intro
            val a = appear()
            alpha = a
            translationY = (1f - a) * 34.dp.toPx()
            val s = 0.9f + 0.1f * a
            scaleX = s; scaleY = s
        }
    ) {
        // Fokus-Ziel ohne Transformation, damit die Pfeiltasten-Navigation die echte Position nutzt
        Box(
            modifier
                .size(width, height)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) { onFocus(); Sounds.nav() } }
                .clickable(remember { MutableInteractionSource() }, null) {
                    if (pressed) return@clickable
                    Sounds.select()
                    pressed = true
                    // Kurz eindruecken, dann starten
                    scope.launch { delay(130); onClick(); delay(400); pressed = false }
                }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationY = lift.toPx() }
                    .shadow(elev, shape, clip = false)
                    .clip(shape)
                    .background(Color(0xFF1C1C22)),
                contentAlignment = Alignment.Center
            ) {
                if (app.banner != null) {
                    Image(app.banner, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        if (app.icon != null) Image(app.icon, contentDescription = null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(app.label, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    }
                }
                // Glas-Glanz: immer eine feine Lichtkante, bei Fokus ein heller Reflex
                Box(
                    Modifier.fillMaxSize()
                        .background(Brush.verticalGradient(listOf(White.copy(alpha = 0.10f + 0.16f * sheen), Color.Transparent, Color.Transparent)))
                        .border(1.dp, Brush.verticalGradient(listOf(White.copy(alpha = 0.30f + 0.45f * sheen), White.copy(alpha = 0.05f))), shape)
                )
            }
        }
    }
}

@Composable
private fun Pill(label: String, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    // Waechst nach rechts: echte Groessenaenderung, die folgenden Eingaenge ruecken weich nach
    val padH by animateDpAsState(if (f) 20.dp else 14.dp, spring(0.75f, Spring.StiffnessMediumLow), label = "padH")
    val padV by animateDpAsState(if (f) 8.dp else 6.dp, spring(0.75f, Spring.StiffnessMediumLow), label = "padV")
    val fs by animateFloatAsState(if (f) 13f else 12f, Smooth, label = "fs")
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .onFocusChanged { f = it.isFocused; if (it.isFocused) Sounds.nav() }
            .clickable(remember { MutableInteractionSource() }, null) { Sounds.select(); onClick() }
            .then(if (f) Modifier.background(White, shape) else Modifier.glass(shape))
            .padding(horizontal = padH, vertical = padV)
    ) {
        Text(label, color = if (f) Color.Black else White, fontSize = fs.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsPanel(
    activity: LauncherState, apps: List<TvApp>, visible: List<String>, wallpaper: Int, showInputs: Boolean,
    appsPage: Boolean, setAppsPage: (Boolean) -> Unit
) {
    val first = remember { FocusRequester() }
    val appsEntry = remember { FocusRequester() }
    val firstApp = remember { FocusRequester() }
    var wasInApps by remember { mutableStateOf(false) }
    LaunchedEffect(appsPage) {
        delay(80)
        try {
            when {
                appsPage -> firstApp.requestFocus()
                wasInApps -> appsEntry.requestFocus()
                else -> first.requestFocus()
            }
        } catch (_: Exception) { }
        wasInApps = appsPage
    }
    val ordered = visible.mapNotNull { p -> apps.firstOrNull { it.packageName == p } } + apps.filter { it.packageName !in visible }
    val shape = RoundedCornerShape(24.dp)
    val learning = LauncherMemory.learningKey.value
    val menuKey = LauncherMemory.menuKey.value

    Column(
        Modifier
            .padding(18.dp)
            .width(370.dp)
            .fillMaxHeight()
            .shadow(30.dp, shape)
            .background(Brush.verticalGradient(listOf(Color(0xE6343440), Color(0xEB16161C))), shape)
            .border(1.dp, Brush.verticalGradient(listOf(White.copy(alpha = 0.40f), White.copy(alpha = 0.06f))), shape)
            .clip(shape)
            .padding(horizontal = 26.dp, vertical = 26.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (appsPage) {
            Text("‹  Apps", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("OK = ein/aus · ◀ ▶ = verschieben · Zurück = Einstellungen", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            ordered.forEachIndexed { i, app ->
                ToggleRow(
                    label = app.label,
                    on = app.packageName in visible,
                    onToggle = { activity.toggleVisible(app.packageName) },
                    onMove = { d -> activity.move(app.packageName, d) },
                    modifier = if (i == 0) Modifier.focusRequester(firstApp) else Modifier
                )
            }
        } else {
        Text("Einstellungen", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Section("Hintergrund")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WALLPAPERS.forEachIndexed { i, res ->
                WallThumb(res, WALL_NAMES[i], i == wallpaper, if (i == 0) Modifier.focusRequester(first) else Modifier) { activity.setWallpaper(i) }
            }
        }
        Spacer(Modifier.height(8.dp))
        val rot = activity.wallRotate.value
        ActionRow("Automatisch wechseln", if (rot == 0) "Aus" else if (rot >= 60) "jede Stunde" else "alle $rot min") {
            activity.setWallRotate(ROTATE_STEPS[(ROTATE_STEPS.indexOf(rot).coerceAtLeast(0) + 1) % ROTATE_STEPS.size])
        }
        Spacer(Modifier.height(18.dp))
        Section("Startbildschirm")
        ActionRow("Apps ein-/ausblenden", "${visible.count { p -> apps.any { it.packageName == p } }} sichtbar", Modifier.focusRequester(appsEntry)) { setAppsPage(true) }
        Spacer(Modifier.height(18.dp))
        Section("Watchlist „Als Nächstes“")
        if (Watchlist.connected.value) {
            ToggleRow("Auf dem Startbildschirm zeigen", activity.watchlistOn.value, { activity.setWatchlistOn(!activity.watchlistOn.value) }, null)
            val wr = activity.wlRotate.value
            ActionRow("Kategorien automatisch wechseln", if (wr == 0) "Aus" else if (wr >= 60) "jede Minute" else "alle $wr s") { activity.cycleWlRotate() }
            ActionRow("Konto", Watchlist.email.value.ifBlank { "verbunden" }) { activity.openWatchlistLogin() }
        } else {
            ActionRow("Watchlist verbinden", "Anmelden") { activity.openWatchlistLogin() }
        }
        Spacer(Modifier.height(18.dp))
        Section("Begrüßung & Effekte")
        ToggleRow("Begrüßung (Guten Morgen …)", activity.greeting.value, { activity.setGreeting(!activity.greeting.value) }, null)
        ToggleRow("Klick-Sounds", activity.sounds.value, { activity.setSounds(!activity.sounds.value) }, null)
        ToggleRow("Animationen", activity.animations.value, { activity.setAnimations(!activity.animations.value) }, null)
        Spacer(Modifier.height(18.dp))
        Section("Fernbedienung")
        ToggleRow("Zurück lang drücken = Startbildschirm", activity.longBackHome.value, { activity.setLongBackHome(!activity.longBackHome.value) }, null)
        when {
            !activity.serviceActive -> Text("Home-Dienst ist nicht aktiv – Tastenbelegung nicht möglich.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            learning -> ActionRow("Jetzt gewünschte Taste drücken …", "Zurück = Abbrechen") { }
            else -> ActionRow("Zusätzliche Taste anlernen", LauncherMemory.keyName(menuKey)) { activity.startLearningKey() }
        }
        if (menuKey != 0 && !learning && activity.serviceActive) ActionRow("Zusätzliche Taste ausschalten", null) { activity.disableMenuKey() }
        Spacer(Modifier.height(18.dp))
        Section("Bildschirmschoner & Ausschalten")
        if (activity.canWritePower) {
            ActionRow("Bildschirmschoner startet nach", Power.label(activity.screensaverMin.value)) { activity.cycleScreensaver() }
            ActionRow("TV ausschalten nach", Power.label(activity.sleepMin.value)) { activity.cycleSleep() }
        } else {
            Text("Zum Einstellen einmalig per adb freischalten (siehe Anleitung).", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        ActionRow("Bildschirmschoner jetzt starten", null) { activity.startScreensaver() }
        Spacer(Modifier.height(18.dp))
        Section("System")
        ToggleRow("Temperatur Aachen", activity.weather.value, { activity.setWeather(!activity.weather.value) }, null)
        ToggleRow("Eingänge (HDMI) anzeigen", showInputs, { activity.setShowInputs(!showInputs) }, null)
        ActionRow(if (activity.isFireTv) "Fire TV Einstellungen" else "TV-Einstellungen", null) { activity.openSystemSettings() }
        if (activity.isGoogleTv) {
            ToggleRow(if (activity.stockBusy.value) "Google-TV-Startseite …" else "Google-TV-Startseite erreichbar", activity.stockOn.value, { activity.toggleStockHome() }, null)
            Text(
                activity.stockMsg.value ?: if (activity.stockOn.value) "Aus = Home-Taste öffnet immer diesen Launcher." else "Abgeschaltet – Home öffnet immer diesen Launcher.",
                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            if (activity.stockOn.value) ActionRow(activity.stockHomeLabel, null) { activity.openStockHome() }
        } else {
            ActionRow(activity.stockHomeLabel, null) { activity.openStockHome() }
        }
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun WallThumb(res: Int, name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.08f else 1f, Smooth, label = "wall")
    Column {
        Box(
            modifier
                .size(92.dp, 52.dp)
                .onFocusChanged { f = it.isFocused; if (it.isFocused) Sounds.nav() }
                .clickable(remember { MutableInteractionSource() }, null) { Sounds.select(); onClick() }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = s; scaleY = s }
                    .clip(RoundedCornerShape(9.dp))
                    .border(if (selected || f) 2.dp else 1.dp, if (selected || f) White else White.copy(alpha = 0.15f), RoundedCornerShape(9.dp))
            ) {
                Image(painterResource(res), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(name, color = if (selected) White else Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: () -> Unit, onMove: ((Int) -> Unit)?, modifier: Modifier = Modifier) {
    var f by remember { mutableStateOf(false) }
    val knob by animateDpAsState(if (on) 15.dp else 0.dp, spring(0.7f, Spring.StiffnessMedium), label = "knob")
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(modifier)
            .then(if (f) Modifier.glass(shape, focused = true) else Modifier)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) Sounds.nav() }
            .onPreviewKeyEvent { e ->
                if (onMove != null && on && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)) {
                    LauncherMemory.lastNavKey = SystemClock.uptimeMillis()
                    Sounds.nav()
                    onMove(if (e.key == Key.DirectionLeft) -1 else 1); true
                } else false
            }
            .clickable(remember { MutableInteractionSource() }, null) { Sounds.select(); onToggle() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.size(34.dp, 19.dp).clip(RoundedCornerShape(10.dp))
                .background(if (on) Color(0xFF34C759) else White.copy(alpha = 0.2f))
                .padding(2.dp)
        ) {
            Box(Modifier.padding(start = knob).size(15.dp).clip(CircleShape).background(White))
        }
    }
}

@Composable
private fun ActionRow(label: String, value: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(modifier)
            .then(if (f) Modifier.glass(shape, focused = true) else Modifier)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) Sounds.nav() }
            .clickable(remember { MutableInteractionSource() }, null) { Sounds.select(); onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value != null) Text(value, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        Text("›", color = Muted, fontSize = 16.sp, modifier = Modifier.padding(start = 6.dp))
    }
}


// ---------------- Watchlist "Als Nächstes" ----------------

internal val CARD_MARGIN = 12.dp

@Composable
private fun rememberTmdbImage(path: String?, size: String): ImageBitmap? {
    val ctx = LocalContext.current
    return produceState<ImageBitmap?>(null, path, size) { value = Watchlist.image(ctx, path, size) }.value
}

/** Einheitliche Karte fuer Watchlist-Titel und Entdecken-Vorschlaege. */
internal data class RowCard(
    val key: String, val title: String, val image: String?, val logo: String?,
    val subtitle: String, val progress: Float?, val rank: Int?, val target: Any
)

private fun rowCards(tab: String, items: List<Watchlist.Item>, picks: List<Discover.Pick>?, onList: Set<String>): List<RowCard> =
    if (tab == "stv") StreamTV.items.value.map { RowCard(it.key, it.show, it.image, null, it.subtitle, it.progress, null, it) }
    else if (tab == "list") items.map { RowCard(it.key, it.title, it.backdrop ?: it.poster, it.logo, it.subtitle, if (it.total > 0) it.progress else null, null, it) }
    else (picks ?: emptyList()).filter { it.key !in onList }.take(if (tab == "top") 10 else 20).mapIndexed { i, p ->
        RowCard(p.key, p.title, p.backdrop ?: p.poster, null, p.reason ?: p.info, null, if (tab == "top") i + 1 else null, p)
    }

@Composable
private fun WatchlistRow(
    tabs: List<Discover.Tab>,
    tab: String,
    onTab: (String) -> Unit,
    cards: List<RowCard>,
    loading: Boolean,
    introP: () -> Float,
    first: FocusRequester,
    up: FocusRequester,
    down: FocusRequester?,
    onRowFocus: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onFocus: (RowCard) -> Unit,
    onClick: (RowCard) -> Unit
) {
    var rowFocus by remember { mutableStateOf(false) }
    val tabReq = remember(tabs.map { it.id }) { tabs.associate { it.id to FocusRequester() } }
    val label = tabs.firstOrNull { it.id == tab }?.label ?: ""
    Column(Modifier.graphicsLayer {
        val a = seg(introP(), 0.22f, 0.40f)
        alpha = a; translationY = (1f - a) * 24.dp.toPx()
    }) {
        Row(Modifier.padding(start = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(targetState = label, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "tabl") { l ->
                Text(l.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
            }
            // Punkte als Hinweis, welcher Reiter gerade laeuft
            Spacer(Modifier.width(10.dp))
            tabs.forEach { t ->
                Box(Modifier.padding(horizontal = 2.dp).size(if (t.id == tab) 6.dp else 4.dp).background(White.copy(alpha = if (t.id == tab) 0.9f else 0.3f), CircleShape))
            }
        }
        Row(
            Modifier
                .offset(x = 6.dp - CARD_MARGIN)
                .fillMaxWidth()
                .height(118.dp)
                .onFocusChanged { st -> if (st.hasFocus != rowFocus) { rowFocus = st.hasFocus; onRowFocus(st.hasFocus) } },
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(
                visible = rowFocus,
                enter = androidx.compose.animation.expandHorizontally(tween(260)) + fadeIn(tween(220)),
                exit = androidx.compose.animation.shrinkHorizontally(tween(220)) + fadeOut(tween(160))
            ) {
                TabRail(tabs, tab, tabReq, onTab, right = first, up = up, down = down, onSearch = onSearch)
            }
            Row(
                Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (cards.isEmpty()) {
                    // Fokussierbarer Platzhalter, damit Navigation hierher immer funktioniert
                    var f by remember { mutableStateOf(false) }
                    Box(
                        Modifier.padding(start = CARD_MARGIN).focusRequester(first)
                            .focusProperties { this.up = up; if (down != null) this.down = down; tabReq[tab]?.let { left = it } }
                            .onFocusChanged { f = it.isFocused }
                            .clickable(remember { MutableInteractionSource() }, null) { }
                            .then(if (f) Modifier.glass(RoundedCornerShape(12.dp), focused = true) else Modifier)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            when { loading -> "Lädt …"; tab == "list" -> "Deine Watchlist ist leer"; else -> "Nichts Neues gefunden" },
                            color = Muted, fontSize = 13.sp
                        )
                    }
                } else cards.forEachIndexed { i, c ->
                    key(c.key) {
                        WatchCard(
                            c,
                            (if (i == 0) Modifier.focusRequester(first) else Modifier).focusProperties {
                                this.up = up
                                if (down != null) this.down = down
                                if (i == 0) tabReq[tab]?.let { left = it }
                            },
                            onFocus = { onFocus(c) },
                            onClick = { onClick(c) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabRail(
    tabs: List<Discover.Tab>, tab: String, req: Map<String, FocusRequester>, onTab: (String) -> Unit,
    right: FocusRequester, up: FocusRequester, down: FocusRequester?, onSearch: () -> Unit
) {
    Column(
        Modifier
            .padding(start = CARD_MARGIN - 6.dp, end = 6.dp)
            .width(178.dp)
            .fillMaxHeight()
            .glass(RoundedCornerShape(14.dp), strength = 0.8f)
            .padding(vertical = 6.dp, horizontal = 6.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Suche als oberster Eintrag (oeffnet eigenen Bildschirm, wechselt keinen Reiter)
        run {
            var f by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp)
                    .focusProperties { this.right = right; this.up = up }
                    .onFocusChanged { st -> f = st.isFocused; if (st.isFocused) Sounds.nav() }
                    .clickable(remember { MutableInteractionSource() }, null) { Sounds.open(); onSearch() }
                    .background(if (f) White else White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⌕", color = if (f) Color.Black else White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(7.dp))
                Text("Suchen", fontSize = 11.sp, color = if (f) Color.Black else White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(3.dp))
        }
        tabs.forEachIndexed { i, t ->
            var f by remember { mutableStateOf(false) }
            val sel = t.id == tab
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp)
                    .then(req[t.id]?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .focusProperties {
                        this.right = right
                        if (i == tabs.lastIndex && down != null) this.down = down
                    }
                    .onFocusChanged { st -> f = st.isFocused; if (st.isFocused) { Sounds.nav(); if (!sel) onTab(t.id) } }
                    .clickable(remember { MutableInteractionSource() }, null) { right.let { r -> try { r.requestFocus() } catch (_: Exception) { } } }
                    .background(if (f) White else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(5.dp).background(if (sel) (if (f) Color.Black else Accent) else Color.Transparent, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(
                    t.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (f) Color.Black else if (sel) White else White.copy(alpha = 0.7f),
                    fontWeight = if (sel || f) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
internal fun WatchCard(it: RowCard, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.10f else 1f, Smooth, label = "wc")
    val shape = RoundedCornerShape(12.dp)
    val bg = rememberTmdbImage(it.image, "w780")
    val logo = rememberTmdbImage(it.logo, "w300")
    // Fokus-Ziel mit Rand: beim Scrollen bleibt die vergroesserte Karte komplett sichtbar
    Box(
        modifier
            .size(170.dp + CARD_MARGIN * 2, 96.dp + 16.dp)
            .onFocusChanged { st -> f = st.isFocused; if (st.isFocused) { onFocus(); Sounds.nav() } }
            .clickable(remember { MutableInteractionSource() }, null) { Sounds.select(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(170.dp, 96.dp)
                .graphicsLayer { scaleX = s; scaleY = s }
                .shadow(if (f) 20.dp else 4.dp, shape, clip = false)
                .clip(shape)
                .background(Color(0xFF1C1C22))
        ) {
            if (bg != null) Image(bg, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))))
            if (it.rank != null) Text(
                "${it.rank}", color = White, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 9.dp, top = 2.dp)
            )
            Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 9.dp)) {
                if (logo != null) Image(logo, it.title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.height(20.dp).width(100.dp))
                else Text(it.title, color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(it.subtitle, color = White.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            if (it.progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(White.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(it.progress).background(White))
            }
            Box(Modifier.fillMaxSize().border(if (f) 2.dp else 1.dp, if (f) White else White.copy(alpha = 0.15f), shape))
        }
    }
}

@Composable
private fun WatchlistDetail(
    item: Watchlist.Item,
    installed: (String) -> Boolean,
    onOpenApp: (String) -> Unit,
    onStreamTV: ((String) -> Unit)?,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bg = rememberTmdbImage(item.backdrop ?: item.poster, "w1280")
    val logo = rememberTmdbImage(item.logo, "w500")
    val apps = Watchlist.PROVIDER_APPS
        .filter { (n, pkg) -> item.providers.any { p -> p.equals(n, true) } && installed(pkg) }
        .distinctBy { it.second }
    val first = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item.key) { delay(80); try { first.requestFocus() } catch (_: Exception) { } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (bg != null) Image(bg, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.55f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 70.dp, end = 70.dp).width(460.dp)) {
            Text(if (item.status == "watching") "WEITER GEHT’S" else "ALS NÄCHSTES", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(10.dp))
            if (logo != null) Image(logo, item.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.height(54.dp).width(260.dp))
            else Text(item.title, color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
            Spacer(Modifier.height(12.dp))
            val info = buildList {
                add(item.subtitle)
                if (item.total > 0) add("${item.done} von ${item.total} Folgen")
                if (item.providers.isNotEmpty()) add(item.providers.joinToString(", "))
            }.joinToString("  ·  ")
            Text(info, color = Muted, fontSize = 14.sp)
            if (item.total > 0) {
                Box(Modifier.padding(top = 12.dp).width(300.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(White.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(item.progress).background(White))
                }
            }
            Spacer(Modifier.height(26.dp))
            var firstUsed = false
            fun fm(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
            if (onStreamTV != null) {
                val resume = item.type == "tv" && item.status == "watching"
                DetailButton(if (resume) "▶  In StreamTV weiterschauen" else "▶  In StreamTV abspielen", primary = true, modifier = fm()) {
                    onStreamTV(StreamTV.titleUri(item.type, item.id, play = true, next = item.next))
                }
            }
            apps.forEach { (name, pkg) ->
                DetailButton("▶  In $name öffnen", primary = onStreamTV == null, modifier = fm()) { onOpenApp(pkg) }
            }
            if (item.next != null) {
                DetailButton(
                    if (busy) "Speichere …" else "✓  ${item.subtitle} als gesehen markieren",
                    primary = apps.isEmpty(), modifier = fm()
                ) {
                    if (busy) return@DetailButton
                    busy = true
                    scope.launch {
                        val ok = Watchlist.markNext(ctx, item)
                        busy = false
                        msg = if (ok) "Gespeichert" else "Konnte nicht speichern"
                        if (ok) Sounds.select()
                    }
                }
            }
            DetailButton("Zurück", primary = false, modifier = fm()) { onClose() }
            if (msg != null) Text(msg!!, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun DetailButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.05f else 1f, Smooth, label = "db")
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .padding(vertical = 5.dp)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) Sounds.nav() }
            .clickable(remember { MutableInteractionSource() }, null) { onClick() }
    ) {
        Box(
            Modifier
                .graphicsLayer { scaleX = s; scaleY = s; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                .then(if (f) Modifier.background(White, shape) else if (primary) Modifier.glass(shape, focused = true) else Modifier.glass(shape))
                .padding(horizontal = 22.dp, vertical = 11.dp)
        ) {
            Text(label, color = if (f) Color.Black else White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun PickDetail(
    pick: Discover.Pick,
    installed: (String) -> Boolean,
    onOpenApp: (String) -> Unit,
    onStreamTV: ((String) -> Unit)? = null,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val d by produceState<org.json.JSONObject?>(null, pick.key) { value = Discover.details(ctx, pick.type, pick.id) }
    val det = d
    val bg = rememberTmdbImage(pick.backdrop ?: pick.poster, "w1280")
    val logo = rememberTmdbImage(det?.let { Discover.pickLogo(it) }, "w500")
    val providers = det?.let { Discover.providersDE(it) } ?: emptyList()
    val apps = Watchlist.PROVIDER_APPS
        .filter { (n, pkg) -> providers.any { p -> p.equals(n, true) } && installed(pkg) }
        .distinctBy { it.second }
    val onList = pick.key in Watchlist.allKeys.value
    val first = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pick.key) { delay(80); try { first.requestFocus() } catch (_: Exception) { } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (bg != null) Image(bg, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.55f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 70.dp, end = 70.dp).width(500.dp)) {
            Text((pick.reason ?: "ENTDECKEN").uppercase(), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            if (logo != null) Image(logo, pick.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.height(54.dp).width(260.dp))
            else Text(pick.title, color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
            Spacer(Modifier.height(10.dp))
            val info = buildList {
                add(pick.info)
                det?.let { Discover.genres(it).take(2) }?.takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
                det?.optInt("number_of_seasons")?.takeIf { it > 0 }?.let { add(if (it == 1) "1 Staffel" else "$it Staffeln") }
                det?.optInt("runtime")?.takeIf { it > 0 }?.let { add("${it / 60} Std. ${it % 60} Min.") }
            }.joinToString("  ·  ")
            Text(info, color = Muted, fontSize = 13.sp)
            val ov = det?.optString("overview")?.takeIf { it.isNotBlank() } ?: pick.overview
            if (ov.isNotBlank()) Text(ov, color = White.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            if (providers.isNotEmpty()) Text("Bei " + providers.take(3).joinToString(", "), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(22.dp))
            var firstUsed = false
            fun fm(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
            DetailButton(
                when { onList -> "✓  Auf deiner Watchlist"; busy -> "Speichere …"; det == null -> "Lädt …"; else -> "+  Zur Watchlist hinzufügen" },
                primary = true, modifier = fm()
            ) {
                if (onList || busy || det == null) return@DetailButton
                busy = true
                scope.launch {
                    val ok = Watchlist.addPlanned(ctx, pick.type, det)
                    busy = false
                    msg = if (ok) "Als „Geplant“ hinzugefügt" else "Konnte nicht speichern"
                    if (ok) Sounds.select()
                }
            }
            if (onStreamTV != null) {
                DetailButton("▶  In StreamTV abspielen", primary = false, modifier = fm()) { onStreamTV(StreamTV.titleUri(pick.type, pick.id, play = true)) }
                DetailButton("In StreamTV ansehen", primary = false, modifier = fm()) { onStreamTV(StreamTV.titleUri(pick.type, pick.id, play = false)) }
            }
            apps.forEach { (name, pkg) -> DetailButton("▶  In $name öffnen", primary = false, modifier = fm()) { onOpenApp(pkg) } }
            DetailButton("Zurück", primary = false, modifier = fm()) { onClose() }
            if (msg != null) Text(msg!!, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

internal fun pickCard(p: Discover.Pick, onList: Boolean) =
    RowCard(p.key, p.title, p.backdrop ?: p.poster, null, if (onList) "✓ Auf deiner Watchlist" else p.info, null, null, p)

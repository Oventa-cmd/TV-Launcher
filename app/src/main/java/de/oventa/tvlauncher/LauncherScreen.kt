package de.oventa.tvlauncher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val WALLPAPERS = listOf(R.drawable.wall_dark, R.drawable.wall_graphite, R.drawable.wall_blue)
val WALL_NAMES = listOf("Dunkel", "Graphit", "Nachtblau")

private val White = Color.White
private val Muted = Color.White.copy(alpha = 0.66f)
private val Glass = Color.White.copy(alpha = 0.10f)
private val Smooth = spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)

@Composable
fun LauncherScreen(
    activity: MainActivity,
    apps: List<TvApp>,
    visible: List<String>,
    inputs: List<TvInput>,
    wallpaper: Int,
    showInputs: Boolean,
    resumeTick: Int
) {
    var settingsOpen by remember { mutableStateOf(false) }
    var focusedLabel by remember { mutableStateOf("") }
    val firstTile = remember { FocusRequester() }
    val gear = remember { FocusRequester() }
    val firstPill = remember { FocusRequester() }

    val byPkg = apps.associateBy { it.packageName }
    val shown = visible.mapNotNull { byPkg[it] }

    BackHandler(enabled = true) { if (settingsOpen) settingsOpen = false }

    LaunchedEffect(shown.size, resumeTick, settingsOpen) {
        if (!settingsOpen && shown.isNotEmpty()) {
            delay(60)
            try { firstTile.requestFocus() } catch (_: Exception) { }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Menu && e.type == KeyEventType.KeyUp) { settingsOpen = !settingsOpen; true } else false
            }
    ) {
        Crossfade(targetState = wallpaper.coerceIn(0, WALLPAPERS.size - 1), animationSpec = tween(600), label = "wall") { w ->
            Image(
                painter = painterResource(WALLPAPERS[w]),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))

        Column(Modifier.fillMaxSize().padding(start = 75.dp, end = 75.dp, top = 34.dp, bottom = 44.dp)) {
            TopBar(onSettings = { settingsOpen = true }, gear = gear, firstTileRef = if (shown.isNotEmpty()) firstTile else null)

            Spacer(Modifier.weight(1f))

            Text("AUSGEWÄHLT", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)
            AnimatedContent(
                targetState = focusedLabel,
                transitionSpec = {
                    (fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 5 }) togetherWith fadeOut(tween(140))
                },
                label = "title"
            ) { t ->
                Text(
                    t.ifEmpty { " " },
                    color = White, fontSize = 52.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text("OK zum Öffnen · Menü-Taste für Einstellungen", color = Muted, fontSize = 14.sp)

            Spacer(Modifier.height(34.dp))

            if (shown.isEmpty()) {
                Text("Keine Apps ausgewählt – Menü-Taste drücken.", color = Muted, fontSize = 16.sp)
            } else {
                Row(
                    Modifier.fillMaxWidth().height(112.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    shown.forEachIndexed { idx, app ->
                        AppTile(
                            app = app,
                            modifier = (if (idx == 0) Modifier.focusRequester(firstTile) else Modifier)
                                .focusProperties {
                                    up = gear
                                    if (showInputs && inputs.isNotEmpty()) down = firstPill
                                },
                            onFocus = { focusedLabel = app.label },
                            onClick = { Apps.launch(activity, app.packageName) }
                        )
                    }
                }
            }

            if (showInputs && inputs.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Eingänge", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 4.dp))
                    inputs.forEachIndexed { i, inp ->
                        Pill(inp.label, if (i == 0) Modifier.focusRequester(firstPill).focusProperties { up = firstTile } else Modifier.focusProperties { up = firstTile }) { Apps.openInput(activity, inp.id) }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = settingsOpen,
            enter = slideInHorizontally(tween(320)) { it } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(260)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            SettingsPanel(activity, apps, visible, wallpaper, showInputs)
        }
    }
}

@Composable
private fun TopBar(onSettings: () -> Unit, gear: FocusRequester, firstTileRef: FocusRequester?) {
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
                .size(32.dp)
                .focusRequester(gear)
                .focusProperties { down = firstTileRef ?: FocusRequester.Default }
                .onFocusChanged { f = it.isFocused }
                .clickable(remember { MutableInteractionSource() }, null) { onSettings() }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = s; scaleY = s }
                    .clip(CircleShape)
                    .background(if (f) White else Glass),
                contentAlignment = Alignment.Center
            ) {
                Text("⚙", color = if (f) Color.Black else White, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(date, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(10.dp))
        Text(time, color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AppTile(app: TvApp, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.14f else 1f, Smooth, label = "scale")
    val lift by animateDpAsState(if (focused) (-6).dp else 0.dp, spring(0.72f, Spring.StiffnessMediumLow), label = "lift")
    val elev by animateDpAsState(if (focused) 22.dp else 6.dp, tween(260), label = "elev")
    val sheen by animateFloatAsState(if (focused) 1f else 0f, tween(260), label = "sheen")
    val shape = RoundedCornerShape(11.dp)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
        // Fokus-Ziel ohne Transformation, damit die Pfeiltasten-Navigation die echte Position nutzt
        Box(
            modifier
                .size(120.dp, 68.dp)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .clickable(remember { MutableInteractionSource() }, null) { onClick() }
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
                    if (app.icon != null) Image(app.icon, contentDescription = null, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(app.label, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
            }
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = sheen }
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.26f), Color.Transparent), end = androidx.compose.ui.geometry.Offset(160f, 140f)))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
            )
        }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            app.label, color = White.copy(alpha = sheen), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Pill(label: String, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.08f else 1f, Smooth, label = "pill")
    Box(
        modifier
            .onFocusChanged { f = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, null) { onClick() }
    ) {
        Box(
            Modifier
                .graphicsLayer { scaleX = s; scaleY = s }
                .clip(RoundedCornerShape(12.dp))
                .background(if (f) White else Glass)
                .padding(horizontal = 13.dp, vertical = 6.dp)
        ) {
            Text(label, color = if (f) Color.Black else White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SettingsPanel(activity: MainActivity, apps: List<TvApp>, visible: List<String>, wallpaper: Int, showInputs: Boolean) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); try { first.requestFocus() } catch (_: Exception) { } }
    val ordered = visible.mapNotNull { p -> apps.firstOrNull { it.packageName == p } } + apps.filter { it.packageName !in visible }

    Column(
        Modifier
            .padding(20.dp)
            .width(360.dp)
            .fillMaxHeight()
            .shadow(30.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xF01C1C22))
            .padding(horizontal = 26.dp, vertical = 26.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Einstellungen", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Section("Hintergrund")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WALLPAPERS.forEachIndexed { i, res ->
                WallThumb(res, WALL_NAMES[i], i == wallpaper, if (i == 0) Modifier.focusRequester(first) else Modifier) { activity.setWallpaper(i) }
            }
        }
        Spacer(Modifier.height(18.dp))
        Section("Apps auf dem Startbildschirm")
        Text("OK = ein/aus · ◀ ▶ = verschieben", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
        ordered.forEach { app ->
            ToggleRow(
                label = app.label,
                on = app.packageName in visible,
                onToggle = { activity.toggleVisible(app.packageName) },
                onMove = { d -> activity.move(app.packageName, d) }
            )
        }
        Spacer(Modifier.height(18.dp))
        Section("System")
        ToggleRow("Eingänge (HDMI) anzeigen", showInputs, { activity.setShowInputs(!showInputs) }, null)
        ActionRow("Fire TV Einstellungen") { activity.openFireSettings() }
        ActionRow("Amazon-Startseite öffnen") { activity.openAmazonHome() }
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
                .onFocusChanged { f = it.isFocused }
                .clickable(remember { MutableInteractionSource() }, null) { onClick() }
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
private fun ToggleRow(label: String, on: Boolean, onToggle: () -> Unit, onMove: ((Int) -> Unit)?) {
    var f by remember { mutableStateOf(false) }
    val knob by animateDpAsState(if (on) 15.dp else 0.dp, spring(0.7f, Spring.StiffnessMedium), label = "knob")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (f) White.copy(alpha = 0.16f) else Color.Transparent)
            .onFocusChanged { f = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (onMove != null && on && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)) {
                    onMove(if (e.key == Key.DirectionLeft) -1 else 1); true
                } else false
            }
            .clickable(remember { MutableInteractionSource() }, null) { onToggle() }
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
private fun ActionRow(label: String, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (f) White.copy(alpha = 0.16f) else Color.Transparent)
            .onFocusChanged { f = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, null) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text("›", color = Muted, fontSize = 16.sp)
    }
}

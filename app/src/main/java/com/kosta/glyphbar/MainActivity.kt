package com.kosta.glyphbar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

private val Bg = Color(0xFF0A0A0A)
private val Panel = Color(0xFF141414)
private val PanelHi = Color(0xFF1E1E1E)
private val Dim = Color(0xFF888888)
private val Faint = Color(0xFF555555)

class MainActivity : ComponentActivity() {

    private val vm: GlyphViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Bg) { ControlPanel(vm) }
            }
        }
    }

    override fun onStop() {
        // Don't keep driving the bar (or tapping audio) once we're not visible.
        vm.onBackgrounded()
        super.onStop()
    }
}

private enum class Tab(val label: String) {
    Manual("MAN"),
    Animations("ANIM"),
    Editor("EDIT"),
    Sound("SOUND"),
    Tones("FILE"),
    Pov("POV"),
}

@Composable
private fun ControlPanel(vm: GlyphViewModel) {
    val status by vm.glyph.status.collectAsStateWithLifecycle()
    val frame by vm.glyph.frame.collectAsStateWithLifecycle()
    val brightness by vm.glyph.brightness.collectAsStateWithLifecycle()
    val playing by vm.glyph.playing.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Manual) }

    val ready = status is GlyphStatus.Ready

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        Header(status, playing)

        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BarPreview(
                frame = frame,
                enabled = ready,
                onZoneTap = { if (tab == Tab.Editor) vm.editorToggleZone(it) else vm.glyph.toggleZone(it) },
                onZoneDrag = { z, v ->
                    val lvl = (v * MAX_LIGHT).roundToInt()
                    if (tab == Tab.Editor) vm.editorSetZone(z, lvl) else vm.glyph.setZone(z, lvl)
                },
            )
            Column(Modifier.weight(1f)) {
                MasterBrightness(brightness, ready) { vm.glyph.setBrightness(it) }
                Spacer(Modifier.height(8.dp))
                QuickRow(ready, vm)
            }
        }

        TabBar(tab) { tab = it }

        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.Manual -> ManualTab(vm, ready)
                Tab.Animations -> AnimationsTab(vm, ready)
                Tab.Editor -> EditorTab(vm, ready)
                Tab.Sound -> SoundTab(vm, ready)
                Tab.Tones -> TonesTab(vm, ready)
                Tab.Pov -> PovTab(vm, ready)
            }
        }
    }
}

// ------------------------------------------------------------------ chrome

@Composable
private fun Header(status: GlyphStatus, playing: String?) {
    val (text, color) = when (status) {
        GlyphStatus.Connecting -> "Connecting to Glyph service…" to Dim
        GlyphStatus.Ready -> (playing?.let { "Playing · $it" } ?: "Connected · session open") to Color(0xFF4CAF50)
        is GlyphStatus.Unsupported -> status.reason to Color(0xFFFFA726)
        is GlyphStatus.Error -> status.message to Color(0xFFE53935)
    }
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "GLYPH BAR",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
            Spacer(Modifier.width(6.dp))
            Text(text, color = color, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Tab.entries.forEach { t ->
            val on = t == current
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) PanelHi else Color.Transparent)
                    .clickable { onSelect(t) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label,
                    color = if (on) Color.White else Faint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * Vertical mock of the physical bar. Tap toggles a zone; drag up/down on a zone
 * sets its brightness, so per-zone dimming is reachable without a slider each.
 */
@Composable
private fun BarPreview(
    frame: Frame,
    enabled: Boolean,
    onZoneTap: (Int) -> Unit,
    onZoneDrag: (Int, Float) -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val percents = frame.asPercents()
        (0 until ZONE_COUNT).forEach { i ->
            val pct = percents[i]
            Box(
                Modifier
                    .size(width = 44.dp, height = 20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.06f + 0.94f * (pct / 100f)))
                    .clickable(enabled = enabled) { onZoneTap(i) }
                    .pointerInput(enabled, i) {
                        if (!enabled) return@pointerInput
                        detectVerticalDragGestures { change, _ ->
                            change.consume()
                            // Drag up = brighter. Position within the box maps to level.
                            val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            onZoneDrag(i, v)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (pct > 0) "$pct" else "A${i + 1}",
                    color = if (pct > 55) Color.Black else Faint,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        // Not controllable: the camera recording privacy indicator.
        Box(
            Modifier
                .size(width = 44.dp, height = 20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF3A1414))
                .border(1.dp, Color(0xFF5A2020), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("REC", color = Color(0xFF8A3030), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun MasterBrightness(value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            "BRIGHTNESS  ${(value * 100).roundToInt()}%",
            color = Dim,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Slider(value = value, onValueChange = onChange, enabled = enabled)
    }
}

@Composable
private fun QuickRow(enabled: Boolean, vm: GlyphViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Mini("ALL", enabled, Modifier.weight(1f)) { vm.glyph.allOn() }
        Mini("OFF", enabled, Modifier.weight(1f)) { vm.glyph.allOff() }
        Mini("STOP", enabled, Modifier.weight(1f)) { vm.stopAudio() }
    }
}

// ------------------------------------------------------------------ tabs

@Composable
private fun ManualTab(vm: GlyphViewModel, enabled: Boolean) {
    val selection by vm.glyph.selection.collectAsStateWithLifecycle()
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel("Per-zone brightness")
        (0 until ZONE_COUNT).forEach { z ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Panel)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "A${z + 1}",
                    color = Dim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(28.dp),
                )
                Slider(
                    value = selection[z] / MAX_LIGHT.toFloat(),
                    onValueChange = { vm.glyph.setZone(z, (it * MAX_LIGHT).roundToInt()) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(selection[z] * 100f / MAX_LIGHT).roundToInt()}".padStart(3),
                    color = Faint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(28.dp),
                )
            }
        }

        SectionLabel("Presets")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mini("EVEN", enabled, Modifier.weight(1f)) {
                vm.glyph.setFrame(IntArray(ZONE_COUNT) { MAX_LIGHT / 2 })
            }
            Mini("RAMP", enabled, Modifier.weight(1f)) {
                vm.glyph.setFrame(IntArray(ZONE_COUNT) { (it + 1) * MAX_LIGHT / ZONE_COUNT })
            }
            Mini("ENDS", enabled, Modifier.weight(1f)) {
                vm.glyph.setFrame(frameOf(MAX_LIGHT, 0, 0, 0, 0, MAX_LIGHT))
            }
        }
        Text(
            "Tip: drag up/down on a segment in the bar to dim it. The 7th segment is " +
                "the red recording LED — it's the camera privacy indicator and the SDK " +
                "exposes no channel for it, so no app can light it.",
            color = Faint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AnimationsTab(vm: GlyphViewModel, enabled: Boolean) {
    val custom by vm.custom.collectAsStateWithLifecycle()
    val playing by vm.glyph.playing.collectAsStateWithLifecycle()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionLabel("Built-in · ${Animations.all.size}")
        Animations.all.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { e ->
                    AnimCard(
                        title = e.name,
                        subtitle = e.description,
                        active = playing == e.name,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.glyph.play(e.name, e.animation) },
                        onLongClick = { vm.editorLoadFrom(e) },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        SectionLabel("Saved · ${custom.size}")
        if (custom.isEmpty()) {
            Text(
                "None yet. Build one in the EDITOR tab, or long-press a built-in above to " +
                    "load it into the editor as a starting point.",
                color = Faint,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
        custom.forEach { c ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (playing == c.name) PanelHi else Panel)
                    .clickable(enabled = enabled) { vm.glyph.play(c.name, c.keys) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "${c.keys.frames.size} frames · ${c.keys.stepMs}ms · ${c.keys.durationMs}ms loop",
                        color = Faint,
                        fontSize = 9.sp,
                    )
                }
                Mini("EDIT", enabled) { vm.editorLoadCustom(c) }
                Spacer(Modifier.width(4.dp))
                Mini("DEL", enabled) { vm.deleteCustom(c.name) }
            }
        }
    }
}

@Composable
private fun EditorTab(vm: GlyphViewModel, enabled: Boolean) {
    val frames by vm.editorFrames.collectAsStateWithLifecycle()
    val index by vm.editorIndex.collectAsStateWithLifecycle()
    val stepMs by vm.editorStepMs.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel("Timeline · frame ${index + 1}/${frames.size}")

        // Horizontal filmstrip: each frame drawn as six stacked pips.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Panel)
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            frames.forEachIndexed { i, f ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (i == index) PanelHi else Color(0xFF0E0E0E))
                        .border(
                            width = if (i == index) 1.dp else 0.dp,
                            color = if (i == index) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(4.dp),
                        )
                        .clickable { vm.editorSelect(i) }
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    f.forEach { v ->
                        Box(
                            Modifier
                                .size(width = 14.dp, height = 3.dp)
                                .background(Color.White.copy(alpha = 0.08f + 0.92f * (v / MAX_LIGHT.toFloat()))),
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mini("+ ADD", enabled, Modifier.weight(1f)) { vm.editorAddFrame(false) }
            Mini("+ DUP", enabled, Modifier.weight(1f)) { vm.editorAddFrame(true) }
            Mini("DEL", enabled, Modifier.weight(1f)) { vm.editorDeleteFrame() }
            Mini("CLR", enabled, Modifier.weight(1f)) { vm.editorClear() }
        }

        SectionLabel("Frame step · ${stepMs}ms  (${1000 / stepMs} fps)")
        Slider(
            value = stepMs.toFloat(),
            onValueChange = { vm.editorSetStep(it.roundToInt()) },
            valueRange = 20f..500f,
            enabled = enabled,
        )

        SectionLabel("Edit zones of frame ${index + 1}")
        Text(
            "Tap or drag the bar on the left — edits apply to the selected frame and " +
                "preview live on the hardware.",
            color = Faint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mini("▶ PREVIEW", enabled, Modifier.weight(1f)) { vm.editorPreview() }
            Mini("■ STOP", enabled, Modifier.weight(1f)) { vm.glyph.stop() }
        }

        SectionLabel("Save")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Name", color = Faint, fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
            )
            Mini("SAVE", enabled && name.isNotBlank()) {
                vm.saveCustom(name)
                name = ""
            }
        }
    }
}

@Composable
private fun SoundTab(vm: GlyphViewModel, enabled: Boolean) {
    val context = LocalContext.current
    val playing by vm.glyph.playing.collectAsStateWithLifecycle()
    val error by vm.audioError.collectAsStateWithLifecycle()
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var pending by remember { mutableStateOf<AudioReactive.Mode?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        val mode = pending
        pending = null
        if (ok && mode != null) vm.startMic(mode) else if (!ok) vm.clearAudioError()
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel("Microphone → light")
        Text(
            "Listens to the room, so it reacts to anything you can hear — Spotify, " +
                "YouTube, a speaker across the room, your voice. Point the phone at " +
                "the sound.",
            color = Faint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )

        AudioReactive.Mode.entries.forEach { m ->
            val desc = when (m) {
                AudioReactive.Mode.Spectrum -> "Six frequency bands · bass at the bottom"
                AudioReactive.Mode.Level -> "VU meter · fills with loudness"
                AudioReactive.Mode.Bass -> "Bass-only pulse across all zones"
            }
            AnimCard(
                title = m.name.uppercase(),
                subtitle = desc,
                active = playing == "Mic · ${m.name}",
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (granted) {
                        vm.startMic(m)
                    } else {
                        pending = m
                        launcher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onLongClick = {},
            )
        }

        Mini("■ STOP AUDIO", enabled) { vm.stopAudio() }

        Text(
            "Why the mic and not the audio you're playing: Android blocks apps from " +
                "reading the system output mix — that's a privacy rule, not a setting, " +
                "and it's why the earlier version of this tab did nothing at all. To " +
                "analyse a track directly, use the FILE tab, which plays it through " +
                "this app where analysis is allowed.",
            color = Faint,
            fontSize = 9.sp,
            lineHeight = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        error?.let {
            Text(
                it,
                color = Color(0xFFE53935),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x22E53935))
                    .padding(10.dp),
            )
        }

        if (!granted) {
            Text(
                "Microphone permission not granted yet — tap a mode above to request it.",
                color = Color(0xFFFFA726),
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * Play any audio file and generate its pattern live.
 *
 * Also handles Glyph Composer files, which carry an authored pattern — if one
 * is present the user can choose it over the generated one.
 */
@Composable
private fun TonesTab(vm: GlyphViewModel, enabled: Boolean) {
    val context = LocalContext.current
    val tones by vm.tones.collectAsStateWithLifecycle()
    val error by vm.toneError.collectAsStateWithLifecycle()
    val audioErr by vm.audioError.collectAsStateWithLifecycle()
    val playing by vm.glyph.playing.collectAsStateWithLifecycle()

    var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedName by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(AudioReactive.Mode.Spectrum) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Persist access so the uri survives a rotation / re-read.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        pickedUri = uri.toString()
        pickedName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Track"
        // If it happens to be a Composer file, surface the authored pattern too.
        vm.importTone(uri, pickedName)
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel("Any sound file → light")
        Text(
            "Pick any audio file — mp3, m4a, wav, ogg, flac. It plays through this app " +
                "and the pattern is generated from the audio as it goes. This is the one " +
                "path Android allows: an app may analyse its own playback, never the " +
                "system mix.",
            color = Faint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )

        Mini("+ PICK AUDIO FILE", enabled) { picker.launch(arrayOf("audio/*")) }

        pickedUri?.let { uriStr ->
            Text(pickedName, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudioReactive.Mode.entries.forEach { m ->
                    val on = mode == m
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (on) PanelHi else Panel)
                            .border(
                                width = if (on) 1.dp else 0.dp,
                                color = if (on) Color.White else Color.Transparent,
                                shape = RoundedCornerShape(6.dp),
                            )
                            .clickable { mode = m }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            m.name.uppercase(),
                            color = if (on) Color.White else Faint,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Mini("▶ PLAY + GENERATE", enabled, Modifier.weight(1f)) {
                    if (!granted) {
                        permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        vm.playSoundFile(android.net.Uri.parse(uriStr), pickedName, mode)
                    }
                }
                Mini("■ STOP", enabled) { vm.stopAudio() }
            }

            if (!granted) {
                Text(
                    "Needs microphone permission — Android gates the Visualizer behind it " +
                        "even for our own audio. Nothing is recorded.",
                    color = Color(0xFFFFA726),
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                )
            }
        }

        (audioErr ?: error)?.let {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x22FFA726))
                    .padding(10.dp),
            ) {
                Text(it, color = Color(0xFFFFA726), fontSize = 10.sp, lineHeight = 14.sp)
            }
        }

        if (tones.isNotEmpty()) {
            SectionLabel("Authored patterns found")
            Text(
                "This file carries a real Glyph Composer pattern. Play that instead of " +
                    "the generated one.",
                color = Faint,
                fontSize = 9.sp,
                lineHeight = 13.sp,
            )
            tones.forEach { t ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (playing == t.title) PanelHi else Panel)
                        .clickable(enabled = enabled) {
                            vm.playTone(t, pickedUri?.let { android.net.Uri.parse(it) })
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            "${t.frames.size} frames · ${t.zoneCount} zones · " +
                                "${(t.frames.size * 16.666f / 1000f).roundToInt()}s",
                            color = Faint,
                            fontSize = 9.sp,
                        )
                    }
                    Mini("EDIT", enabled) { vm.loadToneIntoEditor(t) }
                }
            }
        }
    }
}

@Composable
private fun PovTab(vm: GlyphViewModel, enabled: Boolean) {
    val playing by vm.glyph.playing.collectAsStateWithLifecycle()
    val hz by vm.glyph.writeHz.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("HELLO") }
    var columnUs by rememberSaveable { mutableStateOf(3000f) }
    var radPerColumn by rememberSaveable { mutableStateOf(0.02f) }
    var invert by rememberSaveable { mutableStateOf(false) }
    var swing by rememberSaveable { mutableStateOf(true) }
    var threshold by rememberSaveable { mutableStateOf(vm.motion.defaultThreshold()) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel("Persistence of vision")
        Text(
            "Write a message in the air. The bar is a 6-pixel column: swing the phone " +
                "and each column flashes in turn, so your eye assembles the letters. " +
                "Works best in a dark room, swung fast.",
            color = Faint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )

        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(24) },
            placeholder = { Text("Message", color = Faint, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )

        // Show the message as it will actually be painted.
        val cols = remember(text) { PovFont.columns(text) }
        Text("${cols.size} columns", color = Faint, fontSize = 9.sp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Panel)
                .horizontalScroll(rememberScrollState())
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            cols.forEach { mask ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    (0 until ZONE_COUNT).forEach { r ->
                        Box(
                            Modifier
                                .size(3.dp)
                                .background(
                                    if ((mask shr r) and 1 == 1) Color.White else Color(0xFF262626)
                                ),
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val on = swing
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) PanelHi else Panel)
                    .clickable { swing = true }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text("ON SWING", color = if (on) Color.White else Faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace) }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (!on) PanelHi else Panel)
                    .clickable { swing = false }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text("CONTINUOUS", color = if (!on) Color.White else Faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace) }
        }

        if (swing) {
            SectionLabel("Text width · ${"%.3f".format(radPerColumn)} rad/col")
            Slider(
                value = radPerColumn,
                onValueChange = { radPerColumn = it },
                valueRange = 0.005f..0.06f,
                enabled = enabled,
            )
            Text(
                "How much you sweep per letter-column. Lower = tighter text that needs " +
                    "only a small flick; higher = spread wide across a big swing. Letters " +
                    "stay the same width whether you swing fast or slow — that's the point. " +
                    if (hz > 0) "The bar can repaint about ${hz.roundToInt()}×/sec; swing faster than that and columns drop."
                    else "Run BENCH to see how fast this phone can repaint.",
                color = Faint,
                fontSize = 9.sp,
                lineHeight = 13.sp,
            )

            SectionLabel("Swing trigger · ${"%.1f".format(threshold)} ${vm.motion.unit}")
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                valueRange = 1f..15f,
                enabled = enabled,
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Panel)
                    .clickable { invert = !invert }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Flip direction", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(
                    if (invert) "ON" else "OFF",
                    color = if (invert) Color(0xFF4CAF50) else Faint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                "If the message comes out mirrored or reads backwards, flip this.",
                color = Faint,
                fontSize = 9.sp,
            )

            if (!vm.motion.hasGyro) {
                Text(
                    "No gyroscope on this device — falling back to the accelerometer, " +
                        "which is noisier and driftier for this.",
                    color = Color(0xFFFFA726),
                    fontSize = 9.sp,
                )
            }
        } else {
            SectionLabel("Column time · ${columnUs.roundToInt()}µs")
            Slider(
                value = columnUs,
                onValueChange = { columnUs = it },
                valueRange = 500f..12000f,
                enabled = enabled,
            )
            Text(
                "Auto-scroll speed for continuous mode. " +
                    if (hz > 0) "Measured: ${hz.roundToInt()} writes/sec (${(1000f / hz).let { "%.1f".format(it) }}ms each)."
                    else "Run the benchmark to find this phone's real limit.",
                color = Faint,
                fontSize = 9.sp,
                lineHeight = 13.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mini("▶ START", enabled && text.isNotBlank(), Modifier.weight(1f)) {
                vm.pov.columnUs = columnUs.roundToInt().toLong()
                vm.pov.radPerColumn = radPerColumn
                vm.pov.threshold = threshold
                vm.pov.invert = invert
                vm.pov.mode = if (swing) PovController.Mode.Swing else PovController.Mode.Continuous
                vm.pov.start(text, MAX_LIGHT)
            }
            Mini("TEST", enabled, Modifier.weight(1f)) {
                vm.pov.columnUs = columnUs.roundToInt().toLong()
                vm.pov.testPass(text, MAX_LIGHT)
            }
            Mini("BENCH", enabled, Modifier.weight(1f)) { vm.pov.benchmark {} }
        }
        Mini("■ STOP", enabled) { vm.pov.stop() }

        playing?.let { Text("Running: $it", color = Color(0xFF4CAF50), fontSize = 10.sp) }

        Text(
            "Honest caveat: every column is a round-trip to the Glyph service, so there's " +
                "a hard limit on how fast columns can repaint. BENCH measures it on your " +
                "actual phone. Swing faster than that rate and columns simply drop — the " +
                "text goes gappy rather than lagging behind.",
            color = Faint,
            fontSize = 9.sp,
            lineHeight = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ------------------------------------------------------------------ atoms

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Dim,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnimCard(
    title: String,
    subtitle: String,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) PanelHi else Panel)
            .border(
                width = if (active) 1.dp else 0.dp,
                color = if (active) Color(0xFF4CAF50) else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp),
    ) {
        Text(
            title,
            color = if (enabled) Color.White else Faint,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        Text(subtitle, color = Faint, fontSize = 9.sp, lineHeight = 12.sp)
    }
}

@Composable
private fun Mini(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) PanelHi else Panel)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Faint,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

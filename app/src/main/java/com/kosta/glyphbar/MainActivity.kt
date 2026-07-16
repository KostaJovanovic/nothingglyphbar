package com.kosta.glyphbar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private lateinit var glyph: GlyphController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The no-arg overload picks bar icon colours from system night mode, but this
        // UI is unconditionally dark — in light mode that yields dark-on-dark icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        glyph = GlyphController(applicationContext, lifecycleScope)
        glyph.connect()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Color(0xFF0A0A0A)) {
                    GlyphBarScreen(glyph)
                }
            }
        }
    }

    override fun onStop() {
        // lifecycleScope only cancels at DESTROYED, so without this a running pattern
        // keeps driving the bar (and binder IPC) while the app is backgrounded.
        glyph.stop()
        super.onStop()
    }

    override fun onDestroy() {
        glyph.release()
        super.onDestroy()
    }
}

@Composable
private fun GlyphBarScreen(glyph: GlyphController) {
    val status by glyph.status.collectAsStateWithLifecycle()
    val lit by glyph.lit.collectAsStateWithLifecycle()
    var progress by remember { mutableFloatStateOf(50f) }

    val enabled = status is GlyphStatus.Ready

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "GLYPH BAR",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "Nothing Phone (4a) · 6 addressable zones",
            color = Color(0xFF888888),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )

        StatusBanner(status)

        BarPreview(lit = lit, enabled = enabled, onZoneTap = glyph::toggleZone)

        Section("Basics") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action("All on", enabled, Modifier.weight(1f)) { glyph.allOn() }
                Action("All off", enabled, Modifier.weight(1f)) { glyph.allOff() }
            }
        }

        Section("Animations") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action("Breathe", enabled, Modifier.weight(1f)) { glyph.breathe() }
                Action("Chase", enabled, Modifier.weight(1f)) { glyph.chase() }
                Action("Stop", enabled, Modifier.weight(1f)) { glyph.stop() }
            }
        }

        Section("Progress · ${progress.toInt()}%") {
            Slider(
                value = progress,
                onValueChange = {
                    progress = it
                    glyph.showProgress(it.toInt())
                },
                valueRange = 0f..100f,
                enabled = enabled,
            )
        }

        Text(
            "The 7th segment is the red recording LED. It's driven by the camera " +
                "privacy indicator and isn't exposed by the Glyph SDK, so no app can " +
                "light it — including this one.",
            color = Color(0xFF666666),
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun StatusBanner(status: GlyphStatus) {
    val (text, color) = when (status) {
        GlyphStatus.Connecting -> "Connecting to Glyph service…" to Color(0xFF888888)
        GlyphStatus.Ready -> "Connected · session open" to Color(0xFF4CAF50)
        is GlyphStatus.Unsupported -> status.reason to Color(0xFFFFA726)
        is GlyphStatus.Error -> status.message to Color(0xFFE53935)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, color = color, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/** A vertical mock of the physical bar: six tappable white zones plus the fixed red LED. */
@Composable
private fun BarPreview(lit: Set<Int>, enabled: Boolean, onZoneTap: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141414))
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BAR_ZONES.indices.forEach { i ->
                val on = i in lit
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 26.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (on) Color.White else Color(0xFF2A2A2A))
                        .clickable(enabled = enabled) { onZoneTap(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "A${i + 1}",
                        color = if (on) Color.Black else Color(0xFF666666),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            // Not clickable by design — the SDK exposes no channel for it.
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 26.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF3A1414))
                    .border(1.dp, Color(0xFF5A2020), RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("REC", color = Color(0xFF8A3030), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title.uppercase(),
            color = Color(0xFF888888),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
        content()
    }
}

@Composable
private fun Action(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1E1E1E),
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF141414),
            disabledContentColor = Color(0xFF555555),
        ),
    ) {
        Text(label, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

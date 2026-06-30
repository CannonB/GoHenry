package com.gohenry.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

// --- Per-vehicle accents (Car Select hero theming) ---
// Adapted from docs/car-visuals-plan.md for the GoHenry car-select carousel.
private val MustangMagenta = Color(0xFFC2185B)
private val MustangMagentaDeep = Color(0xFF7A003A)
private val MustangMagentaHi = Color(0xFFFF5EA8)

private val EscapeRed = Color(0xFFD32F2F)
private val EscapeRedDeep = Color(0xFF7F0000)
private val EscapeRedHi = Color(0xFFFF7A7A)

private val F150Blue = Color(0xFF1F3A5F)
private val F150BlueDeep = Color(0xFF0B1730)
private val F150BlueHi = Color(0xFF4F78B5)

// Generic sunset fallback for unknown models (keeps unknown cars on-brand).
private val SunsetTop = Color(0xFFFFA552)
private val SunsetMid = Color(0xFFE0533D)
private val SunsetBottom = Color(0xFF7A2E6B)

/** Which low-alpha background motif a card paints behind its content. */
enum class CardMotif { SkidMarks, MudTracks, Confetti, None }

/** One source of truth for a vehicle's car-select visual identity. */
data class VehicleVisualTheme(
    val gradientTop: Color,
    val gradientMid: Color,
    val gradientBottom: Color,
    val glow: Color,
    val cornerRadius: Dp,
    val motif: CardMotif,
) {
    /** Diagonal (top-left → bottom-right) 3-stop sweep for the card. */
    fun brush(): Brush = Brush.linearGradient(listOf(gradientTop, gradientMid, gradientBottom))

    /**
     * Card content color: white carrying a very slight share of the card's own
     * hue (a 16% blend toward the mid gradient stop) so text/icons feel part of
     * the card while staying highly legible.
     */
    val contentColor: Color get() = lerp(Color.White, gradientMid, 0.16f)
}

/**
 * Builds a card theme by RECOLORING from a single user-chosen [base] color while
 * keeping the same glossy 3-stop gradient style as the model themes: a lighter
 * highlight on top, the chosen hue in the middle, and a darker shade at the
 * bottom. Used by the carousel/notification cards once the user picks a per-slug
 * color (default dark grey), so the card keeps its gradient look in any color.
 */
fun themeFromColor(base: Color): VehicleVisualTheme {
    val top = lerp(base, Color.White, 0.28f)
    val bottom = lerp(base, Color.Black, 0.45f)
    return VehicleVisualTheme(
        gradientTop = top,
        gradientMid = base,
        gradientBottom = bottom,
        glow = top,
        cornerRadius = 26.dp,
        motif = CardMotif.None,
    )
}

/**
 * Resolves a vehicle → its theme. Mapping is by model string (Mustang / Escape /
 * F-150), falling back to a per-index sunset cycle so unknown models never
 * regress to an un-themed card. Engine type is accepted for parity with the plan
 * but the model match already implies it for the three reference vehicles.
 */
fun vehicleVisualTheme(model: String?, engineType: String?, index: Int): VehicleVisualTheme {
    val m = model?.lowercase().orEmpty()
    return when {
        m.contains("mustang") -> VehicleVisualTheme(
            MustangMagentaHi, MustangMagenta, MustangMagentaDeep,
            glow = MustangMagentaHi, cornerRadius = 30.dp, motif = CardMotif.None,
        )
        m.contains("escape") -> VehicleVisualTheme(
            EscapeRedHi, EscapeRed, EscapeRedDeep,
            glow = EscapeRedHi, cornerRadius = 28.dp, motif = CardMotif.None,
        )
        m.contains("f-150") || m.contains("f150") -> VehicleVisualTheme(
            F150BlueHi, F150Blue, F150BlueDeep,
            glow = F150BlueHi, cornerRadius = 22.dp, motif = CardMotif.None,
        )
        else -> {
            // Index-cycle fallback: rotate the sunset stops so a 4th+ car still
            // gets a distinct-enough card.
            val shift = (index % 3) * 0.06f
            VehicleVisualTheme(
                SunsetTop.copy(alpha = 1f),
                SunsetMid,
                SunsetBottom.copy(red = (SunsetBottom.red + shift).coerceAtMost(1f)),
                glow = SunsetTop, cornerRadius = 26.dp, motif = CardMotif.None,
            )
        }
    }
}

/**
 * Paints the per-vehicle motif as a semi-transparent overlay (~0.18–0.30) so it
 * reads clearly against the gradient while the tinted-white content stays legible.
 * Called from a `Canvas` sized to match the card.
 */
fun DrawScope.drawCardMotif(motif: CardMotif) {
    when (motif) {
        CardMotif.SkidMarks -> drawSkidMarks()
        CardMotif.MudTracks -> drawMudTracks()
        CardMotif.Confetti -> drawConfetti()
        CardMotif.None -> Unit
    }
}

/** Two smearing diagonal peel-out streaks — thin at the start, wider trailing. */
private fun DrawScope.drawSkidMarks() {
    val ink = Color.White.copy(alpha = 0.22f)
    for (i in 0..1) {
        val yOffset = size.height * (0.35f + i * 0.30f)
        val path = Path().apply {
            moveTo(-size.width * 0.05f, yOffset)
            cubicTo(
                size.width * 0.30f, yOffset - size.height * 0.10f,
                size.width * 0.65f, yOffset + size.height * 0.15f,
                size.width * 1.05f, yOffset - size.height * 0.05f,
            )
        }
        // Trailing end reads wider/smudged by stacking strokes of growing width.
        drawPath(path, ink, style = Stroke(width = (6 + i * 4).dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, Color.White.copy(alpha = 0.12f), style = Stroke(width = (14 + i * 6).dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Two parallel chunky off-road tread trails (lug blocks) across the card. */
private fun DrawScope.drawMudTracks() {
    val mud = Color(0xFF2A1C0A).copy(alpha = 0.30f)
    val trackWidth = 26.dp.toPx()
    val block = 14.dp.toPx()
    for (t in 0..1) {
        val cy = size.height * (0.40f + t * 0.32f)
        var x = 0f
        while (x < size.width) {
            drawRoundRect(
                color = mud,
                topLeft = Offset(x, cy - trackWidth / 2),
                size = androidx.compose.ui.geometry.Size(block, trackWidth),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            )
            x += block * 1.8f
        }
    }
}

/** Scattered confetti dots / sparkles in a couple of cheerful tints. */
private fun DrawScope.drawConfetti() {
    val rng = Random(42) // fixed seed → stable pattern across recompositions
    val tints = listOf(
        Color.White.copy(alpha = 0.28f),
        EscapeRedHi.copy(alpha = 0.28f),
        Color.White.copy(alpha = 0.20f),
    )
    repeat(34) {
        val cx = rng.nextFloat() * size.width
        val cy = rng.nextFloat() * size.height
        val r = (3 + rng.nextInt(5)).dp.toPx()
        drawCircle(tints[rng.nextInt(tints.size)], radius = r, center = Offset(cx, cy))
    }
}


/** Engine-aware category used by the fresh GoHenry UI for gauges and energy icons. */
enum class EngineVisualKind { BatteryElectric, PlugInHybrid, Hybrid, Gas, Unknown }

fun engineVisualKind(engineType: String?): EngineVisualKind {
    val e = engineType?.trim()?.uppercase().orEmpty()
    return when {
        e.contains("BEV") || e.contains("ELECTRIC") && !e.contains("HYBRID") -> EngineVisualKind.BatteryElectric
        e.contains("PHEV") || e.contains("PLUGIN") || e.contains("PLUG-IN") -> EngineVisualKind.PlugInHybrid
        e.contains("HEV") || e.contains("HYBRID") -> EngineVisualKind.Hybrid
        e.contains("GAS") || e.contains("ICE") || e.contains("PETROL") -> EngineVisualKind.Gas
        else -> EngineVisualKind.Unknown
    }
}

fun engineDisplayName(engineType: String?): String = when (engineVisualKind(engineType)) {
    EngineVisualKind.BatteryElectric -> "Battery electric"
    EngineVisualKind.PlugInHybrid -> "Plug-in hybrid"
    EngineVisualKind.Hybrid -> "Hybrid"
    EngineVisualKind.Gas -> "Gas"
    EngineVisualKind.Unknown -> engineType?.takeIf { it.isNotBlank() } ?: "Vehicle"
}

fun usesBatteryGauge(engineType: String?): Boolean = when (engineVisualKind(engineType)) {
    EngineVisualKind.BatteryElectric, EngineVisualKind.PlugInHybrid -> true
    else -> false
}

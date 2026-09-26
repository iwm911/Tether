package app.tether.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.tether.ui.theme.TetherTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * The onboarding hero: a phone and a machine joined by the clay tether, with glowing pulses
 * travelling along it. Reacts to the pager [position] (0..2):
 *  0 — pulses flow phone → machine (your machines, over SSH)
 *  1 — amber requests flow back and an approval card rises on the phone (approve from anywhere)
 *  2 — the phone sleeps, the machine keeps working (agents survive the phone)
 */
@Composable
internal fun TetherIllustration(position: () -> Float, modifier: Modifier = Modifier) {
    val colors = TetherTheme.colors
    val scheme = MaterialTheme.colorScheme
    val clay = colors.clay
    val amber = colors.warning
    val green = colors.success
    val paper = scheme.onBackground
    val body = scheme.surfaceContainerHigh
    val screen = colors.codeBg
    val outline = colors.hairline
    val faint = colors.faint

    val t = rememberInfiniteTransition(label = "illo")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "flow")
    val back by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "back")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val blink by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "blink")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "spin")

    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(1300, delayMillis = 150, easing = FastOutSlowInEasing)) }

    val path = remember { Path() }
    val segment = remember { Path() }
    val measure = remember { PathMeasure() }

    Canvas(modifier.semantics { contentDescription = "Your phone, tethered to your machine" }) {
        val pos = position()
        val w1 = (1f - abs(pos - 1f)).coerceIn(0f, 1f)
        val w2 = (1f - abs(pos - 2f)).coerceIn(0f, 1f)
        val introP = intro.value

        // A 100 × 90 unit stage, fitted and centred.
        val u = min(size.width / 100f, size.height / 90f)
        val ox = (size.width - 100f * u) / 2f
        val oy = (size.height - 90f * u) / 2f
        fun o(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
        fun sz(w: Float, h: Float) = Size(w * u, h * u)

        // ── Backdrop: breathing clay glow + faint dot grid.
        drawCircle(
            Brush.radialGradient(
                listOf(clay.copy(alpha = 0.10f + 0.06f * breath + 0.04f * w2), Color.Transparent),
                center = o(50f, 46f), radius = 52f * u,
            ),
            radius = 52f * u, center = o(50f, 46f),
        )
        for (gx in 0..10) for (gy in 0..9) {
            val c = o(gx * 10f, gy * 10f)
            val d = (c - o(50f, 46f)).getDistance() / (60f * u)
            val a = (1f - d).coerceIn(0f, 1f) * 0.9f
            if (a > 0.02f) drawCircle(outline.copy(alpha = outline.alpha * a), radius = 0.45f * u, center = c)
        }

        // ── The tether.
        val start = o(32f, 57f)
        val end = o(56f, 40f)
        path.reset()
        path.moveTo(start.x, start.y)
        val c1 = o(46f, 76f)
        val c2 = o(43f, 24f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
        measure.setPath(path, false)
        val len = measure.length
        segment.reset()
        measure.getSegment(0f, len * introP, segment, true)
        drawPath(segment, clay.copy(alpha = 0.16f), style = Stroke(width = 5.5f * u, cap = StrokeCap.Round))
        drawPath(segment, clay, style = Stroke(width = 1.5f * u, cap = StrokeCap.Round))

        if (introP > 0.98f) {
            // Forward pulses: phone → machine.
            val fwdAlpha = 1f - 0.35f * w1
            for (i in 0 until 3) {
                val f = (flow + i / 3f) % 1f
                pulse(measure.getPosition(len * f), sin(PI.toFloat() * f) * fwdAlpha, clay, paper, u)
            }
            // Requests travelling back: machine → phone (page 2).
            if (w1 > 0.01f) {
                for (i in 0 until 2) {
                    val f = 1f - ((back + i / 2f) % 1f)
                    pulse(measure.getPosition(len * f), sin(PI.toFloat() * f) * w1, amber, paper, u)
                }
            }
        }
        drawCircle(clay, radius = 1.7f * u, center = start)
        drawCircle(clay, radius = 1.7f * u * introP, center = end)

        // ── Phone.
        val phoneC = o(21f, 57f)
        val phoneScale = 1f + 0.012f * breath
        withTransform({ scale(phoneScale, phoneScale, pivot = phoneC) }) {
            val tl = o(11f, 39f)
            drawRoundRect(body, topLeft = tl, size = sz(20f, 36f), cornerRadius = CornerRadius(4.5f * u))
            drawRoundRect(paper.copy(alpha = 0.22f), topLeft = tl, size = sz(20f, 36f), cornerRadius = CornerRadius(4.5f * u), style = Stroke(0.5f * u))
            val sTl = o(12.6f, 41.6f)
            val sSize = sz(16.8f, 30.8f)
            drawRoundRect(screen, topLeft = sTl, size = sSize, cornerRadius = CornerRadius(3f * u))
            drawRoundRect(paper.copy(alpha = 0.3f), topLeft = o(18.5f, 42.6f), size = sz(5f, 1.1f), cornerRadius = CornerRadius(0.6f * u))
            // Chat: assistant lines + a user bubble.
            drawRoundRect(paper.copy(alpha = 0.28f), topLeft = o(14.4f, 46.5f), size = sz(10.5f, 1.4f), cornerRadius = CornerRadius(0.7f * u))
            drawRoundRect(paper.copy(alpha = 0.18f), topLeft = o(14.4f, 49.2f), size = sz(8f, 1.4f), cornerRadius = CornerRadius(0.7f * u))
            drawRoundRect(clay.copy(alpha = 0.55f), topLeft = o(19.5f, 53f), size = sz(8f, 3.2f), cornerRadius = CornerRadius(1.6f * u))
            drawRoundRect(paper.copy(alpha = 0.18f), topLeft = o(14.4f, 58.6f), size = sz(11f, 1.4f), cornerRadius = CornerRadius(0.7f * u))

            // Page 2: approval card rises.
            if (w1 > 0.01f) {
                val lift = (1f - w1) * 5f
                val cTl = o(13.6f, 61.5f + lift)
                drawRoundRect(lerp(screen, amber, 0.12f).copy(alpha = w1), topLeft = cTl, size = sz(14.8f, 9.6f), cornerRadius = CornerRadius(2f * u))
                drawRoundRect(amber.copy(alpha = 0.8f * w1), topLeft = cTl, size = sz(14.8f, 9.6f), cornerRadius = CornerRadius(2f * u), style = Stroke(0.45f * u))
                drawRoundRect(paper.copy(alpha = 0.35f * w1), topLeft = o(15.2f, 63.2f + lift), size = sz(8f, 1.2f), cornerRadius = CornerRadius(0.6f * u))
                drawRoundRect(green.copy(alpha = 0.9f * w1), topLeft = o(15.2f, 66.6f + lift), size = sz(6.2f, 2.6f), cornerRadius = CornerRadius(1.3f * u))
                drawRoundRect(paper.copy(alpha = 0.18f * w1), topLeft = o(22.2f, 66.6f + lift), size = sz(4.4f, 2.6f), cornerRadius = CornerRadius(1.3f * u))
            }
            // Page 3: the phone sleeps.
            if (w2 > 0.01f) {
                val dim = Color.Black.copy(alpha = 0.55f * w2)
                drawRoundRect(dim, topLeft = sTl, size = sSize, cornerRadius = CornerRadius(3f * u))
                val moon = o(21f, 53f)
                val dimmed = lerp(screen, Color.Black, 0.55f * w2)
                drawCircle(paper.copy(alpha = 0.85f * w2), radius = 3.2f * u, center = moon)
                drawCircle(dimmed, radius = 2.7f * u, center = moon + Offset(1.5f * u, -1.1f * u))
            }
        }

        // ── Machine.
        val monTl = o(56f, 25f)
        val monSize = sz(36f, 25f)
        if (w2 > 0.01f) {
            drawRoundRect(
                clay.copy(alpha = (0.10f + 0.08f * breath) * w2),
                topLeft = o(53.5f, 22.5f), size = sz(41f, 30f), cornerRadius = CornerRadius(5f * u),
            )
        }
        drawRoundRect(body, topLeft = monTl, size = monSize, cornerRadius = CornerRadius(3f * u))
        drawRoundRect(paper.copy(alpha = 0.22f), topLeft = monTl, size = monSize, cornerRadius = CornerRadius(3f * u), style = Stroke(0.5f * u))
        drawRoundRect(screen, topLeft = o(57.8f, 26.8f), size = sz(32.4f, 21.4f), cornerRadius = CornerRadius(2f * u))
        // Stand.
        drawRect(body, topLeft = o(72.4f, 50f), size = sz(3.2f, 4.6f))
        drawRoundRect(body, topLeft = o(66f, 54.4f), size = sz(16f, 2.2f), cornerRadius = CornerRadius(1.1f * u))
        drawRoundRect(paper.copy(alpha = 0.18f), topLeft = o(66f, 54.4f), size = sz(16f, 2.2f), cornerRadius = CornerRadius(1.1f * u), style = Stroke(0.4f * u))
        // Prompt: ">" + blinking cursor + a few output lines.
        val chev = Path().apply {
            moveTo(o(60.4f, 30.2f).x, o(60.4f, 30.2f).y)
            lineTo(o(62.4f, 31.8f).x, o(62.4f, 31.8f).y)
            lineTo(o(60.4f, 33.4f).x, o(60.4f, 33.4f).y)
        }
        drawPath(chev, clay, style = Stroke(width = 0.7f * u, cap = StrokeCap.Round))
        if (blink < 0.55f) drawRect(paper.copy(alpha = 0.8f), topLeft = o(63.6f, 32.6f), size = sz(2.4f, 0.8f))
        val lines = listOf(16f, 22f, 12f, 19f)
        lines.forEachIndexed { i, w ->
            drawRoundRect(
                (if (i == 1) clay else paper).copy(alpha = if (i == 1) 0.45f else 0.16f),
                topLeft = o(60.4f, 36.4f + i * 2.6f), size = sz(w, 1.1f), cornerRadius = CornerRadius(0.55f * u),
            )
        }
        // ✻ working spinner — stronger on page 3.
        val sc = o(85.4f, 31.8f)
        val spinAlpha = 0.35f + 0.65f * w2
        rotate(spin, pivot = sc) {
            for (k in 0 until 6) {
                rotate(k * 30f, pivot = sc) {
                    drawLine(
                        clay.copy(alpha = spinAlpha),
                        sc - Offset(0f, 1.8f * u), sc + Offset(0f, 1.8f * u),
                        strokeWidth = 0.6f * u, cap = StrokeCap.Round,
                    )
                }
            }
        }
        // Tiny status LED under the screen.
        drawCircle(green.copy(alpha = 0.55f + 0.45f * breath), radius = 0.55f * u, center = o(74f, 48.8f))
        // Faint reflection line on the desk.
        drawLine(faint.copy(alpha = 0.25f), o(8f, 77f), o(92f, 77f), strokeWidth = 0.35f * u)
    }
}

private fun DrawScope.pulse(center: Offset, alpha: Float, color: Color, core: Color, u: Float) {
    val a = alpha.coerceIn(0f, 1f)
    if (a <= 0.01f) return
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = 0.6f * a), Color.Transparent), center = center, radius = 5.5f * u),
        radius = 5.5f * u, center = center,
    )
    drawCircle(core.copy(alpha = a), radius = 1.05f * u, center = center)
}

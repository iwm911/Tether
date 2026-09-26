package app.tether.ui.lock

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tether.ui.theme.TetherTheme

/**
 * The Tether brand mark, drawn to match the launcher icon: a paper node and a clay node joined
 * by the tether curve. With [breathing] the clay node glows softly.
 */
@Composable
fun TetherMark(modifier: Modifier = Modifier, size: Dp = 64.dp, breathing: Boolean = true) {
    val clay = TetherTheme.colors.clay
    val paper = MaterialTheme.colorScheme.onBackground
    val ink = MaterialTheme.colorScheme.background
    val t = rememberInfiniteTransition(label = "mark")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "breath")
    val b = if (breathing) breath else 0.5f
    Canvas(modifier.size(size).semantics { contentDescription = "Tether" }) {
        // Launcher viewport is 108 units; the mark lives in roughly 24..84. Map that window to our box.
        val u = this.size.minDimension / 60f
        fun p(x: Float, y: Float) = Offset((x - 24f) * u, (y - 24f) * u)
        val a = p(38f, 66f)
        val z = p(70f, 42f)
        val path = Path().apply {
            moveTo(a.x, a.y)
            cubicTo(p(44f, 44f).x, p(44f, 44f).y, p(64f, 64f).x, p(64f, 64f).y, z.x, z.y)
        }
        drawCircle(
            Brush.radialGradient(listOf(clay.copy(alpha = 0.22f + 0.18f * b), Color.Transparent), center = z, radius = 16f * u),
            radius = 16f * u,
            center = z,
        )
        drawPath(path, clay, style = Stroke(width = 5.5f * u, cap = StrokeCap.Round))
        drawCircle(paper, radius = 8f * u, center = a)
        drawCircle(clay, radius = 8f * u * (1f + 0.04f * b), center = z)
        drawCircle(ink, radius = 3f * u, center = z)
    }
}

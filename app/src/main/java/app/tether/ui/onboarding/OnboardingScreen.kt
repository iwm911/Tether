package app.tether.ui.onboarding

import androidx.compose.material3.Icon

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.requiredSize

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.rememberHaptics
import app.tether.ui.lock.TetherMark
import app.tether.ui.theme.Serif
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class OnboardingPage(val eyebrow: String, val headline: String, val body: String)

private val Pages = listOf(
    OnboardingPage(
        eyebrow = "Your machines, over SSH",
        headline = "Claude Code,\non a tether.",
        body = "Tether connects to the computers you already use. Claude runs there, with your code and your tools — the phone is simply the remote.",
    ),
    OnboardingPage(
        eyebrow = "Approve from anywhere",
        headline = "In the loop,\nnot at the desk.",
        body = "When Claude wants to run a command or edit a file, you get a calm prompt: allow once, always allow, or tell it what to do instead.",
    ),
    OnboardingPage(
        eyebrow = "Built to survive the phone",
        headline = "Agents keep going\nwhen you don't.",
        body = "Runs live detached on the machine. Lock the screen, lose signal, come back later — Tether picks up exactly where things are.",
    ),
)

@Composable
fun OnboardingScreen(onAddMachine: () -> Unit, onFinish: () -> Unit) {
    val pager = rememberPagerState { Pages.size }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val isLast by remember { derivedStateOf { pager.currentPage == Pages.lastIndex } }

    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // Wordmark
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(
                    androidx.compose.ui.res.painterResource(app.tether.R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.Unspecified,
                    modifier = Modifier.requiredSize(58.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("Tether", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Serif, fontSize = 21.sp))
        }

        // Illustration behind a full-height pager, so a swipe anywhere turns the page.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TetherIllustration(
                position = { pager.currentPage + pager.currentPageOffsetFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.56f)
                    .padding(horizontal = Space.lg),
            )
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                val p = Pages[page]
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = Space.gutter)
                        .graphicsLayer {
                            val off = (pager.currentPage - page) + pager.currentPageOffsetFraction
                            val d = abs(off).coerceIn(0f, 1f)
                            // Fully transparent before a neighbour page settles: the parallax shift pulls it into view.
                            alpha = (1f - d * 1.25f).coerceAtLeast(0f)
                            translationX = off * size.width * 0.28f
                        },
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(p.eyebrow.uppercase(), style = TetherTheme.type.eyebrow, color = TetherTheme.colors.clay)
                    Spacer(Modifier.height(Space.md))
                    Text(p.headline, style = MaterialTheme.typography.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(Space.md))
                    Text(
                        p.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 520.dp),
                    )
                    Spacer(Modifier.height(Space.xl))
                }
            }
        }

        // Indicator + CTA
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(bottom = Space.md)) {
            PageIndicator(count = Pages.size, position = { pager.currentPage + pager.currentPageOffsetFraction })
            Spacer(Modifier.height(Space.xl))
            AnimatedContent(
                targetState = isLast,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 3 })
                        .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 3 })
                },
                label = "cta",
            ) { last ->
                if (last) {
                    PrimaryButton(
                        "Add your first machine",
                        onClick = { haptics.confirm(); onAddMachine() },
                        icon = Icons.Rounded.Add,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    PrimaryButton(
                        "Continue",
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(Space.xs))
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(isLast, transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(120))) }, label = "skip") { last ->
                    TextButton(onClick = onFinish) {
                        Text(
                            if (last) "I'll look around first" else "Skip",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(count: Int, position: () -> Float) {
    val colors = TetherTheme.colors
    val pos = position()
    Row(
        Modifier.semantics { contentDescription = "Page ${(pos + 0.5f).toInt() + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val sel = (1f - abs(pos - i)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .height(8.dp)
                    .width(lerp(8.dp, 26.dp, sel))
                    .clip(CircleShape)
                    .background(lerp(colors.faint.copy(alpha = 0.35f), colors.clay, sel)),
            )
        }
    }
}

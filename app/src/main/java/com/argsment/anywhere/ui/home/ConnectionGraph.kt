package com.argsment.anywhere.ui.home

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.argsment.anywhere.R
import com.argsment.anywhere.viewmodel.ConnectionGraphNode
import com.argsment.anywhere.viewmodel.ConnectionNodeState
import com.argsment.anywhere.viewmodel.ConnectionStage
import com.argsment.anywhere.vpn.turn.TurnPoolSummary

/*
 * The connection-stage graph in the style of git branches (iOS `ConnectionGraphView`,
 * `ConnectionGraphCollapsedView`, `ConnectionGraphSection`; spec §11.3/§11.4).
 */

/** The only new colour in the design: a passed stage. */
internal val GraphReachedColor = Color(0xFF30D158)
/** The node the connection stalled on (iOS dark-scheme `.red`). */
internal val GraphFailedColor = Color(0xFFFF453A)
/** Red text on the blue/grey home gradient (lighter than systemRed for contrast). */
internal val BypassWarningTextColor = Color(0xFFFF8A80)
private val IdleEdgeColor = Color.White.copy(alpha = 0.14f)
private const val STAGE_ANIMATION_MS = 350

/** Edge drawing order matters: the direct trunk goes on top of the branch. */
private val GraphEdges = listOf(
    ConnectionGraphNode.WHITELIST_CHECK to ConnectionGraphNode.TURN_TUNNEL, // branch-off to the bypass
    ConnectionGraphNode.TURN_TUNNEL to ConnectionGraphNode.CAPTCHA,
    ConnectionGraphNode.CAPTCHA to ConnectionGraphNode.CONNECTED,           // merge
    ConnectionGraphNode.WHITELIST_CHECK to ConnectionGraphNode.CONNECTED,   // direct trunk
    ConnectionGraphNode.VPN_CONNECT to ConnectionGraphNode.WHITELIST_CHECK,
    ConnectionGraphNode.NETWORK_CHECK to ConnectionGraphNode.VPN_CONNECT,
)

/** The system "remove animations" switch, the closest Android has to iOS Reduce Motion. */
@Composable
private fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** 0 → 1 → 0 over 0.85 s each way, or a constant 0 with reduce motion. */
@Composable
private fun pulsePhase(reduceMotion: Boolean): State<Float> {
    if (reduceMotion) return remember { mutableStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "graphPulse")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "graphPulsePhase",
    )
}

private data class DotStyle(val fill: Color, val stroke: Color, val glow: Color)

private fun dotStyle(state: ConnectionNodeState): DotStyle = when (state) {
    ConnectionNodeState.OFF -> DotStyle(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.20f), Color.Transparent)
    ConnectionNodeState.SKIPPED -> DotStyle(Color.White.copy(alpha = 0.06f), GraphReachedColor.copy(alpha = 0.40f), Color.Transparent)
    ConnectionNodeState.DONE -> DotStyle(GraphReachedColor, Color.Transparent, GraphReachedColor.copy(alpha = 0.45f))
    ConnectionNodeState.CURRENT -> DotStyle(GraphReachedColor, Color.Transparent, GraphReachedColor.copy(alpha = 0.75f))
    // A glow without a pulse: failure is final, there is nothing to blink about.
    ConnectionNodeState.FAILED -> DotStyle(GraphFailedColor, Color.Transparent, GraphFailedColor.copy(alpha = 0.65f))
    // Connected the wrong way (directly although the backup route was needed): empty, white.
    ConnectionNodeState.HOLLOW -> DotStyle(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent)
}

/** Animated per-node dot colours and per-edge colours, in [ConnectionGraphNode.entries] / [GraphEdges] order. */
private class GraphColors(val dots: List<DotStyle>, val edges: List<Color>)

@Composable
private fun animatedGraphColors(stage: ConnectionStage): GraphColors {
    val spec = tween<Color>(STAGE_ANIMATION_MS, easing = FastOutSlowInEasing)
    val dots = ConnectionGraphNode.entries.map { node ->
        val target = dotStyle(stage.state(node))
        val fill by animateColorAsState(target.fill, spec, label = "fill-$node")
        val stroke by animateColorAsState(target.stroke, spec, label = "stroke-$node")
        val glow by animateColorAsState(target.glow, spec, label = "glow-$node")
        DotStyle(fill, stroke, glow)
    }
    val edges = GraphEdges.map { (from, to) ->
        val color by animateColorAsState(
            if (stage.isEdgeTraversed(from, to)) GraphReachedColor else IdleEdgeColor,
            spec,
            label = "edge-$from-$to",
        )
        color
    }
    return GraphColors(dots, edges)
}

/** A soft glow standing in for SwiftUI's `.shadow(color:radius:)`. */
private fun DrawScope.glow(center: Offset, dotRadius: Float, blur: Float, color: Color) {
    if (color.alpha <= 0f) return
    val radius = dotRadius + blur
    drawCircle(
        brush = Brush.radialGradient(
            0f to color,
            (dotRadius / radius) to color.copy(alpha = color.alpha * 0.6f),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

private fun DrawScope.dot(center: Offset, diameter: Float, strokeWidth: Float, style: DotStyle) {
    val r = diameter / 2
    drawCircle(style.fill, radius = r, center = center)
    if (style.stroke.alpha > 0f) {
        // `strokeBorder`: the stroke sits inside the circle.
        drawCircle(style.stroke, radius = r - strokeWidth / 2, center = center, style = Stroke(strokeWidth))
    }
}

/** The text for the stage's current node, for accessibility. */
@Composable
private fun accessibilitySummary(stage: ConnectionStage, pool: TurnPoolSummary?): String {
    val context = LocalContext.current
    val current = stage.currentNode ?: return stringResource(R.string.graph_a11y_idle)
    return stringResource(R.string.graph_a11y_stage, current.title(pool).text(context))
}

// MARK: - Expanded graph

private object Expanded {
    val height = 210.dp
    val trunkX = 14.dp
    val branchX = 62.dp
    val labelX = 86.dp
    val firstRowY = 20.dp
    val rowSpacing = 34.dp
    val dotSize = 12.dp
    val edgeWidth = 2.dp

    fun centerX(node: ConnectionGraphNode): Dp = if (node.isOnBypassBranch) branchX else trunkX
    fun centerY(node: ConnectionGraphNode): Dp = firstRowY + rowSpacing * (node.order - 1)
}

/**
 * The full graph, 210 dp tall. [onNodeClick] receives taps on a node's row (used to reopen
 * the captcha); everything else falls through to the section's toggle.
 */
@Composable
fun ConnectionGraphView(
    stage: ConnectionStage,
    pool: TurnPoolSummary?,
    modifier: Modifier = Modifier,
    onNodeClick: ((ConnectionGraphNode) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val colors = animatedGraphColors(stage)
    val pulse by pulsePhase(reduceMotion())
    val summary = accessibilitySummary(stage, pool)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Expanded.height)
            .clearAndSetSemantics { contentDescription = summary }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            fun center(node: ConnectionGraphNode) =
                Offset(Expanded.centerX(node).toPx(), Expanded.centerY(node).toPx())

            GraphEdges.forEachIndexed { index, (from, to) ->
                val a = center(from)
                val b = center(to)
                val path = Path().apply {
                    moveTo(a.x, a.y)
                    if (a.x == b.x) {
                        lineTo(b.x, b.y)
                    } else {
                        // The bend fits exactly into one row step, as in the mock-up.
                        val c = 19.dp.toPx()
                        cubicTo(a.x, a.y + c, b.x, b.y - c, b.x, b.y)
                    }
                }
                drawPath(path, colors.edges[index], style = Stroke(Expanded.edgeWidth.toPx(), cap = StrokeCap.Round))
            }

            val dotPx = Expanded.dotSize.toPx()
            ConnectionGraphNode.entries.forEachIndexed { index, node ->
                val c = center(node)
                val state = stage.state(node)
                if (state == ConnectionNodeState.CURRENT) {
                    // A pulsing ring around the current node.
                    val ring = dotPx + (8 + 10 * pulse).dp.toPx()
                    drawCircle(
                        GraphReachedColor.copy(alpha = 0.18f - 0.13f * pulse),
                        radius = ring / 2,
                        center = c,
                    )
                }
                val style = colors.dots[index]
                glow(c, dotPx / 2, (if (state == ConnectionNodeState.CURRENT) 9 else 5).dp.toPx(), style.glow)
                dot(c, dotPx, 2.dp.toPx(), style)
            }
        }

        ConnectionGraphNode.entries.forEach { node ->
            val state = stage.state(node)
            val emphasised = state == ConnectionNodeState.CURRENT || state == ConnectionNodeState.FAILED
            val labelColor by animateColorAsState(
                when (state) {
                    ConnectionNodeState.OFF, ConnectionNodeState.SKIPPED -> Color.White.copy(alpha = 0.45f)
                    ConnectionNodeState.DONE, ConnectionNodeState.HOLLOW -> Color.White.copy(alpha = 0.68f)
                    ConnectionNodeState.CURRENT, ConnectionNodeState.FAILED -> Color.White
                },
                tween(STAGE_ANIMATION_MS),
                label = "label-$node",
            )
            val rowModifier = if (onNodeClick != null) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onNodeClick(node) }
            } else Modifier
            val caption = stage.nodeCaption(node)
            Text(
                text = stage.nodeTitleOverride(node)?.let { context.getString(it) } ?: node.title(pool).text(context),
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Normal,
                color = if (state == ConnectionNodeState.FAILED && caption != null) BypassWarningTextColor else labelColor,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .offset(x = Expanded.labelX, y = Expanded.centerY(node) - (if (caption != null) 12 else 8).dp)
                    .then(rowModifier),
            )
            if (caption != null) {
                Text(
                    text = context.getString(caption),
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Padding, not an x offset: the line must ellipsize at the card's edge.
                    modifier = Modifier
                        .padding(start = Expanded.labelX, end = 8.dp)
                        .offset(y = Expanded.centerY(node) + 3.dp),
                )
            }
        }
    }
}

// MARK: - Collapsed graph

private object Mini {
    val width = 92.dp
    val height = 28.dp
    val dotSize = 7.dp
    val edgeWidth = 1.5.dp
    val trunkY = 20.dp
    val branchY = 8.dp

    fun centerX(node: ConnectionGraphNode): Dp = 6.dp + 16.dp * (node.order - 1)
    fun centerY(node: ConnectionGraphNode): Dp = if (node.isOnBypassBranch) branchY else trunkY
}

/** The collapsed graph's text (spec §11.4). */
@Composable
private fun collapsedSummary(stage: ConnectionStage, pool: TurnPoolSummary?, serverName: String? = null): String =
    stage.collapsedText(LocalContext.current, pool, serverName)

/** Red for a backup-route failure; white for "this server has none". */
private fun collapsedColor(stage: ConnectionStage): Color =
    if (stage is ConnectionStage.ConnectedWithoutBypass && stage.reason.isError) BypassWarningTextColor
    else Color.White.copy(alpha = 0.68f)

/** One 44 dp row: the mini graph, the stage text and a chevron. */
@Composable
fun ConnectionGraphCollapsedView(
    stage: ConnectionStage,
    pool: TurnPoolSummary?,
    modifier: Modifier = Modifier,
    serverName: String? = null,
) {
    val colors = animatedGraphColors(stage)
    val pulse by pulsePhase(reduceMotion())
    val summary = collapsedSummary(stage, pool, serverName)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(modifier = Modifier.size(Mini.width, Mini.height)) {
            fun center(node: ConnectionGraphNode) =
                Offset(Mini.centerX(node).toPx(), Mini.centerY(node).toPx())

            GraphEdges.forEachIndexed { index, (from, to) ->
                val a = center(from)
                val b = center(to)
                val path = Path().apply {
                    moveTo(a.x, a.y)
                    if (a.y == b.y) {
                        lineTo(b.x, b.y)
                    } else {
                        val c = 8.dp.toPx()
                        cubicTo(a.x, a.y - c, b.x, b.y + c, b.x, b.y)
                    }
                }
                drawPath(path, colors.edges[index], style = Stroke(Mini.edgeWidth.toPx(), cap = StrokeCap.Round))
            }

            val dotPx = Mini.dotSize.toPx()
            ConnectionGraphNode.entries.forEachIndexed { index, node ->
                val c = center(node)
                if (stage.state(node) == ConnectionNodeState.CURRENT) {
                    val ring = dotPx + (6 + 6 * pulse).dp.toPx()
                    drawCircle(GraphReachedColor.copy(alpha = 0.18f - 0.13f * pulse), radius = ring / 2, center = c)
                }
                dot(c, dotPx, 1.5.dp.toPx(), colors.dots[index])
            }
        }

        Text(
            text = summary,
            fontSize = 13.sp,
            color = collapsedColor(stage),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.40f),
            modifier = Modifier.size(18.dp),
        )
    }
}

// MARK: - Section

/**
 * Expanded or collapsed — only the user decides. Connecting, a failure or going idle
 * change what the graph shows, never whether it is open. The choice is persisted
 * ([expanded] comes from `connectionGraphExpanded`).
 *
 * Tapping the captcha node while a captcha waits (or "Connected · Captcha for More
 * Peers") reopens the captcha sheet instead of collapsing the graph.
 */
@Composable
fun ConnectionGraphSection(
    stage: ConnectionStage,
    pool: TurnPoolSummary?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    captchaWaiting: Boolean,
    onShowCaptcha: () -> Unit,
    modifier: Modifier = Modifier,
    serverName: String? = null,
) {
    val expandHint = stringResource(R.string.graph_a11y_expand_hint)
    val collapseHint = stringResource(R.string.graph_a11y_collapse_hint)
    val summary = if (expanded) accessibilitySummary(stage, pool) else collapsedSummary(stage, pool, serverName)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(tween(STAGE_ANIMATION_MS, easing = FastOutSlowInEasing))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onExpandedChange(!expanded) }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = summary
                onClick(label = if (expanded) collapseHint else expandHint) {
                    onExpandedChange(!expanded); true
                }
            }
    ) {
        if (expanded) {
            Box(modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp)) {
                ConnectionGraphView(
                    stage = stage,
                    pool = pool,
                    onNodeClick = { node ->
                        if (opensCaptcha(node, stage, pool, captchaWaiting)) {
                            onShowCaptcha()
                            true
                        } else {
                            onExpandedChange(false)
                            false
                        }
                    },
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.40f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .width(18.dp)
                        .height(18.dp),
                )
            }
        } else {
            ConnectionGraphCollapsedView(stage = stage, pool = pool, serverName = serverName)
        }
    }
}

/** A tap on [node] should reopen the captcha sheet rather than toggle the graph. */
internal fun opensCaptcha(
    node: ConnectionGraphNode,
    stage: ConnectionStage,
    pool: TurnPoolSummary?,
    captchaWaiting: Boolean,
): Boolean {
    if (!captchaWaiting) return false
    return when (node) {
        ConnectionGraphNode.CAPTCHA -> stage == ConnectionStage.Captcha
        ConnectionGraphNode.CONNECTED -> stage is ConnectionStage.ConnectedViaTurn && pool?.needsCaptchaForMorePeers == true
        else -> false
    }
}

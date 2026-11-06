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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
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
 * The connection stages on the home screen, after the mock-up (`.graphrow` collapsed,
 * `.xg` expanded): one 44 dp plaque with a state dot and a line of text, which a tap
 * turns into a card listing the stages top to bottom.
 */

/** A passed stage. */
internal val GraphReachedColor = Color(0xFF30D158)
/** The node the connection stalled on (iOS dark-scheme `.red`). */
internal val GraphFailedColor = Color(0xFFFF453A)
/** Red text on the blue/grey home gradient (lighter than systemRed for contrast). */
internal val BypassWarningTextColor = Color(0xFFFF8A80)
private const val STAGE_ANIMATION_MS = 350

/** Plaque and card: white 10 % over the gradient. */
private val PanelBackground = Color.White.copy(alpha = 0.10f)
private val CollapsedShape = RoundedCornerShape(14.dp)
private val ExpandedShape = RoundedCornerShape(16.dp)

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

/** fill / ring / glow of a state dot. */
private data class DotStyle(val fill: Color, val stroke: Color, val glow: Color)

private fun dotStyle(state: ConnectionNodeState): DotStyle = when (state) {
    // Not reached: a faint white dot (mock-up `.dot.idle`, white 35 %).
    ConnectionNodeState.OFF -> DotStyle(Color.White.copy(alpha = 0.35f), Color.Transparent, Color.Transparent)
    ConnectionNodeState.SKIPPED -> DotStyle(Color.Transparent, GraphReachedColor.copy(alpha = 0.55f), Color.Transparent)
    ConnectionNodeState.DONE -> DotStyle(GraphReachedColor, Color.Transparent, GraphReachedColor.copy(alpha = 0.70f))
    ConnectionNodeState.CURRENT -> DotStyle(GraphReachedColor, Color.Transparent, GraphReachedColor.copy(alpha = 0.70f))
    // A glow without a pulse: failure is final, there is nothing to blink about.
    ConnectionNodeState.FAILED -> DotStyle(GraphFailedColor, Color.Transparent, GraphFailedColor.copy(alpha = 0.75f))
    // Connected the wrong way (directly although the backup route was needed): empty, white.
    ConnectionNodeState.HOLLOW -> DotStyle(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent)
}

/**
 * The one dot of the collapsed plaque: the state of the whole connection rather than of
 * one node — grey at rest, green while it runs and once connected, red on a failure,
 * hollow when connected without a backup route that is merely absent for this server.
 */
internal fun collapsedDotState(stage: ConnectionStage): ConnectionNodeState = when (stage) {
    ConnectionStage.Idle -> ConnectionNodeState.OFF
    is ConnectionStage.Failed -> ConnectionNodeState.FAILED
    is ConnectionStage.ConnectedWithoutBypass ->
        if (stage.reason.isError) ConnectionNodeState.FAILED else ConnectionNodeState.HOLLOW
    else -> if (stage.isConnected) ConnectionNodeState.DONE else ConnectionNodeState.CURRENT
}

/** A soft glow standing in for CSS `box-shadow: 0 0 6px`. */
private fun DrawScope.glow(center: Offset, dotRadius: Float, blur: Float, color: Color) {
    if (color.alpha <= 0f) return
    val radius = dotRadius + blur
    drawCircle(
        brush = Brush.radialGradient(
            0f to color,
            (dotRadius / radius) to color.copy(alpha = color.alpha * 0.5f),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** A state dot of [diameter] with its glow (6 dp) and, for the running stage, a pulsing halo. */
@Composable
private fun StateDot(state: ConnectionNodeState, diameter: Dp, modifier: Modifier = Modifier) {
    val target = dotStyle(state)
    val spec = tween<Color>(STAGE_ANIMATION_MS, easing = FastOutSlowInEasing)
    val fill by animateColorAsState(target.fill, spec, label = "dotFill")
    val stroke by animateColorAsState(target.stroke, spec, label = "dotStroke")
    val glowColor by animateColorAsState(target.glow, spec, label = "dotGlow")
    val pulse by pulsePhase(reduceMotion())
    val running = state == ConnectionNodeState.CURRENT
    // The canvas is larger than the dot so the glow and the halo are not clipped.
    Canvas(modifier = modifier.size(diameter)) {
        val c = center
        val r = diameter.toPx() / 2
        if (running) {
            drawCircle(
                GraphReachedColor.copy(alpha = 0.22f - 0.16f * pulse),
                radius = r + (3 + 3 * pulse).dp.toPx(),
                center = c,
            )
        }
        glow(c, r, 6.dp.toPx(), glowColor)
        drawCircle(fill, radius = r, center = c)
        if (stroke.alpha > 0f) {
            val w = 1.5.dp.toPx()
            drawCircle(stroke, radius = r - w / 2, center = c, style = Stroke(w))
        }
    }
}

/** The text for the stage's current node, for accessibility. */
@Composable
private fun accessibilitySummary(stage: ConnectionStage, pool: TurnPoolSummary?): String {
    val context = LocalContext.current
    val current = stage.currentNode ?: return stringResource(R.string.graph_a11y_idle)
    return stringResource(R.string.graph_a11y_stage, current.title(pool).text(context))
}

// MARK: - Expanded card (`.xg`)

/**
 * The nodes the card lists: the direct trunk always, the backup-route branch only once it
 * is in play (a stage on it was reached, failed or is pending) — a direct connection reads
 * as four steps, not six.
 */
internal fun visibleNodes(stage: ConnectionStage): List<ConnectionGraphNode> =
    ConnectionGraphNode.entries.filter { node ->
        !node.isOnBypassBranch || stage.state(node).let {
            it != ConnectionNodeState.OFF && it != ConnectionNodeState.SKIPPED
        }
    }

/**
 * The stages as a list, radius 16, white 10 %: a 9 dp dot, a 15 sp title and an optional
 * 13 sp caption per node, joined by a hairline. [onNodeClick] receives taps on a node's
 * row (used to reopen the captcha); everything else falls through to the section's toggle.
 */
@Composable
fun ConnectionGraphView(
    stage: ConnectionStage,
    pool: TurnPoolSummary?,
    modifier: Modifier = Modifier,
    onNodeClick: ((ConnectionGraphNode) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val summary = accessibilitySummary(stage, pool)
    val nodes = visibleNodes(stage)
    val connector = Color.White.copy(alpha = 0.18f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ExpandedShape)
            .background(PanelBackground)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = summary },
    ) {
        nodes.forEachIndexed { index, node ->
            val state = stage.state(node)
            val caption = stage.nodeCaption(node)
            val warn = state == ConnectionNodeState.FAILED
            val titleColor by animateColorAsState(
                when {
                    warn -> BypassWarningTextColor
                    state == ConnectionNodeState.OFF || state == ConnectionNodeState.SKIPPED -> Color.White.copy(alpha = 0.45f)
                    else -> Color.White.copy(alpha = 0.88f)
                },
                tween(STAGE_ANIMATION_MS),
                label = "title-$node",
            )
            val rowModifier = if (onNodeClick != null) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onNodeClick(node) }
            } else Modifier
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(rowModifier)
                    // `.st + .st::before`: a 1 dp line from the gap above down to this dot.
                    .drawBehind {
                        if (index > 0) {
                            val x = 4.5.dp.toPx()
                            drawLine(
                                connector,
                                start = Offset(x, -9.dp.toPx()),
                                end = Offset(x, 9.dp.toPx()),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    }
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StateDot(state = state, diameter = 9.dp, modifier = Modifier.padding(top = 6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stage.nodeTitleOverride(node)?.let { context.getString(it) }
                            ?: node.title(pool).text(context),
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = if (state == ConnectionNodeState.CURRENT) FontWeight.SemiBold else FontWeight.Normal,
                        color = titleColor,
                    )
                    if (caption != null) {
                        Text(
                            text = context.getString(caption),
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            color = Color.White.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Collapsed plaque (`.graphrow`)

/** The collapsed plaque's text (spec §11.4). */
@Composable
private fun collapsedSummary(stage: ConnectionStage, pool: TurnPoolSummary?, serverName: String? = null): String =
    stage.collapsedText(LocalContext.current, pool, serverName)

/** Red for a failure or a broken backup route; white for everything else. */
private fun collapsedColor(stage: ConnectionStage): Color = when {
    stage is ConnectionStage.ConnectedWithoutBypass && stage.reason.isError -> BypassWarningTextColor
    stage is ConnectionStage.Failed -> BypassWarningTextColor
    else -> Color.White.copy(alpha = 0.85f)
}

/** One 44 dp plaque, radius 14, white 10 %: a 8 dp state dot and one line of 14 sp text. */
@Composable
fun ConnectionGraphCollapsedView(
    stage: ConnectionStage,
    pool: TurnPoolSummary?,
    modifier: Modifier = Modifier,
    serverName: String? = null,
) {
    val summary = collapsedSummary(stage, pool, serverName)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CollapsedShape)
            .background(PanelBackground)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StateDot(state = collapsedDotState(stage), diameter = 8.dp)
        Text(
            text = summary,
            fontSize = 14.sp,
            color = collapsedColor(stage),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// MARK: - Section

/**
 * Expanded or collapsed — only the user decides. Connecting, a failure or going idle
 * change what the stages show, never whether they are open. The choice is persisted
 * ([expanded] comes from `connectionGraphExpanded`).
 *
 * Tapping the captcha node while a captcha waits (or "Connected · Captcha for More
 * Peers") reopens the captcha sheet instead of collapsing the card.
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
        } else {
            ConnectionGraphCollapsedView(stage = stage, pool = pool, serverName = serverName)
        }
    }
}

/** A tap on [node] should reopen the captcha sheet rather than toggle the card. */
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

package com.argsment.anywhere.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.argsment.anywhere.R
import com.argsment.anywhere.turn.BypassReason
import com.argsment.anywhere.turn.SubscriptionBypassState
import com.argsment.anywhere.turn.TurnMode

/*
 * The "bridge through a relay" mark next to a subscription's name (mock-up variant A):
 * two supports and an arch with a relay dot on top. Drawn on a 24-unit grid.
 *   available — solid arch + relay, white 78 %
 *   none      — the arch as seven dots, no relay, white 34 %
 *   error     — the arch broken in the middle, no relay, red
 */

/** The icon's red: systemRed's high-contrast variant, readable on the blue gradient. */
internal val BypassErrorIconColor = Color(0xFFFF6961)

enum class BypassGlyph { AVAILABLE, NONE, ERROR }

/** Points t = 1/8…7/8 of the arch's curve (from the mock-up): the "none" dots. */
private val DottedArch = listOf(
    4.7f to 13.6f, 6.5f to 10.4f, 9.1f to 8.5f, 12f to 7.9f, 14.9f to 8.5f, 17.5f to 10.4f, 19.3f to 13.6f,
)

private fun DrawScope.drawBypassGlyph(glyph: BypassGlyph, color: Color) {
    val k = size.minDimension / 24f
    fun p(x: Float, y: Float) = Offset(x * k, y * k)
    val stroke = Stroke(width = 2.4f * k, cap = StrokeCap.Round)
    when (glyph) {
        BypassGlyph.AVAILABLE -> {
            val arch = Path().apply {
                moveTo(4 * k, 18 * k)
                cubicTo(4 * k, 4.5f * k, 20 * k, 4.5f * k, 20 * k, 18 * k)
            }
            drawPath(arch, color, style = stroke)
            drawCircle(color, 2.6f * k, p(12f, 7.9f))
        }
        BypassGlyph.NONE -> DottedArch.forEach { (x, y) -> drawCircle(color, 1.2f * k, p(x, y)) }
        BypassGlyph.ERROR -> {
            val left = Path().apply {
                moveTo(4 * k, 18 * k)
                cubicTo(4 * k, 13.4f * k, 5.8f * k, 10.4f * k, 8.3f * k, 8.9f * k)
            }
            val right = Path().apply {
                moveTo(15.7f * k, 8.9f * k)
                cubicTo(18.2f * k, 10.4f * k, 20 * k, 13.4f * k, 20 * k, 18 * k)
            }
            drawPath(left, color, style = stroke)
            drawPath(right, color, style = stroke)
        }
    }
    drawCircle(color, 2.8f * k, p(4f, 18f))
    drawCircle(color, 2.8f * k, p(20f, 18f))
}

/** The bare mark, [size] square. */
@Composable
fun BypassMark(glyph: BypassGlyph, modifier: Modifier = Modifier, size: Dp = 12.dp, color: Color = Color.White) {
    Canvas(modifier = modifier.size(size)) { drawBypassGlyph(glyph, color) }
}

private data class BypassCopy(val glyph: BypassGlyph, val title: Int, val body: Int, val a11y: Int)

private fun copyFor(state: SubscriptionBypassState, mode: TurnMode?): BypassCopy? = when (state) {
    SubscriptionBypassState.Unknown -> null
    SubscriptionBypassState.Available -> BypassCopy(
        BypassGlyph.AVAILABLE,
        R.string.bypass_popover_available_title,
        when (mode) {
            TurnMode.ON -> R.string.bypass_popover_available_body_on
            TurnMode.OFF -> R.string.bypass_popover_available_body_off
            else -> R.string.bypass_popover_available_body
        },
        R.string.bypass_a11y_available,
    )
    SubscriptionBypassState.None -> BypassCopy(
        BypassGlyph.NONE, R.string.bypass_popover_none_title, R.string.bypass_popover_none_body, R.string.bypass_a11y_none,
    )
    is SubscriptionBypassState.Error -> {
        val body = when (state.reason) {
            BypassReason.SUBSCRIPTION_NOT_FOUND -> R.string.bypass_popover_not_found_body
            BypassReason.NO_VK_LINK -> R.string.bypass_popover_no_vk_link_body
            else -> R.string.bypass_popover_load_failed_body
        }
        BypassCopy(BypassGlyph.ERROR, state.reason.collapsedRes, body, state.reason.collapsedRes)
    }
}

/**
 * The subscription's mark with a 32 dp tap target (no visible chip) and a two-line
 * popover. Nothing is drawn for [SubscriptionBypassState.Unknown].
 *
 * @param mode the stored TURN mode, for the popover's second sentence.
 */
@Composable
fun SubscriptionBypassIcon(state: SubscriptionBypassState, mode: TurnMode?, modifier: Modifier = Modifier) {
    val copy = copyFor(state, mode) ?: return
    var open by remember { mutableStateOf(false) }
    // Below the 32 dp tap target, with a small gap.
    val popupOffsetY = with(LocalDensity.current) { 36.dp.roundToPx() }
    val label = stringResource(copy.a11y)
    val hint = stringResource(R.string.bypass_a11y_hint)
    val baseAlpha = when (copy.glyph) {
        BypassGlyph.AVAILABLE -> 0.78f
        BypassGlyph.NONE -> 0.34f
        BypassGlyph.ERROR -> 1f
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = hint,
            ) { open = true }
            .semantics { contentDescription = label },
    ) {
        BypassMark(
            glyph = copy.glyph,
            color = if (copy.glyph == BypassGlyph.ERROR) BypassErrorIconColor else Color.White,
            // While the popover is open the mark brightens, showing what it belongs to.
            modifier = Modifier.alpha(if (open) (if (copy.glyph == BypassGlyph.NONE) 0.6f else 1f) else baseAlpha),
        )
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, popupOffsetY),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        .width(240.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color(0xF72C3038))
                        .clickable { open = false }
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                ) {
                    Text(
                        text = stringResource(copy.title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (copy.glyph == BypassGlyph.ERROR) BypassWarningTextColor else Color.White,
                    )
                    Text(
                        text = stringResource(copy.body),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

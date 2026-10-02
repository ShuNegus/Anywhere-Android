package com.argsment.anywhere.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.argsment.anywhere.R

private val CircleDiameter = 140.dp
private val RingColor = Color.Cyan

/**
 * The home screen's power button (iOS `HomeView.PowerButton`, spec §8/§11.1).
 *
 * A tap toggles the VPN; holding it for two seconds fills a ring around it, gives a strong
 * haptic and calls [onLongPress] (connect through TURN) — the tap that ends the hold is
 * swallowed. While the session is forced through TURN the ring stays drawn at 0.55.
 */
@Composable
fun PowerButton(
    isConnected: Boolean,
    isTransitioning: Boolean,
    enabled: Boolean,
    isTurnForced: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val hold = remember { ForceTurnHold() }
    var isHolding by remember { mutableStateOf(false) }

    // Linear 0→1 over the hold; snaps back with a short ease-out on release.
    val ringTrim = remember { Animatable(0f) }
    LaunchedEffect(isHolding) {
        if (isHolding) {
            ringTrim.snapTo(0f)
            ringTrim.animateTo(1f, tween(ForceTurnHold.HOLD_MILLIS.toInt(), easing = LinearEasing))
        } else {
            ringTrim.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
        }
    }
    val forcedRingAlpha by animateFloatAsState(
        targetValue = if (isTurnForced && !isHolding) 0.55f else 0f,
        animationSpec = tween(200),
        label = "forcedRing",
    )
    val glow by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0f,
        animationSpec = tween(600),
        label = "glow",
    )

    val forceTurnHint = stringResource(R.string.home_power_force_turn_hint)
    val forceTurnAction = stringResource(R.string.home_power_force_turn_action)
    val statusDescription = stringResource(if (isConnected) R.string.connected else R.string.disconnected)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(CircleDiameter + 20.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = "$statusDescription. $forceTurnHint"
                if (!enabled) disabled()
                onClick { if (currentEnabled) currentOnClick(); currentEnabled }
                onLongClick(label = forceTurnAction) {
                    if (currentEnabled) currentOnLongPress()
                    currentEnabled
                }
                customActions = listOf(
                    CustomAccessibilityAction(forceTurnAction) {
                        if (currentEnabled) currentOnLongPress()
                        currentEnabled
                    }
                )
            }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(CircleDiameter)
                .shadow(
                    elevation = (8 + 16 * glow).dp,
                    shape = CircleShape,
                    ambientColor = if (isConnected) Color.Cyan.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.08f),
                    spotColor = if (isConnected) Color.Cyan.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.08f),
                )
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        hold.press()
                        isHolding = true
                        var finished = false
                        val up = withTimeoutOrNull(ForceTurnHold.HOLD_MILLIS) {
                            waitForUpOrCancellation().also { finished = true }
                        }
                        if (finished) {
                            isHolding = false
                            val outcome = hold.release(cancelled = up == null, enabled = currentEnabled)
                            if (up != null) up.consume()
                            if (outcome == ForceTurnHold.Release.TAP) currentOnClick()
                        } else {
                            // The hold completed with the finger still down.
                            isHolding = false
                            if (hold.holdElapsed(currentEnabled)) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnLongPress()
                            }
                            val release = waitForUpOrCancellation()
                            release?.consume()
                            hold.release(cancelled = release == null, enabled = currentEnabled)
                        }
                    }
                }
        ) {
            if (isTransitioning) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
        }

        // Hold ring: diameter + 10, stroke 3, starting at the top.
        Canvas(modifier = Modifier.size(CircleDiameter + 10.dp)) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            if (forcedRingAlpha > 0f) {
                drawArc(
                    color = RingColor.copy(alpha = forcedRingAlpha),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            if (ringTrim.value > 0f) {
                drawArc(
                    color = RingColor.copy(alpha = 0.9f),
                    startAngle = -90f, sweepAngle = 360f * ringTrim.value, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

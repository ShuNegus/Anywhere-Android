package com.argsment.anywhere.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.argsment.anywhere.R
import com.argsment.anywhere.data.network.LatencyResult
import com.argsment.anywhere.turn.SubscriptionBypassState
import com.argsment.anywhere.turn.TurnMode
import java.util.UUID

/*
 * The persistent server list that replaced the picker menu (iOS `ServerListSection`,
 * spec §11.5). All colours are white-on-gradient: the home screen is always dark.
 */

private val RowHeight = 52.dp
private val RowPadding = 16.dp
private val FlagWidth = 24.dp
private val ItemSpacing = 12.dp
private val PingWidth = 46.dp
private val TickWidth = 20.dp
/** RowPadding + FlagWidth + ItemSpacing: separators start where the name does. */
private val SeparatorInset = 52.dp
internal val CardShape = RoundedCornerShape(16.dp)
internal val CardBackground = Color.White.copy(alpha = 0.10f)

@Composable
fun ServerListSection(
    sections: List<ServerListSectionModel>,
    selectedId: UUID?,
    latencies: Map<UUID, LatencyResult>,
    isMeasuring: Boolean,
    onSelect: (UUID) -> Unit,
    onMeasure: () -> Unit,
    onAddSubscription: () -> Unit,
    onDeleteSubscription: (UUID) -> Unit,
    modifier: Modifier = Modifier,
    /** Refresh from the header button. Available without Pro Mode, which hides Proxies. */
    onRefreshSubscription: (UUID) -> Unit = {},
    /** The subscription being refreshed: it shows a spinner, the others are disabled. */
    refreshingSubscription: UUID? = null,
    /** Backup-route mark per subscription; a missing entry draws nothing. */
    bypassStates: Map<UUID, SubscriptionBypassState> = emptyMap(),
    /** The stored TURN mode, for the mark's popover. */
    bypassMode: TurnMode? = null,
    /**
     * Servers that have a backup route, marked in subscriptions where not every server
     * has one (so the user can pick one that does).
     */
    bypassServerIds: Set<UUID> = emptySet(),
) {
    if (sections.isEmpty()) {
        ServerListEmptyState(onAddSubscription = onAddSubscription, modifier = modifier)
        return
    }

    // At most one header is swiped open; a changed section set closes it.
    var swipedSection by remember { mutableStateOf<UUID?>(null) }
    val sectionIds = sections.map { it.id }
    LaunchedEffect(sectionIds) { swipedSection = null }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListHeader(isMeasuring = isMeasuring, onMeasure = onMeasure)

        sections.forEachIndexed { index, section ->
            val header = section.header
            if (header != null) {
                val headerModifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp)
                val subscriptionId = section.subscriptionId
                if (subscriptionId != null) {
                    SwipeableSubscriptionHeader(
                        title = header,
                        isOpen = swipedSection == subscriptionId,
                        onOpenChange = { open -> swipedSection = if (open) subscriptionId else null },
                        onDelete = {
                            swipedSection = null
                            onDeleteSubscription(subscriptionId)
                        },
                        isRefreshing = refreshingSubscription == subscriptionId,
                        refreshEnabled = refreshingSubscription == null,
                        onRefresh = { onRefreshSubscription(subscriptionId) },
                        bypassState = bypassStates[subscriptionId],
                        bypassMode = bypassMode,
                        modifier = headerModifier,
                    )
                } else {
                    // Chains: a header, but not a subscription — nothing to delete.
                    SectionTitle(header, modifier = headerModifier.height(32.dp))
                }
            }
            SectionCard(section, selectedId, latencies, onSelect, bypassServerIds)
        }
    }
}

@Composable
private fun ListHeader(isMeasuring: Boolean, onMeasure: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.server_list_header),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.padding(start = 12.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        val measureLabel = stringResource(R.string.server_list_measure_ping)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset(x = 6.dp) // the circle's visual edge lines up with the card's
                .size(44.dp)
                .alpha(if (isMeasuring) 0.65f else 1f)
                .clickable(enabled = !isMeasuring, role = Role.Button, onClick = onMeasure)
                .semantics { contentDescription = measureLabel },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
            ) {
                if (isMeasuring) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Speed,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * A subscription's header: name on the left, flat refresh and trash on the right, and a swipe to
 * the left that reveals a red delete button (dragging past 160 dp deletes outright).
 * The drag only claims clearly horizontal movement, so vertical scrolling still works.
 */
@Composable
private fun SwipeableSubscriptionHeader(
    title: String,
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    isRefreshing: Boolean,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
    bypassState: SubscriptionBypassState?,
    bypassMode: TurnMode?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragTranslation by remember { mutableFloatStateOf(0f) } // dp
    val liveOffset = SubscriptionSwipe.offset(isOpen, if (dragging) dragTranslation else 0f)
    val offset by animateFloatAsState(
        targetValue = liveOffset,
        animationSpec = if (dragging) tween(0) else tween(200),
        label = "swipeOffset",
    )
    val deleteLabel = stringResource(R.string.server_list_delete_subscription)
    val refreshLabel = stringResource(R.string.server_list_refresh_subscription)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(isOpen) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; dragTranslation = 0f },
                    onDragEnd = {
                        val total = SubscriptionSwipe.offset(isOpen, dragTranslation)
                        dragging = false
                        dragTranslation = 0f
                        when (SubscriptionSwipe.settle(total)) {
                            SubscriptionSwipe.Outcome.DELETE -> onDelete()
                            SubscriptionSwipe.Outcome.OPEN -> onOpenChange(true)
                            SubscriptionSwipe.Outcome.CLOSE -> onOpenChange(false)
                        }
                    },
                    onDragCancel = { dragging = false; dragTranslation = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragTranslation += with(density) { amount.toDp().value }
                    },
                )
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        // The red button under the row.
        if (offset < -4f) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(SubscriptionSwipe.REVEAL_WIDTH.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFF3B30))
                    .clickable(onClick = onDelete),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = offset.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The name takes what it needs; the backup-route mark follows it directly.
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f, fill = false),
                )
                // 12 dp mark centred in a 32 dp target: (32 − 12) / 2 − 4 = a 6 dp gap.
                if (bypassState != null) {
                    SubscriptionBypassIcon(
                        state = bypassState,
                        mode = bypassMode,
                        modifier = Modifier.offset(x = (-4).dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // While the row is slid away its own trash hides — two trashes in a row otherwise.
            val showsTrash = offset > -4f
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(x = 10.dp)
                    .size(44.dp)
                    .alpha(if (showsTrash) 1f else 0f)
                    .clickable(enabled = showsTrash && refreshEnabled, role = Role.Button, onClick = onRefresh)
                    .semantics { contentDescription = refreshLabel },
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = if (refreshEnabled) 0.45f else 0.25f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(x = 10.dp)
                    .size(44.dp)
                    .alpha(if (showsTrash) 1f else 0f)
                    .clickable(enabled = showsTrash, role = Role.Button, onClick = onDelete)
                    .semantics { contentDescription = deleteLabel },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    section: ServerListSectionModel,
    selectedId: UUID?,
    latencies: Map<UUID, LatencyResult>,
    onSelect: (UUID) -> Unit,
    bypassServerIds: Set<UUID>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardBackground),
    ) {
        section.items.forEachIndexed { index, item ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .padding(start = SeparatorInset)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f)),
                )
            }
            ServerRow(item, item.id == selectedId, latencies[item.id], onSelect, item.id in bypassServerIds)
        }
    }
}

@Composable
private fun ServerRow(
    item: ServerListItem,
    isSelected: Boolean,
    latency: LatencyResult?,
    onSelect: (UUID) -> Unit,
    hasBypass: Boolean = false,
) {
    // The flag is an emoji prefix of the name ("🇺🇸 Los Angeles"); the model has no field for it.
    val (flag, name) = remember(item.name) { ServerListModel.splitFlag(item.name) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .background(if (isSelected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(role = Role.Button) { onSelect(item.id) }
            .semantics { selected = isSelected }
            .padding(horizontal = RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ItemSpacing),
    ) {
        Text(
            text = flag,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(FlagWidth),
        )
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (hasBypass) {
                BypassMark(
                    glyph = BypassGlyph.AVAILABLE,
                    size = 10.dp,
                    modifier = Modifier.padding(start = 6.dp).alpha(0.55f),
                )
            }
        }
        PingCell(latency)
        Box(modifier = Modifier.width(TickWidth), contentAlignment = Alignment.Center) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * At least [PingWidth] in every state, so names do not jump when the numbers arrive; wider
 * only when a label ("таймаут") needs it — it never wraps onto a second line.
 */
@Composable
private fun PingCell(result: LatencyResult?) {
    Box(modifier = Modifier.widthIn(min = PingWidth), contentAlignment = Alignment.CenterEnd) {
        when (result) {
            LatencyResult.Testing -> MeasuringPlaceholder()
            is LatencyResult.Success -> Text(
                text = stringResource(R.string.latency_ms, result.ms),
                style = TextStyle(fontSize = 13.sp, fontFeatureSettings = "tnum"),
                color = Color.White.copy(alpha = 0.52f),
                maxLines = 1,
                softWrap = false,
            )
            LatencyResult.Failed -> Text(
                text = stringResource(R.string.timeout),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.40f),
                maxLines = 1,
                softWrap = false,
            )
            LatencyResult.Insecure -> Text(
                text = stringResource(R.string.insecure),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.40f),
                maxLines = 1,
                softWrap = false,
            )
            null -> Unit
        }
    }
}

@Composable
private fun MeasuringPlaceholder() {
    val transition = rememberInfiniteTransition(label = "measuring")
    val alpha by transition.animateFloat(
        initialValue = 0.40f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(575, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "measuringAlpha",
    )
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 8.dp)
            .alpha(alpha)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.18f)),
    )
}

/** No subscription, so no list: an explanation and a way to add one. */
@Composable
fun ServerListEmptyState(onAddSubscription: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardBackground)
            .padding(vertical = 28.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.WifiOff,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier
                .padding(bottom = 4.dp)
                .size(34.dp),
        )
        Text(
            text = stringResource(R.string.server_list_empty_title),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        Text(
            text = stringResource(R.string.server_list_empty_body),
            fontSize = 13.sp,
            lineHeight = 17.sp,
            color = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 240.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.20f))
                .clickable(role = Role.Button, onClick = onAddSubscription)
                .padding(horizontal = 22.dp),
        ) {
            Text(
                text = stringResource(R.string.server_list_add_subscription),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

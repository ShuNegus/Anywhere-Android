package com.argsment.anywhere.ui.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.argsment.anywhere.R
import com.argsment.anywhere.data.model.Subscription
import com.argsment.anywhere.turn.TurnCaptchaMonitor
import com.argsment.anywhere.ui.components.PowerButton
import com.argsment.anywhere.ui.components.TrafficStatsRow
import com.argsment.anywhere.ui.proxy.AddProxyScreen
import com.argsment.anywhere.ui.proxy.ProxyEditorScreen
import com.argsment.anywhere.ui.theme.HomeConnectedBackgroundEnd
import com.argsment.anywhere.ui.theme.HomeConnectedBackgroundStart
import com.argsment.anywhere.ui.theme.HomeDisconnectedBackgroundEnd
import com.argsment.anywhere.ui.theme.HomeDisconnectedBackgroundStart
import com.argsment.anywhere.viewmodel.VpnStatus
import com.argsment.anywhere.viewmodel.VpnViewModel
import java.util.UUID

/** Mock-up `.phone`: 16 on the sides. */
private val HorizontalPadding = 16.dp
private val MaxControlPaneWidth = 500.dp
/** Clears the last row from under the scrim (112 tall) when scrolled to the end. */
private val ScrollBottomInset = 96.dp
private val ScrimHeight = 112.dp

/**
 * The home screen (iOS `HomeView`, spec §11): one vertical scroll with the add button,
 * the power button and status, the connection-stage graph and the server list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: VpnViewModel, contentPadding: PaddingValues = PaddingValues()) {
    val vpnStatus by viewModel.vpnStatus.collectAsState()
    val isPreflighting by viewModel.isPreflighting.collectAsState()
    val stage by viewModel.connectionStage.collectAsState()
    val turnPool by viewModel.turnPool.collectAsState()
    val turnForcedInSession by viewModel.turnForcedInSession.collectAsState()
    val graphExpanded by viewModel.connectionGraphExpanded.collectAsState()
    val captchaWaiting by TurnCaptchaMonitor.captchaWaiting.collectAsState(initial = false)
    val bytesIn by viewModel.bytesIn.collectAsState()
    val bytesOut by viewModel.bytesOut.collectAsState()
    val selectedConfigId by viewModel.selectedConfigId.collectAsState()
    val selectedChainId by viewModel.selectedChainId.collectAsState()
    val configurations by viewModel.configRepository.configurations.collectAsState()
    val chains by viewModel.chainRepository.chains.collectAsState()
    val subscriptions by viewModel.subscriptionRepository.subscriptions.collectAsState()
    val latencyResults by viewModel.latencyResults.collectAsState()
    val chainLatencyResults by viewModel.chainLatencyResults.collectAsState()
    val startError by viewModel.startError.collectAsState()
    val proModeEnabled by viewModel.proModeEnabled.collectAsState()
    val pendingDeepLink by viewModel.pendingDeepLinkUrl.collectAsState()
    val bypassStates by viewModel.subscriptionBypassStates.collectAsState()
    val turnMetadata by viewModel.turnMetadataStore.bySubscription.collectAsState()

    val isConnected = vpnStatus == VpnStatus.CONNECTED
    val isTransitioning = vpnStatus == VpnStatus.CONNECTING ||
        vpnStatus == VpnStatus.DISCONNECTING ||
        vpnStatus == VpnStatus.REASSERTING ||
        isPreflighting
    val hasConfigurations = configurations.isNotEmpty()

    var showingAddSheet by remember { mutableStateOf(false) }
    var addSheetPrefill by remember { mutableStateOf<String?>(null) }
    var showingManualAddSheet by remember { mutableStateOf(false) }
    var showingStatsSheet by remember { mutableStateOf(false) }
    /** Deleting a subscription is destructive, so it goes through a confirmation. */
    var subscriptionPendingDeletion by remember { mutableStateOf<Subscription?>(null) }
    /** Subscription being refreshed from its header button; one at a time. */
    var refreshingSubscription by remember { mutableStateOf<UUID?>(null) }
    var subscriptionRefreshError by remember { mutableStateOf<String?>(null) }
    val refreshScope = rememberCoroutineScope()
    val unknownError = stringResource(R.string.unknown_error)

    // Without Pro Mode there is no Proxies tab: an import deep link opens the add sheet
    // over Home (spec §10). With Pro Mode the Proxies tab consumes it, as upstream.
    LaunchedEffect(pendingDeepLink, proModeEnabled) {
        val url = pendingDeepLink ?: return@LaunchedEffect
        if (proModeEnabled) return@LaunchedEffect
        addSheetPrefill = url
        showingAddSheet = true
        viewModel.consumePendingDeepLink()
    }

    // The stats sheet makes no sense once the tunnel is down.
    LaunchedEffect(isConnected) { if (!isConnected) showingStatsSheet = false }

    // A haptic on connect/disconnect — but not for the status restored at launch.
    val haptics = LocalHapticFeedback.current
    var lastConnected by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(isConnected) {
        if (lastConnected != null && lastConnected != isConnected) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        lastConnected = isConnected
    }

    val chainsHeader = stringResource(R.string.chains)
    val sections = remember(configurations, chains, subscriptions, chainsHeader) {
        ServerListModel.sections(configurations, chains, subscriptions, chainsHeader)
    }
    val selectedRowId = ServerListModel.selectedRowId(selectedChainId, selectedConfigId)
    val latencies = remember(latencyResults, chainLatencyResults) {
        ServerListModel.mergedLatencies(latencyResults, chainLatencyResults)
    }
    val isMeasuring = ServerListModel.isMeasuring(latencyResults, chainLatencyResults)
    val bypassServerIds = remember(configurations, turnMetadata) {
        ServerListModel.bypassServerIds(configurations, turnMetadata)
    }

    val backgroundStart by animateColorAsState(
        if (isConnected) HomeConnectedBackgroundStart else HomeDisconnectedBackgroundStart,
        tween(600), label = "backgroundStart",
    )
    val backgroundEnd by animateColorAsState(
        if (isConnected) HomeConnectedBackgroundEnd else HomeDisconnectedBackgroundEnd,
        tween(600), label = "backgroundEnd",
    )

    // The home screen is always dark (iOS `homeColorScheme`); the sheets keep the app theme.
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(backgroundStart, backgroundEnd)))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            top = contentPadding.calculateTopPadding(),
                            bottom = contentPadding.calculateBottomPadding() + ScrollBottomInset,
                        )
                        .padding(horizontal = HorizontalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = MaxControlPaneWidth)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TopBar(onAdd = { addSheetPrefill = null; showingAddSheet = true })

                        // Mock-up rhythm: power 4 below the top bar, status 10 below the
                        // power, stages 10 below the status, servers 18 below the stages.
                        Spacer(modifier = Modifier.height(4.dp))

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.alpha(if (hasConfigurations) 1f else 0.4f),
                        ) {
                            PowerButton(
                                isConnected = isConnected,
                                isTransitioning = isTransitioning,
                                enabled = hasConfigurations && !viewModel.isButtonDisabled,
                                isTurnForced = turnForcedInSession,
                                onClick = { if (hasConfigurations) viewModel.toggleVPN() },
                                onLongPress = { if (hasConfigurations) viewModel.connectForcingTurn() },
                            )
                            StatusLabel(
                                // A failed stage keeps its node red; the label goes back to
                                // the raw tunnel status so it does not claim "Connecting" forever.
                                text = stringResource(
                                    homeStatusText(if (stage.isFailed) rawStatusText(vpnStatus) else stage.statusTextRes)
                                ),
                                isConnected = isConnected,
                                warning = stage.isDegraded,
                                onClick = { showingStatsSheet = true },
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Without a subscription there is nowhere to connect, so no graph either.
                        if (hasConfigurations) {
                            ConnectionGraphSection(
                                stage = stage,
                                pool = turnPool,
                                expanded = graphExpanded,
                                onExpandedChange = viewModel::setConnectionGraphExpanded,
                                captchaWaiting = captchaWaiting,
                                onShowCaptcha = { TurnCaptchaMonitor.requestShow() },
                                serverName = viewModel.selectedServerName?.let { ServerListModel.splitFlag(it).second },
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                        }

                        // Repositories load synchronously at start-up, so there is no
                        // loading state to show before the list (iOS shows a spinner card).
                        ServerListSection(
                            sections = sections,
                            selectedId = selectedRowId,
                            latencies = latencies,
                            isMeasuring = isMeasuring,
                            onSelect = { id -> select(viewModel, id) },
                            onMeasure = {
                                viewModel.testLatencies(viewModel.configRepository.getAll())
                                viewModel.testAllChainLatencies()
                            },
                            onAddSubscription = { addSheetPrefill = null; showingAddSheet = true },
                            onDeleteSubscription = { id ->
                                subscriptionPendingDeletion = subscriptions.firstOrNull { it.id == id }
                            },
                            // Same path as Update on the Proxies tab, which Pro Mode hides.
                            onRefreshSubscription = { id ->
                                val subscription = subscriptions.firstOrNull { it.id == id }
                                if (refreshingSubscription == null && subscription != null) {
                                    refreshingSubscription = id
                                    refreshScope.launch {
                                        try {
                                            viewModel.updateSubscription(subscription)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            subscriptionRefreshError = e.message ?: unknownError
                                        } finally {
                                            refreshingSubscription = null
                                        }
                                    }
                                }
                            },
                            refreshingSubscription = refreshingSubscription,
                            bypassStates = bypassStates,
                            bypassMode = if (viewModel.turnSettings.featureEnabled) viewModel.turnSettings.turnMode else null,
                            bypassServerIds = bypassServerIds,
                        )
                    }
                }

                // Fades the rows out above the tab bar instead of cutting them off. Pinned
                // to the screen's bottom edge and stretched by the bottom inset, so the
                // fade keeps its top and runs down under the tab bar.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(ScrimHeight + contentPadding.calculateBottomPadding())
                        .background(
                            Brush.verticalGradient(
                                0f to backgroundEnd.copy(alpha = 0f),
                                0.46f to backgroundEnd.copy(alpha = 0.88f),
                                1f to backgroundEnd,
                            )
                        )
                )
            }
        }
    }

    if (showingStatsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showingStatsSheet = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            StatsSheetContent(
                bytesIn = bytesIn,
                bytesOut = bytesOut,
                onDone = { showingStatsSheet = false },
            )
        }
    }

    if (showingAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showingAddSheet = false; addSheetPrefill = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            AddProxyScreen(
                viewModel = viewModel,
                initialLink = addSheetPrefill,
                onDismiss = { showingAddSheet = false; addSheetPrefill = null },
                onShowManualAdd = {
                    showingAddSheet = false
                    addSheetPrefill = null
                    showingManualAddSheet = true
                },
                onImport = { config ->
                    viewModel.addConfiguration(config)
                    showingAddSheet = false
                    addSheetPrefill = null
                },
                onSubscriptionImport = { configs, subscription, turn ->
                    viewModel.addSubscription(configs, subscription, turn)
                    showingAddSheet = false
                    addSheetPrefill = null
                }
            )
        }
    }

    if (showingManualAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showingManualAddSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            ProxyEditorScreen(
                configuration = null,
                onSave = { config ->
                    viewModel.addConfiguration(config)
                    showingManualAddSheet = false
                },
                onDismiss = { showingManualAddSheet = false }
            )
        }
    }

    subscriptionPendingDeletion?.let { subscription ->
        AlertDialog(
            onDismissRequest = { subscriptionPendingDeletion = null },
            title = { Text(stringResource(R.string.server_list_delete_confirm_title, subscription.name)) },
            text = { Text(stringResource(R.string.server_list_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSubscription(subscription)
                    subscriptionPendingDeletion = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { subscriptionPendingDeletion = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    subscriptionRefreshError?.let { message ->
        AlertDialog(
            onDismissRequest = { subscriptionRefreshError = null },
            title = { Text(stringResource(R.string.update_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { subscriptionRefreshError = null }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    if (startError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearStartError() },
            title = { Text(stringResource(R.string.vpn_error)) },
            text = { Text(startError ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearStartError() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

/** A row id is a chain's id or a configuration's id. */
private fun select(viewModel: VpnViewModel, id: UUID) {
    val chain = viewModel.chainRepository.get(id)
    if (chain != null) {
        viewModel.selectChain(chain)
        return
    }
    viewModel.configRepository.get(id)?.let { viewModel.setSelectedConfiguration(it) }
}

/** "Disconnected" reads "Not Connected" on Home, as in the mock-up; the rest as is. */
private fun homeStatusText(res: Int): Int =
    if (res == R.string.disconnected) R.string.home_status_disconnected else res

private fun rawStatusText(status: VpnStatus): Int = when (status) {
    VpnStatus.DISCONNECTED -> R.string.disconnected
    VpnStatus.CONNECTING -> R.string.connecting
    VpnStatus.CONNECTED -> R.string.connected
    VpnStatus.DISCONNECTING -> R.string.disconnecting
    VpnStatus.REASSERTING -> R.string.reconnecting
}

/**
 * Mock-up `.topbar`: a 32 dp row with the "+" on the right (adding a subscription lives
 * up here because the list it would otherwise hang off does not exist yet). The tap area
 * stays 44 dp and overflows the row.
 */
@Composable
private fun TopBar(onAdd: () -> Unit) {
    val addLabel = stringResource(R.string.server_list_add_subscription)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset(x = 6.dp) // the circle's visual edge lines up with the cards'
                .requiredSize(44.dp)
                .clickable(role = Role.Button, onClick = onAdd)
                .semantics { contentDescription = addLabel },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/**
 * Mock-up `.status`: 17 sp semibold white, a regular "›" at 55 % while connected (it opens
 * the statistics sheet); red (#FF8A80, "›" at 80 %) when connected without the backup
 * route it needed.
 */
@Composable
private fun StatusLabel(text: String, isConnected: Boolean, warning: Boolean = false, onClick: () -> Unit) {
    val color = if (warning) BypassWarningTextColor else Color.White
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                enabled = isConnected,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
    ) {
        Text(text = text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = color)
        if (isConnected) {
            Text(
                text = "›",
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
                color = color.copy(alpha = if (warning) 0.8f else 0.55f),
            )
        }
    }
}

/** iOS `statsSheet`: a "Statistics" title, "Done", and the upstream traffic stats. */
@Composable
private fun StatsSheetContent(bytesIn: Long, bytesOut: Long, onDone: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_sheet_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.align(Alignment.Center),
            )
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterEnd)) {
                Text(stringResource(R.string.stats_sheet_done), fontWeight = FontWeight.SemiBold)
            }
        }
        Box(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp),
        ) {
            TrafficStatsRow(
                bytesIn = bytesIn,
                bytesOut = bytesOut,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

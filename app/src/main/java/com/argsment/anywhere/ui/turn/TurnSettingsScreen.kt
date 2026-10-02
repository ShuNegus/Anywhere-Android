package com.argsment.anywhere.ui.turn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.argsment.anywhere.R
import com.argsment.anywhere.turn.TurnCaptchaMonitor
import com.argsment.anywhere.turn.TurnLimits
import com.argsment.anywhere.turn.TurnMode
import com.argsment.anywhere.turn.TurnServerInfo
import com.argsment.anywhere.turn.TurnSettings
import com.argsment.anywhere.ui.settings.SectionHeader
import com.argsment.anywhere.vpn.turn.TurnAutoDecision
import com.argsment.anywhere.vpn.turn.TurnAutoState
import com.argsment.anywhere.vpn.turn.TurnHostStatistics
import com.argsment.anywhere.vpn.turn.TurnStatusMonitor
import com.argsment.anywhere.viewmodel.VpnViewModel

/** iOS `.indigo`, the TURN item's tint (spec §10). */
internal val TurnTint = Color(0xFF5856D6)

/**
 * The TURN settings screen, a port of iOS `TurnSettingsView` (spec §12): mode, VK Calls
 * link, relay knobs, live counters, captcha, and the relays the subscriptions advertise.
 *
 * Live counters come from [TurnStatusMonitor.snapshot], which the VPN service refreshes
 * every second while the tunnel is up and empties when it stops (iOS polls the extension
 * every 2 s and shows nothing unless connected — same outcome).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurnSettingsScreen(viewModel: VpnViewModel, onBack: () -> Unit) {
    val settings: TurnSettings = viewModel.turnSettings
    val requestPermission = rememberTurnNotificationPermissionRequest()

    var mode by remember { mutableStateOf(settings.turnMode) }
    var vkLink by remember { mutableStateOf(settings.vkLink) }
    var peers by remember { mutableIntStateOf(settings.peers) }
    var captchaManual by remember { mutableStateOf(settings.captchaManual) }

    val metadata by viewModel.turnMetadataStore.bySubscription.collectAsState()
    val servers = remember(metadata) { viewModel.turnMetadataStore.allServers() }
    val subscriptionLink = remember(metadata) { viewModel.turnMetadataStore.subscriptionVKLink() }
    val stats by TurnStatusMonitor.snapshot.collectAsState()
    val decision by TurnAutoState.decisionFlow.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.turn_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 1. Mode
            ModeSection(
                mode = mode,
                onModeChange = { new ->
                    mode = new
                    settings.turnMode = new
                    // A captcha can strand the relay while the app is in the background,
                    // so ask for notifications at the moment TURN is switched on.
                    if (new != TurnMode.OFF) requestPermission()
                },
                // The route is only known while the tunnel is up and auto mode is in
                // effect: the snapshot carries a decision exactly then.
                autoStatus = if (mode == TurnMode.AUTO && stats.autoDecision != null) {
                    autoStatusText(decision)
                } else {
                    null
                },
            )

            Spacer(Modifier.height(16.dp))

            // 2. VK Calls link
            SectionHeader(stringResource(R.string.turn_settings_vk_link_header))
            OutlinedTextField(
                value = vkLink,
                onValueChange = {
                    vkLink = it
                    settings.vkLink = it
                },
                placeholder = { Text(stringResource(R.string.turn_settings_vk_link_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Footer(
                when {
                    vkLink.isNotBlank() -> stringResource(R.string.turn_settings_vk_link_override)
                    !subscriptionLink.isNullOrEmpty() ->
                        stringResource(R.string.turn_settings_vk_link_from_subscription, subscriptionLink)
                    else -> stringResource(R.string.turn_settings_vk_link_missing)
                }
            )

            if (mode != TurnMode.OFF) {
                Spacer(Modifier.height(16.dp))

                // 3. Relay
                SectionHeader(stringResource(R.string.turn_settings_relay))
                PeersStepper(
                    value = peers,
                    onChange = { new ->
                        peers = TurnLimits.clampPeers(new)
                        settings.peers = peers
                    },
                )
                LabeledRow(stringResource(R.string.turn_settings_captcha)) {}
                Segmented(
                    options = listOf(
                        false to stringResource(R.string.turn_settings_captcha_automatic),
                        true to stringResource(R.string.turn_settings_captcha_manual),
                    ),
                    selected = captchaManual,
                    onSelect = {
                        captchaManual = it
                        settings.captchaManual = it
                    },
                )
                Footer(
                    stringResource(
                        R.string.turn_settings_relay_footer,
                        TurnLimits.RELAY_PEERS_PER_ACCOUNT,
                    )
                )

                Spacer(Modifier.height(16.dp))

                // 4. Live
                SectionHeader(stringResource(R.string.turn_settings_live))
                LabeledRow(stringResource(R.string.turn_settings_sessions)) {
                    ValueText("${stats.hosts.sumOf { it.sessions }}")
                }
                LabeledRow(stringResource(R.string.turn_settings_open_streams)) {
                    ValueText("${stats.hosts.sumOf { it.streams }}")
                }
                stats.hosts.forEach { host ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        CaptionText(host.host)
                        CaptionText("${host.sessions} / ${host.streams}${credentialSuffix(host)}")
                    }
                }
                Footer(
                    stringResource(
                        if (stats.hosts.isEmpty()) R.string.turn_settings_live_footer_empty
                        else R.string.turn_settings_live_footer
                    )
                )

                Spacer(Modifier.height(16.dp))

                // 5. Captcha
                TextButton(
                    onClick = { TurnCaptchaMonitor.requestShow() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.turn_captcha_solve))
                }
                Footer(stringResource(R.string.turn_settings_captcha_footer))
            }

            Spacer(Modifier.height(16.dp))

            // 6. Servers
            SectionHeader(stringResource(R.string.turn_settings_servers))
            if (servers.isEmpty()) {
                EmptyServers()
            } else {
                servers.forEach { ServerRow(it) }
                Footer(stringResource(R.string.turn_settings_servers_footer))
            }
        }
    }
}

@Composable
private fun ModeSection(
    mode: TurnMode,
    onModeChange: (TurnMode) -> Unit,
    autoStatus: String?,
) {
    LabeledRow(stringResource(R.string.turn_settings_title)) {}
    Segmented(
        options = listOf(
            TurnMode.AUTO to stringResource(R.string.turn_mode_auto),
            TurnMode.ON to stringResource(R.string.turn_mode_on),
            TurnMode.OFF to stringResource(R.string.turn_mode_off),
        ),
        selected = mode,
        onSelect = onModeChange,
    )
    if (autoStatus != null) {
        LabeledRow(stringResource(R.string.turn_settings_route)) { ValueText(autoStatus) }
    }
    Footer(stringResource(R.string.turn_settings_mode_footer))
}

@Composable
private fun autoStatusText(decision: TurnAutoDecision): String = when (decision) {
    TurnAutoDecision.TURN -> stringResource(R.string.turn_settings_auto_status_turn)
    TurnAutoDecision.DIRECT -> stringResource(R.string.turn_settings_auto_status_direct)
    TurnAutoDecision.UNDECIDED, TurnAutoDecision.OFFLINE ->
        stringResource(R.string.turn_settings_auto_status_probing)
}

/**
 * VK accounts (credential sets) carrying this relay's sessions, plus "+" while one more
 * is being signed in — each further account can raise its own captcha.
 */
internal fun credentialSuffix(host: TurnHostStatistics): String {
    val core = host.core ?: return ""
    val inUse = core.sets.count { it.state != "retired" }
    var suffix = if (inUse > 0) " · $inUse" else ""
    if (core.fetch.state != com.argsment.anywhere.vpn.turn.TurnFetchState.IDLE) suffix += "+"
    return suffix
}

@Composable
private fun PeersStepper(value: Int, onChange: (Int) -> Unit) {
    LabeledRow(stringResource(R.string.turn_settings_peers)) {
        IconButton(onClick = { onChange(value - 1) }, enabled = value > TurnLimits.MIN_PEERS) {
            Icon(Icons.Filled.Remove, contentDescription = null)
        }
        ValueText("$value")
        IconButton(onClick = { onChange(value + 1) }, enabled = value < TurnLimits.MAX_PEERS) {
            Icon(Icons.Filled.Add, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun ServerRow(server: TurnServerInfo) {
    val supported = stringResource(
        if (server.isUsable) R.string.turn_settings_supported else R.string.turn_settings_unsupported
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(server.host, style = MaterialTheme.typography.bodyLarge)
            CaptionText(server.peerAddr)
        }
        Icon(
            imageVector = if (server.isUsable) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            tint = if (server.isUsable) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = supported },
        )
    }
}

@Composable
private fun EmptyServers() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Hub,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            stringResource(R.string.turn_settings_no_servers_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.turn_settings_no_servers_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabeledRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun ValueText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CaptionText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Footer(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

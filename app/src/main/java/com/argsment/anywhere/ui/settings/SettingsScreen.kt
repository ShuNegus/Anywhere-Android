package com.argsment.anywhere.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.argsment.anywhere.R
import com.argsment.anywhere.data.rules.CountryBypassCatalog
import com.argsment.anywhere.ui.turn.TurnSettingsScreen
import com.argsment.anywhere.ui.turn.TurnTint
import com.argsment.anywhere.viewmodel.VpnViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: VpnViewModel) {
    val context = LocalContext.current
    var showRoutingRules by remember { mutableStateOf(false) }
    var showAcknowledgements by remember { mutableStateOf(false) }
    var showTrustedCertificates by remember { mutableStateOf(false) }
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var showInsecureAlert by remember { mutableStateOf(false) }
    var showTurnSettings by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    val proModeEnabled by viewModel.proModeEnabled.collectAsState()
    val visibility = ProModeVisibility.of(proModeEnabled, viewModel.turnSettings.featureEnabled)

    var globalMode by remember { mutableStateOf(viewModel.proxyMode == "global") }
    var bypassCountryCode by remember { mutableStateOf(viewModel.bypassCountryCode) }
    var allowInsecure by remember { mutableStateOf(viewModel.allowInsecure) }

    val adBlockRuleSet = remember { viewModel.ruleSetRepository.ruleSets.value.find { it.name == "ADBlock" } }
    var adBlockEnabled by remember {
        mutableStateOf(adBlockRuleSet?.assignedConfigurationId == "REJECT")
    }

    // Intercept the device back button when a sub-screen is showing. Without
    // this, the NavHost handles back press by popping the SettingsRoute,
    // sending the user to Home instead of back to the Settings list.
    val activeSubScreen = showRoutingRules || showAcknowledgements ||
            showTrustedCertificates || showAdvancedSettings || showTurnSettings || showLogs
    BackHandler(enabled = activeSubScreen) {
        showTurnSettings = false
        showLogs = false
        showRoutingRules = false
        showAcknowledgements = false
        showTrustedCertificates = false
        showAdvancedSettings = false
    }

    val currentRoute = when {
        showRoutingRules -> "routing"
        showAcknowledgements -> "acks"
        showTrustedCertificates -> "certs"
        showAdvancedSettings -> "advanced"
        showTurnSettings -> "turn"
        showLogs -> "logs"
        else -> "root"
    }

    SubScreenHost(state = currentRoute, rootKey = "root") { route ->
        when (route) {
            "routing" -> RuleSetListScreen(
                viewModel = viewModel,
                onBack = { showRoutingRules = false }
            )
            "acks" -> AcknowledgementsScreen(onBack = { showAcknowledgements = false })
            "certs" -> TrustedCertificatesScreen(
                viewModel = viewModel,
                onBack = { showTrustedCertificates = false }
            )
            "advanced" -> AdvancedSettingsScreen(
                viewModel = viewModel,
                onBack = { showAdvancedSettings = false }
            )
            "turn" -> TurnSettingsScreen(
                viewModel = viewModel,
                onBack = { showTurnSettings = false }
            )
            "logs" -> LogListScreen(onBack = { showLogs = false })
            else -> SettingsRoot(
                visibility = visibility,
                proModeEnabled = proModeEnabled,
                onProModeChange = viewModel::setProModeEnabled,
                onOpenLogs = { showLogs = true },
                globalMode = globalMode,
                onGlobalModeChange = { globalMode = it; viewModel.proxyMode = if (it) "global" else "rule" },
                bypassCountryCode = bypassCountryCode,
                onBypassCountryChange = {
                    bypassCountryCode = it
                    viewModel.bypassCountryCode = it
                    viewModel.syncRoutingConfigurationToNE()
                },
                adBlockVisible = adBlockRuleSet != null,
                adBlockEnabled = adBlockEnabled,
                onAdBlockChange = { enabled ->
                    adBlockEnabled = enabled
                    if (adBlockRuleSet != null) {
                        viewModel.ruleSetRepository.updateAssignment(
                            adBlockRuleSet,
                            if (enabled) "REJECT" else null
                        )
                        viewModel.syncRoutingConfigurationToNE()
                    }
                },
                allowInsecure = allowInsecure,
                onAllowInsecureChange = { newValue ->
                    if (newValue) {
                        showInsecureAlert = true
                    } else {
                        allowInsecure = false
                        viewModel.allowInsecure = false
                    }
                },
                onOpenTelegram = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/anywhere_official_group"))
                    )
                },
                onOpenRoutingRules = { showRoutingRules = true },
                onOpenTrustedCertificates = { showTrustedCertificates = true },
                onOpenTurn = { showTurnSettings = true },
                onOpenAcknowledgements = { showAcknowledgements = true },
                onOpenAdvancedSettings = { showAdvancedSettings = true }
            )
        }
    }

    if (showInsecureAlert) {
        AlertDialog(
            onDismissRequest = { showInsecureAlert = false },
            title = { Text(stringResource(R.string.allow_insecure)) },
            text = { Text(stringResource(R.string.allow_insecure_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    showInsecureAlert = false
                    allowInsecure = true
                    viewModel.allowInsecure = true
                }) {
                    Text(stringResource(R.string.allow_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { showInsecureAlert = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoot(
    visibility: ProModeVisibility,
    proModeEnabled: Boolean,
    onProModeChange: (Boolean) -> Unit,
    onOpenLogs: () -> Unit,
    globalMode: Boolean,
    onGlobalModeChange: (Boolean) -> Unit,
    bypassCountryCode: String,
    onBypassCountryChange: (String) -> Unit,
    adBlockVisible: Boolean,
    adBlockEnabled: Boolean,
    onAdBlockChange: (Boolean) -> Unit,
    allowInsecure: Boolean,
    onAllowInsecureChange: (Boolean) -> Unit,
    onOpenTelegram: () -> Unit,
    onOpenRoutingRules: () -> Unit,
    onOpenTrustedCertificates: () -> Unit,
    onOpenTurn: () -> Unit,
    onOpenAcknowledgements: () -> Unit,
    onOpenAdvancedSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings)) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // App: the Pro Mode toggle is always visible (iOS also has iCloud Sync and
            // Personalization here; neither exists on Android).
            SectionHeader(stringResource(R.string.settings_section_app))
            SettingsSwitch(
                icon = Icons.Filled.Build,
                iconTint = Color(0xFF8E8E93),
                label = stringResource(R.string.pro_mode),
                checked = proModeEnabled,
                onCheckedChange = onProModeChange
            )

            Spacer(modifier = Modifier.height(16.dp))

            SectionHeader(stringResource(R.string.routing))
            SettingsSwitch(
                icon = Icons.AutoMirrored.Filled.CallMerge,
                iconTint = Color(0xFFFF9800),
                label = stringResource(R.string.global_mode),
                checked = globalMode,
                onCheckedChange = onGlobalModeChange
            )
            if (!globalMode) {
                CountryBypassPicker(
                    icon = Icons.Filled.Public,
                    iconTint = Color(0xFF007AFF),
                    selectedCode = bypassCountryCode,
                    onSelect = onBypassCountryChange
                )
                if (adBlockVisible) {
                    SettingsSwitch(
                        icon = Icons.Filled.Shield,
                        iconTint = Color(0xFFE91E63),
                        label = stringResource(R.string.ad_blocking_title),
                        checked = adBlockEnabled,
                        onCheckedChange = onAdBlockChange
                    )
                }
                SettingsNavRow(
                    icon = Icons.Filled.AltRoute,
                    iconTint = Color(0xFF9C27B0),
                    label = stringResource(R.string.routing_rules),
                    onClick = onOpenRoutingRules
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (visibility.securitySection) {
                SectionHeader(stringResource(R.string.security))
                SettingsSwitch(
                    icon = Icons.Filled.Warning,
                    iconTint = Color(0xFFF44336),
                    label = stringResource(R.string.allow_insecure),
                    checked = allowInsecure,
                    onCheckedChange = onAllowInsecureChange
                )
                SettingsNavRow(
                    icon = Icons.Filled.VerifiedUser,
                    iconTint = Color(0xFF4CAF50),
                    label = stringResource(R.string.trusted_certificates),
                    onClick = onOpenTrustedCertificates
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Utilities (iOS, between Security and About). Purify/Reflection/MITM do not
            // exist on Android, so TURN is the only item; it is not gated by Pro Mode.
            if (visibility.utilitiesSection && visibility.turnItem) {
                SectionHeader(stringResource(R.string.turn_settings_utilities))
                SettingsNavRow(
                    icon = Icons.Filled.Hub,
                    iconTint = TurnTint,
                    label = stringResource(R.string.turn_settings_title),
                    onClick = onOpenTurn
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Diagnostics stays visible without Pro Mode (iOS). Upstream Android kept Logs
            // inside Advanced Settings, which Pro Mode hides, so they live here now.
            SectionHeader(stringResource(R.string.diagnostics))
            SettingsNavRow(
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                iconTint = Color(0xFF607D8B),
                label = stringResource(R.string.logs),
                onClick = onOpenLogs
            )

            Spacer(modifier = Modifier.height(16.dp))

            SectionHeader(stringResource(R.string.about))
            SettingsNavRow(
                painter = painterResource(R.drawable.ic_telegram_symbol),
                iconTint = Color(0xFF039BE5),
                label = stringResource(R.string.join_telegram_group),
                onClick = onOpenTelegram
            )
            SettingsNavRow(
                icon = Icons.Filled.Info,
                iconTint = Color(0xFF9E9E9E),
                label = stringResource(R.string.acknowledgements),
                onClick = onOpenAcknowledgements
            )

            if (visibility.advancedSettings) {
                Spacer(modifier = Modifier.height(16.dp))

                SettingsNavRow(
                    icon = Icons.Filled.Tune,
                    iconTint = Color(0xFF546E7A),
                    label = stringResource(R.string.advanced_settings),
                    onClick = onOpenAdvancedSettings
                )
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SettingsIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    SettingsIconContainer(tint = tint, modifier = modifier) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun SettingsIcon(
    painter: Painter,
    tint: Color,
    modifier: Modifier = Modifier
) {
    SettingsIconContainer(tint = tint, modifier = modifier) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun SettingsIconContainer(
    tint: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
internal fun SettingsSwitch(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsIcon(icon = icon, tint = iconTint)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun SettingsNavRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsIcon(icon = icon, tint = iconTint)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun SettingsNavRow(
    painter: Painter,
    iconTint: Color,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsIcon(painter = painter, tint = iconTint)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun flagForCountryCode(code: String): String {
    val firstChar = Character.toChars(0x1F1E6 - 'A'.code + code[0].uppercaseChar().code)
    val secondChar = Character.toChars(0x1F1E6 - 'A'.code + code[1].uppercaseChar().code)
    return String(firstChar) + String(secondChar)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryBypassPicker(
    icon: ImageVector,
    iconTint: Color,
    selectedCode: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val countryCodes = remember { CountryBypassCatalog.get(context).supportedCountryCodes }

    val displayText = if (selectedCode.isEmpty()) {
        stringResource(R.string.disable)
    } else {
        val locale = Locale("", selectedCode)
        "${flagForCountryCode(selectedCode)} ${locale.displayCountry}"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsIcon(icon = icon, tint = iconTint)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = displayText,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.country_bypass)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.disable)) },
                    onClick = {
                        onSelect("")
                        expanded = false
                    }
                )
                countryCodes.forEach { code ->
                    val locale = Locale("", code)
                    DropdownMenuItem(
                        text = { Text("${flagForCountryCode(code)} ${locale.displayCountry}") },
                        onClick = {
                            onSelect(code)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

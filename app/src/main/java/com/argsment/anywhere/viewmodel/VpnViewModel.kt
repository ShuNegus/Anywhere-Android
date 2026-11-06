package com.argsment.anywhere.viewmodel

import com.argsment.anywhere.data.sync.SubscriptionUpdater
import com.argsment.anywhere.data.repository.AppRepositories
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.VpnService
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.argsment.anywhere.data.model.ProxyChain
import com.argsment.anywhere.data.model.Subscription
import com.argsment.anywhere.data.model.ProxyConfiguration
import com.argsment.anywhere.data.network.LatencyResult
import com.argsment.anywhere.data.network.LatencyTester
import com.argsment.anywhere.data.network.SubscriptionFetcher
import com.argsment.anywhere.data.repository.CertificateRepository
import com.argsment.anywhere.data.repository.ChainRepository
import com.argsment.anywhere.data.repository.RuleSetRepository
import com.argsment.anywhere.turn.TurnCaptchaMonitor
import com.argsment.anywhere.turn.TurnEnvironment
import com.argsment.anywhere.turn.TurnMetadata
import com.argsment.anywhere.turn.TurnMetadataStore
import com.argsment.anywhere.turn.TurnMode
import com.argsment.anywhere.turn.TurnSettings
import com.argsment.anywhere.R
import com.argsment.anywhere.vpn.turn.ConnectivityProbe
import com.argsment.anywhere.vpn.turn.ConnectivityVerdict
import com.argsment.anywhere.vpn.turn.ProbeProfile
import com.argsment.anywhere.vpn.turn.TurnAutoDecision
import com.argsment.anywhere.vpn.turn.TurnPhase
import com.argsment.anywhere.vpn.turn.TurnPoolSummary
import com.argsment.anywhere.vpn.turn.TurnRouting
import com.argsment.anywhere.vpn.turn.TurnStatusMonitor
import com.argsment.anywhere.vpn.protocol.tls.CertificatePolicy
import com.argsment.anywhere.vpn.AnywhereVpnService
import com.argsment.anywhere.vpn.util.AnywhereLogger
import com.argsment.anywhere.vpn.util.DnsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID

enum class VpnStatus {
    DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, REASSERTING
}

private val logger = AnywhereLogger("VPNViewModel")

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    // Process-wide: the auto-refresh worker writes to the same instances.
    val configRepository = AppRepositories.config(application)
    val subscriptionRepository = AppRepositories.subscriptions(application)
    val ruleSetRepository = RuleSetRepository(application)
    val certificateRepository = CertificateRepository(application)
    val chainRepository = ChainRepository(application)

    private val prefs: SharedPreferences =
        application.getSharedPreferences("anywhere_settings", Context.MODE_PRIVATE)

    init {
        TurnEnvironment.install(application)
    }

    /** TURN preferences (spec §3); the service reads the same instance. */
    val turnSettings: TurnSettings = TurnEnvironment.settings!!
    val turnMetadataStore: TurnMetadataStore = TurnEnvironment.metadataStore!!
    private val json = Json { ignoreUnknownKeys = true }

    private val _vpnStatus = MutableStateFlow(VpnStatus.DISCONNECTED)
    val vpnStatus: StateFlow<VpnStatus> = _vpnStatus.asStateFlow()
    private var pendingReconnect = false

    // MARK: - TURN session state (spec §6–§8). The home screen renders [connectionStage].

    private val _isPreflighting = MutableStateFlow(false)
    /** A reachability probe runs ahead of the tunnel (auto mode); the button stays busy. */
    val isPreflighting: StateFlow<Boolean> = _isPreflighting.asStateFlow()

    private val _connectionFailure = MutableStateFlow<ConnectionFailure?>(null)
    /**
     * The step the connection died on. Deliberately outlives the DISCONNECTED that
     * follows a failure — the red node has to stay on screen. Cleared by the next connect.
     */
    val connectionFailure: StateFlow<ConnectionFailure?> = _connectionFailure.asStateFlow()

    private val _turnPhase = MutableStateFlow<TurnPhase?>(null)
    /** The least advanced phase among the TURN pools, polled while connected. Never latched. */
    val turnPhase: StateFlow<TurnPhase?> = _turnPhase.asStateFlow()

    private val _turnAutoDecision = MutableStateFlow<TurnAutoDecision?>(null)
    /** The auto-pilot's decision while the effective mode is auto, otherwise `null`. */
    val turnAutoDecision: StateFlow<TurnAutoDecision?> = _turnAutoDecision.asStateFlow()

    private val _turnPool = MutableStateFlow<TurnPoolSummary?>(null)
    /** The TURN pools folded together (peers X/N, captcha for more peers). */
    val turnPool: StateFlow<TurnPoolSummary?> = _turnPool.asStateFlow()

    private val _captchaSeenInSession = MutableStateFlow(false)
    val captchaSeenInSession: StateFlow<Boolean> = _captchaSeenInSession.asStateFlow()

    private val _captchaPending = MutableStateFlow(false)

    private val _turnForcedInSession = MutableStateFlow(false)
    /** This session was forced through TURN (power button held); mirrors [TurnSettings.turnForced]. */
    val turnForcedInSession: StateFlow<Boolean> = _turnForcedInSession.asStateFlow()

    private val _connectionStage = MutableStateFlow<ConnectionStage>(ConnectionStage.Idle)
    /** The home graph's stage — the single output of [ConnectionStage.resolve]. */
    val connectionStage: StateFlow<ConnectionStage> = _connectionStage.asStateFlow()

    private val _proModeEnabled = MutableStateFlow(turnSettings.proModeEnabled)
    /** Pro Mode (spec §10): off = the simplified end-customer UI. */
    val proModeEnabled: StateFlow<Boolean> = _proModeEnabled.asStateFlow()

    fun setProModeEnabled(enabled: Boolean) {
        turnSettings.proModeEnabled = enabled
        _proModeEnabled.value = enabled
    }

    private val _connectionGraphExpanded = MutableStateFlow(turnSettings.connectionGraphExpanded)
    /** The home graph is expanded. Changed only by the user; persisted across restarts. */
    val connectionGraphExpanded: StateFlow<Boolean> = _connectionGraphExpanded.asStateFlow()

    fun setConnectionGraphExpanded(expanded: Boolean) {
        turnSettings.connectionGraphExpanded = expanded
        _connectionGraphExpanded.value = expanded
    }

    private val turnWatchdog = TurnProgressWatchdog()
    private var turnWatchJob: Job? = null
    /** Declared before the init block that registers it; see [syncTurnPhaseWatch]. */
    private val turnSettingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in TurnSettings.Keys.PHASE_WATCH_INPUTS) syncTurnPhaseWatch()
    }
    /** The tunnel reached CONNECTED in this session: a later DISCONNECTED is a teardown, not a failed start. */
    private var sessionReachedConnected = false
    private var userRequestedDisconnect = false
    /** The reconnect in flight was asked to go through TURN (a long press while connected). */
    private var pendingReconnectForcesTurn = false

    private val _bytesIn = MutableStateFlow(0L)
    val bytesIn: StateFlow<Long> = _bytesIn.asStateFlow()

    private val _bytesOut = MutableStateFlow(0L)
    val bytesOut: StateFlow<Long> = _bytesOut.asStateFlow()

    private val _selectedConfigId = MutableStateFlow<UUID?>(null)
    val selectedConfigId: StateFlow<UUID?> = _selectedConfigId.asStateFlow()

    private val _latencyResults = MutableStateFlow<Map<UUID, LatencyResult>>(emptyMap())
    val latencyResults: StateFlow<Map<UUID, LatencyResult>> = _latencyResults.asStateFlow()

    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()

    private val _orphanedRuleSetNames = MutableStateFlow<List<String>>(emptyList())
    val orphanedRuleSetNames: StateFlow<List<String>> = _orphanedRuleSetNames.asStateFlow()

    private val _selectedChainId = MutableStateFlow<UUID?>(null)
    val selectedChainId: StateFlow<UUID?> = _selectedChainId.asStateFlow()

    private val _chainLatencyResults = MutableStateFlow<Map<UUID, LatencyResult>>(emptyMap())
    val chainLatencyResults: StateFlow<Map<UUID, LatencyResult>> = _chainLatencyResults.asStateFlow()

    var onRequestVpnPermission: ((Intent) -> Unit)? = null

    /**
     * Pending deep-link URL (vless://…, ss://…, socks5://…, or raw link from
     * anywhere://add-proxy?link=…). Consumed by ProxyListScreen to open the
     * Add Proxy sheet with the URL pre-filled.
     */
    private val _pendingDeepLinkUrl = MutableStateFlow<String?>(null)
    val pendingDeepLinkUrl: StateFlow<String?> = _pendingDeepLinkUrl.asStateFlow()

    fun onDeepLink(uri: android.net.Uri) {
        val scheme = uri.scheme?.lowercase() ?: return
        when (scheme) {
            "anywhere" -> {
                if (uri.host != "add-proxy") return
                val full = uri.toString()
                val marker = "?link="
                val idx = full.indexOf(marker)
                if (idx < 0) return
                val raw = full.substring(idx + marker.length)
                if (raw.isEmpty()) return
                _pendingDeepLinkUrl.value = runCatching {
                    com.argsment.anywhere.data.model.percentDecode(raw)
                }.getOrDefault(raw)
            }
            "vless", "trojan", "ss", "socks5", "socks" -> {
                _pendingDeepLinkUrl.value = uri.toString()
            }
            else -> {}
        }
    }

    fun consumePendingDeepLink() {
        _pendingDeepLinkUrl.value = null
    }

    private var vpnService: AnywhereVpnService? = null
    private var serviceBound = false
    private var statsJob: Job? = null
    private var pendingConnectAfterPermission = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AnywhereVpnService.LocalBinder ?: return
            vpnService = localBinder.service
            serviceBound = true

            // The binder is delivered after `onStartCommand` has returned,
            // so `isRunning` reflects whether `startVpn` set up the TUN and
            // lwIP stack successfully.
            if (localBinder.service.isRunning) {
                applyStatus(VpnStatus.CONNECTED)
                startStatsPolling()
                selectedConfiguration?.let { syncProxyServerAddresses(it) }
            } else if (_vpnStatus.value == VpnStatus.CONNECTING) {
                // Service bound but tunnel setup failed (e.g. TUN establish
                // failed) — don't leave the UI stuck on the spinner.
                applyStatus(VpnStatus.DISCONNECTED)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            serviceBound = false
            stopStatsPolling()
        }
    }

    val isButtonDisabled: Boolean
        get() = (selectedConfiguration == null) ||
            (_vpnStatus.value != VpnStatus.CONNECTED && _vpnStatus.value != VpnStatus.DISCONNECTED) ||
            _isPreflighting.value

    init {
        _turnForcedInSession.value = turnSettings.turnForced
        val savedChainId = prefs.getString("selectedChainId", null)?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        if (savedChainId != null && chainRepository.get(savedChainId) != null) {
            _selectedChainId.value = savedChainId
        } else {
            prefs.getString("selectedConfigurationId", null)?.let { id ->
                _selectedConfigId.value = runCatching { UUID.fromString(id) }.getOrNull()
            }
        }
        ensureValidSelection()

        // Same preferences file the TURN settings live in (TurnSettings.PREFS_NAME).
        prefs.registerOnSharedPreferenceChangeListener(turnSettingsListener)

        // The captcha monitor fires the moment a captcha server is up, ahead of the next
        // phase poll, so the graph and the session's captcha latch follow it directly
        // (iOS: `HomeView.onChange(captchaMonitor.captchaWaiting)` → `noteCaptchaSeen()`).
        // Collected here rather than in the home screen so it keeps working on other tabs.
        viewModelScope.launch {
            TurnCaptchaMonitor.captchaWaiting.collect { setCaptchaPending(it) }
        }

        // Prime CertificatePolicy with the current allowInsecure + trusted-certificate
        // values so TLS validation has a fresh snapshot before the first connection.
        CertificatePolicy.reload(application)

        // React to trusted-certificate changes: update the singleton immediately and
        // notify the tunnel so live TLS connections tear down and reconnect under the
        // new policy.
        viewModelScope.launch {
            var first = true
            certificateRepository.fingerprints.collect { fingerprints ->
                CertificatePolicy.setTrustedFingerprints(fingerprints)
                if (first) { first = false; return@collect }
                signalCertificatePolicyChanged()
            }
        }
    }

    val selectedConfiguration: ProxyConfiguration?
        get() {
            val chainId = _selectedChainId.value
            if (chainId != null) {
                val chain = chainRepository.get(chainId) ?: return null
                return resolveChain(chain)
            }
            val id = _selectedConfigId.value ?: return null
            return configRepository.get(id)
        }

    fun setSelectedConfiguration(config: ProxyConfiguration) {
        _selectedChainId.value = null
        prefs.edit().remove("selectedChainId").apply()
        _selectedConfigId.value = config.id
        prefs.edit().putString("selectedConfigurationId", config.id.toString()).apply()

        if (_vpnStatus.value == VpnStatus.CONNECTED) {
            switchConfig(config)
        }
    }

    fun toggleVPN() {
        when (_vpnStatus.value) {
            VpnStatus.DISCONNECTED -> connect()
            VpnStatus.CONNECTED, VpnStatus.CONNECTING -> disconnect()
            else -> {}
        }
    }

    /**
     * Connects through TURN whatever the TURN mode or the master switch says — the power
     * button held for two seconds (spec §8, iOS `connectForcingTurn()`). Reconnects when
     * the tunnel is already up. Refuses with an error when the selected server has no
     * TURN relay or no VK link is known, rather than silently connecting directly.
     */
    fun connectForcingTurn() {
        val configuration = selectedConfiguration ?: return
        if (!isTurnAvailable(configuration)) {
            _startError.value = getApplication<Application>().getString(R.string.vpn_error_turn_unavailable)
            return
        }
        when (_vpnStatus.value) {
            VpnStatus.CONNECTED, VpnStatus.CONNECTING, VpnStatus.REASSERTING -> {
                if (turnSettings.turnForced) return // already going through TURN
                pendingReconnectForcesTurn = true
                reconnect()
            }
            VpnStatus.DISCONNECTED -> connect(forceTurn = true)
            VpnStatus.DISCONNECTING -> {}
        }
    }

    /**
     * The selected server has a usable TURN relay in a subscription and there is a VK
     * link to dial with — what the service will need to open the tunnel. The relay is
     * looked up for the hop TURN actually carries (a chain's entry hop).
     */
    fun isTurnAvailable(configuration: ProxyConfiguration): Boolean {
        val host = TurnRouting.relayHop(configuration).serverAddress
        val server = turnMetadataStore.server(host) ?: return false
        if (!server.isUsable) return false
        val manual = turnSettings.vkLink.trim()
        val shipped = turnMetadataStore.subscriptionVKLink()?.trim().orEmpty()
        return manual.isNotEmpty() || shipped.isNotEmpty()
    }

    /**
     * @param forceTurn route this session through TURN whatever the mode says. An
     *   ordinary connect clears a previous session's force.
     */
    fun connect(forceTurn: Boolean = false) {
        if (_isPreflighting.value) return
        val config = selectedConfiguration ?: return
        val context = getApplication<Application>()

        // Before anything reads the mode (the pre-flight here, routing in the service).
        turnSettings.turnForced = forceTurn
        _turnForcedInSession.value = forceTurn
        _connectionFailure.value = null
        turnWatchdog.reset()
        _captchaSeenInSession.value = false
        userRequestedDisconnect = false
        sessionReachedConnected = false
        updateConnectionStage()

        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            pendingConnectAfterPermission = true
            onRequestVpnPermission?.invoke(prepareIntent)
            return
        }

        startVpnService(config)
    }

    fun onVpnPermissionGranted() {
        if (pendingConnectAfterPermission) {
            pendingConnectAfterPermission = false
            val config = selectedConfiguration ?: return
            startVpnService(config)
        }
    }

    fun onVpnPermissionDenied() {
        pendingConnectAfterPermission = false
        _startError.value = "VPN permission denied"
    }

    /**
     * Applies the global allowInsecure setting to a proxy configuration's TLS config.
     * The global toggle overrides per-proxy setting when enabled.
     */
    private fun applyGlobalAllowInsecure(config: ProxyConfiguration): ProxyConfiguration {
        if (!allowInsecure) return config
        val tls = config.tls ?: return config
        if (tls.allowInsecure) return config  // already insecure
        val updatedTls = tls.copy(allowInsecure = true)
        val updatedChain = config.chain?.map { applyGlobalAllowInsecure(it) }
        return config.copy(tls = updatedTls, chain = updatedChain)
    }

    private fun startVpnService(config: ProxyConfiguration) {
        val context = getApplication<Application>()

        viewModelScope.launch {
            if (!runPreflight()) return@launch

            applyStatus(VpnStatus.CONNECTING)
            syncRoutingConfigurationToNE()

            // Resolve server domain on IO thread (avoids NetworkOnMainThreadException)
            val resolvedConfig = withContext(Dispatchers.IO) {
                resolveServerAddress(applyGlobalAllowInsecure(config))
            }
            DnsCache.setActiveProxyDomain(resolvedConfig.serverAddress)
            syncProxyServerAddresses(resolvedConfig)

            val configJson = json.encodeToString(ProxyConfiguration.serializer(), resolvedConfig)
            val intent = Intent(context, AnywhereVpnService::class.java).apply {
                action = AnywhereVpnService.ACTION_START
                putExtra(AnywhereVpnService.EXTRA_CONFIG, configJson)
            }

            try {
                context.startForegroundService(intent)
                // Status stays at CONNECTING until the service binder confirms
                // the tunnel is up via [serviceConnection.onServiceConnected]
                // + `isRunning`.
                bindToService()
            } catch (e: Exception) {
                logger.warning("Failed to send configuration to tunnel: ${e.message}")
                _connectionFailure.value = ConnectionFailure.VPN_START_FAILED
                applyStatus(VpnStatus.DISCONNECTED)
                _startError.value = e.message
            }
        }
    }

    /**
     * Auto mode only (it is the only mode that must tell "no network" from "censored
     * network"): probe before the tunnel starts and refuse to start on a dead network.
     * The verdict is handed to the service's auto-pilot, which cross-checks its own first
     * probe against it. Returns whether to go on.
     */
    private suspend fun runPreflight(): Boolean {
        if (!turnSettings.turnActiveForSession || turnSettings.effectiveTurnMode != TurnMode.AUTO) return true
        _isPreflighting.value = true
        updateConnectionStage()
        val verdict = try {
            ConnectivityProbe.classify(getApplication(), ProbeProfile.PREFLIGHT)
        } finally {
            _isPreflighting.value = false
        }
        turnSettings.setPreflightVerdictRaw(verdict.rawValue)
        if (verdict == ConnectivityVerdict.OFFLINE) {
            noteFailure(ConnectionFailure.NO_NETWORK)
            updateConnectionStage()
            return false
        }
        updateConnectionStage()
        return true
    }

    /** Explicit disconnect: a forced TURN session ends with it, a pending reconnect is dropped. */
    fun disconnect() {
        turnSettings.turnForced = false
        _turnForcedInSession.value = false
        pendingReconnect = false
        pendingReconnectForcesTurn = false
        stopTunnel()
    }

    private fun stopTunnel() {
        userRequestedDisconnect = true
        applyStatus(VpnStatus.DISCONNECTING)
        val context = getApplication<Application>()

        stopStatsPolling()

        val intent = Intent(context, AnywhereVpnService::class.java).apply {
            action = AnywhereVpnService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {}

        unbindFromService()
        _bytesIn.value = 0
        _bytesOut.value = 0
        applyStatus(VpnStatus.DISCONNECTED)
    }

    /**
     * Stops the VPN and automatically reconnects once disconnected. Keeps a forced TURN
     * session forced.
     */
    fun reconnect() {
        val status = _vpnStatus.value
        if (status != VpnStatus.CONNECTED && status != VpnStatus.CONNECTING) return
        pendingReconnect = true
        stopTunnel()
    }

    /** Status transitions and their side effects (iOS `applyStatus`). */
    private fun applyStatus(status: VpnStatus) {
        val previous = _vpnStatus.value
        _vpnStatus.value = status
        when (status) {
            VpnStatus.CONNECTED -> {
                sessionReachedConnected = true
                if (_connectionFailure.value == ConnectionFailure.VPN_START_FAILED) _connectionFailure.value = null
                startTurnPhaseWatch()
            }
            VpnStatus.DISCONNECTED, VpnStatus.DISCONNECTING -> {
                stopTurnPhaseWatch()
                if (status == VpnStatus.DISCONNECTED) {
                    // Dropped out of CONNECTING on its own: the tunnel never came up.
                    if (!sessionReachedConnected && !userRequestedDisconnect && previous == VpnStatus.CONNECTING) {
                        noteFailure(ConnectionFailure.VPN_START_FAILED)
                    }
                    if (pendingReconnect) {
                        pendingReconnect = false
                        // A reconnect keeps a forced TURN session forced.
                        val force = pendingReconnectForcesTurn || turnSettings.turnForced
                        pendingReconnectForcesTurn = false
                        updateConnectionStage()
                        connect(forceTurn = force)
                        return
                    }
                }
            }
            else -> {}
        }
        updateConnectionStage()
    }

    // MARK: - TURN phase watch (spec §6.2/§6.3)

    /**
     * Polls the service's TURN snapshot once a second while connected. Keeps polling past
     * READY: the phase has to be able to fall back when a pool reconnects.
     */
    private fun startTurnPhaseWatch() {
        if (!turnSettings.wantsTurnPhaseWatch) return
        if (turnWatchJob != null) return
        turnWatchJob = viewModelScope.launch {
            while (isActive) {
                val snapshot = TurnStatusMonitor.snapshot.value
                _turnPhase.value = snapshot.phase
                _turnAutoDecision.value = TurnAutoDecision.entries.firstOrNull { it.rawValue == snapshot.autoDecision }
                _turnPool.value = snapshot.pool
                evaluateTurnProgress()
                updateConnectionStage()
                delay(TurnStatusMonitor.POLL_INTERVAL_MS)
            }
        }
    }

    private fun evaluateTurnProgress() {
        val mode = if (turnSettings.turnActiveForSession) turnSettings.effectiveTurnMode else null
        val outcome = turnWatchdog.evaluate(_turnPhase.value, _turnAutoDecision.value, _turnPool.value, mode)
        _captchaSeenInSession.value = turnWatchdog.captchaSeenInSession
        if (outcome.alertOffline) {
            _startError.value = ConnectionFailure.NETWORK_LOST.message(getApplication())
        }
        if (outcome.clearTimeoutFailure) {
            val failure = _connectionFailure.value
            if (failure == ConnectionFailure.TURN_TIMEOUT || failure == ConnectionFailure.CAPTCHA_TIMEOUT) {
                _connectionFailure.value = null
            }
        }
        outcome.failure?.let { noteFailure(it) }
    }

    /**
     * The TURN mode or the master switch changed while connected: start or stop the watch
     * as if the session had connected under the new setting. Without this, switching
     * Off → Auto/On mid-session left the graph blind to TURN until the next reconnect.
     * (iOS has the same gap: its watch is only (re)evaluated on `.connected`.)
     */
    private fun syncTurnPhaseWatch() {
        if (_vpnStatus.value != VpnStatus.CONNECTED) return
        if (turnSettings.wantsTurnPhaseWatch) {
            if (turnWatchJob == null) {
                logger.info("[TURN] mode changed while connected; starting the phase watch")
                startTurnPhaseWatch()
            }
        } else if (turnWatchJob != null) {
            logger.info("[TURN] mode changed while connected; stopping the phase watch")
            stopTurnPhaseWatch()
            updateConnectionStage()
        }
    }

    private fun stopTurnPhaseWatch() {
        turnWatchJob?.cancel()
        turnWatchJob = null
        _turnPhase.value = null
        _turnAutoDecision.value = null
        _turnPool.value = null
        turnWatchdog.reset()
        _captchaSeenInSession.value = false
        // `connectionFailure` deliberately survives: the red node has to outlive the
        // DISCONNECTED that follows the failure it describes.
    }

    /** Records a failure once and surfaces it as an alert alongside the red node. */
    private fun noteFailure(failure: ConnectionFailure) {
        if (_connectionFailure.value == failure) return
        _connectionFailure.value = failure
        _startError.value = failure.message(getApplication())
    }

    /** The captcha came up (sheet shown / monitor saw it): latch it for the session. */
    fun noteCaptchaSeen() {
        turnWatchdog.noteCaptchaSeen()
        _captchaSeenInSession.value = true
        updateConnectionStage()
    }

    /**
     * Feed from the captcha monitor (`captchaWaiting`): a captcha server is up. The graph
     * moves to the captcha node ahead of the next phase poll.
     */
    fun setCaptchaPending(waiting: Boolean) {
        _captchaPending.value = waiting
        if (waiting) noteCaptchaSeen() else updateConnectionStage()
    }

    /** Recompute the stage, e.g. after the TURN mode was changed in settings. */
    fun refreshConnectionStage() = updateConnectionStage()

    /** The mode the graph follows: forced → ON; feature off → null; else the stored mode. */
    private fun graphTurnMode(): TurnMode? = when {
        _turnForcedInSession.value -> TurnMode.ON
        turnSettings.featureEnabled -> turnSettings.turnMode
        else -> null
    }

    private fun updateConnectionStage() {
        _connectionStage.value = ConnectionStage.resolve(
            status = _vpnStatus.value,
            isPreflighting = _isPreflighting.value,
            turnMode = graphTurnMode(),
            autoDecision = _turnAutoDecision.value,
            turnPhase = _turnPhase.value,
            turnUsable = _turnPool.value?.usable,
            captchaPending = _captchaPending.value,
            captchaSeen = _captchaSeenInSession.value,
            failure = _connectionFailure.value,
        )
    }

    /**
     * The selection is gone (its subscription or configuration was deleted) while the
     * tunnel runs: there is nothing left for it to carry (spec §6.4).
     */
    private fun disconnectIfSelectionGone() {
        if (selectedConfiguration != null) return
        val status = _vpnStatus.value
        if (status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING || status == VpnStatus.REASSERTING) {
            disconnect()
        }
    }

    private fun switchConfig(config: ProxyConfiguration) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val resolvedConfig = withContext(Dispatchers.IO) {
                resolveServerAddress(applyGlobalAllowInsecure(config))
            }
            DnsCache.setActiveProxyDomain(resolvedConfig.serverAddress)
            syncProxyServerAddresses(resolvedConfig)
            val configJson = json.encodeToString(ProxyConfiguration.serializer(), resolvedConfig)
            val intent = Intent(context, AnywhereVpnService::class.java).apply {
                action = AnywhereVpnService.ACTION_SWITCH_CONFIG
                putExtra(AnywhereVpnService.EXTRA_CONFIG, configJson)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private fun bindToService() {
        if (serviceBound) return
        val context = getApplication<Application>()
        val intent = Intent(context, AnywhereVpnService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindFromService() {
        if (!serviceBound) return
        val context = getApplication<Application>()
        try {
            context.unbindService(serviceConnection)
        } catch (_: Exception) {}
        vpnService = null
        serviceBound = false
    }

    private fun startStatsPolling() {
        if (statsJob != null) return
        statsJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val service = vpnService
                if (service != null && service.isRunning) {
                    val (bytesIn, bytesOut) = service.getStats()
                    _bytesIn.value = bytesIn
                    _bytesOut.value = bytesOut
                } else if (service != null && !service.isRunning &&
                    _vpnStatus.value == VpnStatus.CONNECTED) {
                    // Service is bound but no longer running — it actually died
                    applyStatus(VpnStatus.DISCONNECTED)
                    _bytesIn.value = 0
                    _bytesOut.value = 0
                    break
                }
                // If service is null, binding is still in progress — keep polling
            }
        }
    }

    private fun stopStatsPolling() {
        statsJob?.cancel()
        statsJob = null
    }

    /**
     * Resolves server address to IP before tunnel starts (avoids DNS-over-tunnel loop).
     * If already an IP, returns config as-is. If a domain, resolves via system DNS.
     */
    private fun resolveServerAddress(config: ProxyConfiguration): ProxyConfiguration {
        val resolvedConfig = resolveAddress(config)
        val resolvedChain = config.chain?.map { resolveAddress(it) }
        return if (resolvedChain != null) resolvedConfig.copy(chain = resolvedChain) else resolvedConfig
    }

    private fun resolveAddress(config: ProxyConfiguration): ProxyConfiguration {
        val resolved = resolveServerAddress(config.serverAddress) ?: return config
        return config.copy(resolvedIP = resolved)
    }

    private fun resolveServerAddress(address: String): String? {
        val bare = if (address.startsWith("[") && address.endsWith("]")) {
            address.substring(1, address.length - 1)
        } else {
            address
        }

        if (DnsCache.isIpAddress(bare)) return bare

        return try {
            val all = InetAddress.getAllByName(bare)
            val resolved = all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
            resolved?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(turnSettingsListener)
        stopStatsPolling()
        unbindFromService()
    }

    fun clearStartError() {
        _startError.value = null
    }

    fun clearOrphanedRuleSetNames() {
        _orphanedRuleSetNames.value = emptyList()
    }

    fun addConfiguration(config: ProxyConfiguration) {
        configRepository.add(config)
        if (_selectedConfigId.value == null) {
            setSelectedConfiguration(config)
        }
    }

    fun updateConfiguration(config: ProxyConfiguration) {
        configRepository.update(config)
    }

    fun deleteConfiguration(config: ProxyConfiguration) {
        configRepository.delete(config.id)
        if (_selectedConfigId.value == config.id) {
            val remaining = configRepository.getAll()
            _selectedConfigId.value = remaining.firstOrNull()?.id
            prefs.edit().putString("selectedConfigurationId", _selectedConfigId.value?.toString()).apply()
        }
        val chainId = _selectedChainId.value
        if (chainId != null) {
            val chain = chainRepository.get(chainId)
            if (chain == null || resolveChain(chain) == null) {
                _selectedChainId.value = null
                prefs.edit().remove("selectedChainId").apply()
                ensureValidSelection()
            }
        }
        checkOrphanedRuleSets()
        disconnectIfSelectionGone()
    }

    fun configurations(forSubscription: Subscription): List<ProxyConfiguration> {
        return configRepository.getAll().filter { it.subscriptionId == forSubscription.id }
    }

    fun addSubscription(
        configurations: List<ProxyConfiguration>,
        subscription: Subscription,
        turn: TurnMetadata? = null
    ) {
        subscriptionRepository.add(subscription)
        if (turn != null) turnMetadataStore.setMetadata(turn, subscription.id)
        configurations.forEach { config ->
            configRepository.add(config.copy(subscriptionId = subscription.id))
        }
        if (_selectedConfigId.value == null) {
            configurations.firstOrNull()?.let { setSelectedConfiguration(it) }
        }
    }

    fun toggleSubscriptionCollapsed(subscription: Subscription) {
        subscriptionRepository.update(subscription.copy(collapsed = !subscription.collapsed))
    }

    fun renameSubscription(subscription: Subscription, newName: String) {
        subscriptionRepository.update(subscription.copy(name = newName, isNameCustomized = true))
    }

    fun deleteSubscription(subscription: Subscription) {
        subscriptionRepository.delete(subscription.id, configRepository)
        turnMetadataStore.removeMetadata(subscription.id)
        AppRepositories.sync(getApplication()).remove(subscription.id)
        ensureValidSelection()
        checkOrphanedRuleSets()
        disconnectIfSelectionGone()
    }

    /** Shared with the background auto-refresh — see [SubscriptionUpdater]. Throws on failure. */
    suspend fun updateSubscription(subscription: Subscription) {
        subscriptionUpdater.update(subscription)
        ensureValidSelection()
    }

    fun testLatency(forConfig: ProxyConfiguration) {
        syncProxyServerAddresses(forConfig)
        _latencyResults.value = _latencyResults.value + (forConfig.id to LatencyResult.Testing)
        viewModelScope.launch {
            val result = LatencyTester.test(applyGlobalAllowInsecure(forConfig))
            _latencyResults.value = _latencyResults.value + (forConfig.id to result)
        }
    }

    /** Tests latency for a specific set of configurations. The caller filters
     *  out collapsed subscriptions before invoking — we don't filter here. */
    fun testLatencies(forConfigs: List<ProxyConfiguration>) {
        if (forConfigs.isEmpty()) return
        syncProxyServerAddresses(forConfigs)
        forConfigs.forEach { config ->
            _latencyResults.value = _latencyResults.value + (config.id to LatencyResult.Testing)
        }
        viewModelScope.launch {
            LatencyTester.testAll(forConfigs.map { applyGlobalAllowInsecure(it) }).collect { (id, result) ->
                _latencyResults.value = _latencyResults.value + (id to result)
            }
        }
    }

    fun addChain(chain: ProxyChain) {
        chainRepository.add(chain)
    }

    fun updateChain(chain: ProxyChain) {
        chainRepository.update(chain)
        if (_selectedChainId.value == chain.id) {
            val resolved = resolveChain(chain)
            if (resolved != null && _vpnStatus.value == VpnStatus.CONNECTED) {
                switchConfig(resolved)
            }
        }
    }

    fun deleteChain(chain: ProxyChain) {
        chainRepository.delete(chain.id)
        if (_selectedChainId.value == chain.id) {
            _selectedChainId.value = null
            prefs.edit().remove("selectedChainId").apply()
            ensureValidSelection()
        }
    }

    fun selectChain(chain: ProxyChain) {
        val resolved = resolveChain(chain) ?: return
        _selectedChainId.value = chain.id
        prefs.edit()
            .putString("selectedChainId", chain.id.toString())
            .remove("selectedConfigurationId")
            .apply()
        _selectedConfigId.value = null

        if (_vpnStatus.value == VpnStatus.CONNECTED) {
            switchConfig(resolved)
        }
    }

    /**
     * Resolves a chain into a composite ProxyConfiguration.
     * The last proxy becomes the main config; preceding proxies fill the [chain] field.
     */
    fun resolveChain(chain: ProxyChain): ProxyConfiguration? {
        val configs = chain.proxyIds.mapNotNull { id -> configRepository.get(id) }
        if (configs.size != chain.proxyIds.size || configs.size < 2) return null
        val exitProxy = configs.last()
        val chainProxies = configs.dropLast(1)
        return exitProxy.copy(
            name = chain.name,
            chain = chainProxies
        )
    }

    fun testChainLatency(chain: ProxyChain) {
        val resolved = resolveChain(chain) ?: return
        syncProxyServerAddresses(resolved)
        _chainLatencyResults.value = _chainLatencyResults.value + (chain.id to LatencyResult.Testing)
        viewModelScope.launch {
            val result = LatencyTester.test(applyGlobalAllowInsecure(resolved))
            _chainLatencyResults.value = _chainLatencyResults.value + (chain.id to result)
        }
    }

    fun testAllChainLatencies() {
        val chains = chainRepository.getAll()
        val resolvedChains = chains.mapNotNull { chain ->
            resolveChain(chain)?.let { chain.id to it }
        }
        syncProxyServerAddresses(resolvedChains.map { it.second })
        resolvedChains.forEach { (chainId, _) ->
            _chainLatencyResults.value = _chainLatencyResults.value + (chainId to LatencyResult.Testing)
        }
        viewModelScope.launch {
            val configs = resolvedChains.map { applyGlobalAllowInsecure(it.second) }
            val idMap = resolvedChains.associate { (chainId, config) -> config.id to chainId }
            LatencyTester.testAll(configs).collect { (configId, result) ->
                val chainId = idMap[configId] ?: return@collect
                _chainLatencyResults.value = _chainLatencyResults.value + (chainId to result)
            }
        }
    }

    fun syncRoutingConfigurationToNE() {
        ruleSetRepository.syncRoutingFile(
            configRepository.getAll()
        ) { address -> resolveServerAddress(address) }
        if (_vpnStatus.value == VpnStatus.CONNECTED) {
            prefs.edit().putLong("routingChanged", System.currentTimeMillis()).apply()
        }
    }


    /** Collects all proxy server addresses (domains + resolved IPs) from all
     *  configurations and sends them to the VPN service. This prevents routing
     *  loops when proxy server domains match routing rules. */
    private fun syncProxyServerAddresses(configuration: ProxyConfiguration) {
        syncProxyServerAddresses(listOf(configuration))
    }

    private fun syncProxyServerAddresses(configurations: List<ProxyConfiguration>) {
        val addresses = linkedSetOf<String>()
        for (config in configurations) {
            collectProxyServerAddresses(config, addresses)
        }

        val persisted = addresses.toList()
        prefs.edit().putString("proxyServerAddresses", json.encodeToString(persisted)).apply()
        vpnService?.updateProxyServerAddresses(persisted)
    }

    private fun collectProxyServerAddresses(
        configuration: ProxyConfiguration,
        addresses: MutableSet<String>
    ) {
        addresses.add(configuration.serverAddress)
        configuration.resolvedIP?.let(addresses::add)
        DnsCache.cachedIPs(configuration.serverAddress)?.let(addresses::addAll)
        configuration.chain?.forEach { hop ->
            collectProxyServerAddresses(hop, addresses)
        }
    }

    /** "rule" (default) applies routing rules per-destination; "global" routes everything through the proxy. */
    var proxyMode: String
        get() = prefs.getString("proxyMode", "rule") ?: "rule"
        set(value) = prefs.edit().putString("proxyMode", value).apply()

    var bypassCountryCode: String
        get() = prefs.getString("bypassCountryCode", "") ?: ""
        set(value) = prefs.edit().putString("bypassCountryCode", value).apply()

    /**
     * IPv6 DNS lookup toggle — a single knob that controls both IPv6 routes
     * in the TUN interface and fake-IPv6 answers for AAAA queries. When off,
     * IPv6 is effectively disabled.
     */
    var ipv6DnsEnabled: Boolean
        get() = prefs.getBoolean("ipv6DnsEnabled", false)
        set(value) = prefs.edit().putBoolean("ipv6DnsEnabled", value).apply()

    var encryptedDnsEnabled: Boolean
        get() = prefs.getBoolean("encryptedDnsEnabled", false)
        set(value) = prefs.edit().putBoolean("encryptedDnsEnabled", value).apply()

    var encryptedDnsProtocol: String
        get() = prefs.getString("encryptedDnsProtocol", "doh") ?: "doh"
        set(value) = prefs.edit().putString("encryptedDnsProtocol", value).apply()

    var encryptedDnsServer: String
        get() = prefs.getString("encryptedDnsServer", "") ?: ""
        set(value) = prefs.edit().putString("encryptedDnsServer", value).apply()

    /**
     * When true, drop UDP/443 traffic with ICMP port-unreachable so HTTP/3 clients
     * fall back to HTTP/2. Defaults to true.
     */
    var blockQuicEnabled: Boolean
        get() = prefs.getBoolean("blockQuicEnabled", true)
        set(value) = prefs.edit().putBoolean("blockQuicEnabled", value).apply()

    var allowInsecure: Boolean
        get() = prefs.getBoolean("allowInsecure", false)
        set(value) {
            if (value == allowInsecure) return
            prefs.edit().putBoolean("allowInsecure", value).apply()
            // Update the in-memory policy immediately so non-tunnel callers
            // (e.g. SubscriptionFetcher) observe the new value without waiting
            // for LwipStack to reload after a prefs listener fires.
            CertificatePolicy.setAllowInsecure(value)
            // Toggling allowInsecure tears down active TLS connections so the
            // new policy applies immediately.
            signalCertificatePolicyChanged()
        }

    /** UI-only flag gating experimental features (e.g. custom rule set creation). */
    var experimentalEnabled: Boolean
        get() = prefs.getBoolean("experimentalEnabled", false)
        set(value) = prefs.edit().putBoolean("experimentalEnabled", value).apply()

    /**
     * Toggle for Remnawave-panel subscriptions that require an `x-hwid` header keyed
     * to this install. Off by default so the identifier is never sent unless the
     * user opts in.
     */
    var remnawaveHWIDEnabled: Boolean
        get() = prefs.getBoolean("remnawaveHWIDEnabled", false)
        set(value) = prefs.edit().putBoolean("remnawaveHWIDEnabled", value).apply()

    /**
     * Persistent per-install identifier used as the `x-hwid` header value for
     * Remnawave subscriptions. Generates a UUID on first read and reuses it
     * thereafter.
     */
    val deviceIdentifier: String
        get() {
            prefs.getString("identifier", null)?.let { return it }
            val generated = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("identifier", generated).apply()
            return generated
        }

    /**
     * Signals a certificate policy change. LwipStack observes this key and
     * tears down active connections.
     */
    fun signalCertificatePolicyChanged() {
        prefs.edit().putLong("certificatePolicyChanged", System.currentTimeMillis()).apply()
    }

    val hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean("hasCompletedOnboarding", false)

    fun completeOnboarding(bypassCountryCode: String, adBlockEnabled: Boolean) {
        if (bypassCountryCode.isNotEmpty()) {
            this.bypassCountryCode = bypassCountryCode
        }

        if (adBlockEnabled) {
            val adBlock = ruleSetRepository.ruleSets.value.find { it.name == "ADBlock" }
            if (adBlock != null) {
                ruleSetRepository.updateAssignment(adBlock, "REJECT")
            }
        }

        syncRoutingConfigurationToNE()

        prefs.edit().putBoolean("hasCompletedOnboarding", true).apply()
    }

    private val subscriptionUpdater: SubscriptionUpdater = AppRepositories.updater(application)

    // Last in the class so every property above is initialised: a background refresh
    // may have dropped the selected configuration.
    init {
        viewModelScope.launch {
            subscriptionUpdater.updated.collect { ensureValidSelection() }
        }
    }

    private fun ensureValidSelection() {
        val selectedId = _selectedConfigId.value
        if (selectedId == null || configRepository.get(selectedId) == null) {
            val newId = configRepository.getAll().firstOrNull()?.id
            _selectedConfigId.value = newId
            prefs.edit().putString("selectedConfigurationId", newId?.toString()).apply()
        }
    }

    private fun checkOrphanedRuleSets() {
        val configIds = configRepository.getAll().map { it.id.toString() }.toSet()
        val orphaned = ruleSetRepository.clearOrphanedAssignments(configIds)
        if (orphaned.isNotEmpty()) {
            _orphanedRuleSetNames.value = orphaned
            syncRoutingConfigurationToNE()
        }
    }
}

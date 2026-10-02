package com.argsment.anywhere

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.argsment.anywhere.turn.TurnCaptchaMonitor
import com.argsment.anywhere.turn.TurnCaptchaNotifications
import com.argsment.anywhere.turn.TurnEnvironment
import com.argsment.anywhere.ui.navigation.AppNavigation
import com.argsment.anywhere.ui.onboarding.OnboardingScreen
import com.argsment.anywhere.ui.theme.AnywhereTheme
import com.argsment.anywhere.ui.turn.TurnCaptchaHost
import com.argsment.anywhere.viewmodel.VpnViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: VpnViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.onVpnPermissionGranted()
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel.onRequestVpnPermission = { intent ->
            vpnPermissionLauncher.launch(intent)
        }

        handleDeepLinkIntent(intent)
        handleCaptchaIntent(intent)

        setContent {
            AnywhereTheme {
                var onboardingCompleted by mutableStateOf(viewModel.hasCompletedOnboarding)

                if (!onboardingCompleted) {
                    OnboardingScreen(
                        onComplete = { bypassCountryCode, adBlockEnabled ->
                            viewModel.completeOnboarding(bypassCountryCode, adBlockEnabled)
                            onboardingCompleted = true
                        }
                    )
                } else {
                    AppNavigation(viewModel = viewModel)
                    // TURN captcha sheet, over every tab (spec §9.4).
                    TurnCaptchaHost(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
        handleCaptchaIntent(intent)
    }

    // The VPN service reads this to decide whether a captcha needs a notification
    // (iOS: `setAppInForeground(scenePhase == .active)`). Single-activity app, so the
    // activity's started state is the app's foreground state.
    override fun onStart() {
        super.onStart()
        TurnEnvironment.install(this)
        TurnEnvironment.settings?.appIsForeground = true
    }

    override fun onStop() {
        super.onStop()
        TurnEnvironment.settings?.appIsForeground = false
    }

    /** A tap on the "captcha required" notification re-arms the captcha sheet. */
    private fun handleCaptchaIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(TurnCaptchaNotifications.EXTRA_OPEN_CAPTCHA, false) != true) return
        intent.removeExtra(TurnCaptchaNotifications.EXTRA_OPEN_CAPTCHA)
        TurnCaptchaMonitor.requestShow()
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        viewModel.onDeepLink(uri)
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.onRequestVpnPermission = null
    }
}

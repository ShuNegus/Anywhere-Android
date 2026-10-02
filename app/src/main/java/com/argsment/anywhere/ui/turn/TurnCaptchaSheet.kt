package com.argsment.anywhere.ui.turn

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.argsment.anywhere.R
import com.argsment.anywhere.turn.TurnCaptcha
import com.argsment.anywhere.turn.TurnCaptchaMonitor
import com.argsment.anywhere.turn.TurnCaptchaNotifications
import com.argsment.anywhere.turn.TurnEnvironment
import com.argsment.anywhere.turn.captchaWatchApplies
import com.argsment.anywhere.viewmodel.VpnStatus
import com.argsment.anywhere.viewmodel.VpnViewModel

/**
 * App-wide captcha plumbing, hosted once at the activity root so the sheet opens over any
 * tab (iOS `MainTabView`, spec §9.3/§9.4):
 * - runs [TurnCaptchaMonitor] while `turnActiveForSession && effectiveMode != off &&
 *   connected` (the iOS app checks the stored mode here; the effective one is right);
 * - asks for the notification permission when that watch starts;
 * - shows [TurnCaptchaSheet] whenever the monitor says so.
 */
@Composable
fun TurnCaptchaHost(viewModel: VpnViewModel) {
    val status by viewModel.vpnStatus.collectAsState()
    // Settings are plain prefs; the forced flag is written before connecting, so reading
    // them whenever the status changes sees the session's values.
    val watching = status == VpnStatus.CONNECTED &&
        TurnEnvironment.settings?.captchaWatchApplies() == true
    val requestPermission = rememberTurnNotificationPermissionRequest()

    LaunchedEffect(watching) {
        TurnCaptchaMonitor.setActive(watching)
        if (watching) requestPermission()
    }

    val sheet by TurnCaptchaMonitor.sheetState.collectAsState()
    if (sheet.showCaptcha) {
        TurnCaptchaSheet(onDismiss = { TurnCaptchaMonitor.userDismissed() })
    }
}

/**
 * Returns a callback that asks for `POST_NOTIFICATIONS` (API 33+) unless it is granted or
 * we have asked before — a refusal is not re-asked (iOS `TurnNotifications`).
 */
@Composable
fun rememberTurnNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Nothing to do either way: the notifier checks the grant before posting. */ }
    return remember(context, launcher) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                TurnCaptchaNotifications.shouldRequestPermission(context)
            ) {
                TurnCaptchaNotifications.markPermissionRequested(context)
                launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/**
 * Full-screen dialog with the VK captcha page the core serves on loopback (iOS
 * `TurnCaptchaSheet`). A bottom sheet would fight the page for vertical drags, so this
 * is a full-screen dialog; Close, back and the system dismiss gesture all count as the
 * user closing it ([onDismiss] → `TurnCaptchaMonitor.userDismissed()`). It also goes
 * away on its own when the server disappears (solved or abandoned).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurnCaptchaSheet(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.turn_captcha_solve)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.turn_captcha_close),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            CaptchaWebView(modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}

/** Nothing about a captcha is worth keeping: no cache, cookies and storage wiped on close. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CaptchaWebView(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.setSupportMultipleWindows(false)
                webViewClient = WebViewClient()
                loadUrl(TurnCaptcha.URL)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.clearCache(true)
            webView.clearHistory()
            webView.destroy()
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        },
    )
}

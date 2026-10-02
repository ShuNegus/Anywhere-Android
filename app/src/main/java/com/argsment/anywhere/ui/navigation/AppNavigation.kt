package com.argsment.anywhere.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.argsment.anywhere.R
import com.argsment.anywhere.ui.home.HomeScreen
import com.argsment.anywhere.ui.proxy.ChainListScreen
import com.argsment.anywhere.ui.proxy.ProxyListScreen
import com.argsment.anywhere.ui.settings.ProModeVisibility
import com.argsment.anywhere.ui.settings.SettingsScreen
import com.argsment.anywhere.viewmodel.VpnViewModel
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object ProxiesRoute
@Serializable object ChainsRoute
@Serializable object SettingsRoute

data class TopLevelRoute(
    val titleResId: Int,
    val route: Any,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
fun AppNavigation(viewModel: VpnViewModel) {
    val navController = rememberNavController()

    val proModeEnabled by viewModel.proModeEnabled.collectAsState()
    val visibility = ProModeVisibility.of(proModeEnabled, viewModel.turnSettings.featureEnabled)

    // With Pro Mode, switch to the Proxies tab when a deep link arrives so ProxyListScreen
    // can consume it (upstream). Without it, go Home, which opens the add sheet itself.
    val pendingDeepLink by viewModel.pendingDeepLinkUrl.collectAsState()
    LaunchedEffect(pendingDeepLink, visibility.deepLinkOpensProxiesTab) {
        if (pendingDeepLink != null) {
            // Without Pro Mode: Home, whose add sheet takes the link.
            val target: Any = if (visibility.deepLinkOpensProxiesTab) ProxiesRoute else HomeRoute
            navController.navigate(target) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val allTopLevelRoutes = listOf(
        TopLevelRoute(
            titleResId = R.string.home,
            route = HomeRoute,
            selectedIcon = Icons.Filled.Home,
            unselectedIcon = Icons.Outlined.Home
        ),
        TopLevelRoute(
            titleResId = R.string.proxies,
            route = ProxiesRoute,
            selectedIcon = ImageVector.vectorResource(R.drawable.ic_network_filled),
            unselectedIcon = ImageVector.vectorResource(R.drawable.ic_network_outlined)
        ),
        TopLevelRoute(
            titleResId = R.string.chains,
            route = ChainsRoute,
            selectedIcon = Icons.Filled.Link,
            unselectedIcon = Icons.Filled.Link
        ),
        TopLevelRoute(
            titleResId = R.string.settings,
            route = SettingsRoute,
            selectedIcon = Icons.Filled.Settings,
            unselectedIcon = Icons.Outlined.Settings
        )
    )

    // Pro Mode hides the Proxies and Chains tabs (spec §10).
    val topLevelRoutes = allTopLevelRoutes.filter { route ->
        when (route.route) {
            ProxiesRoute -> visibility.proxiesTab
            ChainsRoute -> visibility.chainsTab
            else -> true
        }
    }

    // Turning Pro Mode off while on a hidden tab lands on Home (iOS `MainTabView`).
    val navBackStackEntryForPro by navController.currentBackStackEntryAsState()
    LaunchedEffect(visibility.proxiesTab, visibility.chainsTab, navBackStackEntryForPro) {
        val destination = navBackStackEntryForPro?.destination ?: return@LaunchedEffect
        val onHiddenTab = (!visibility.proxiesTab && destination.hasRoute(ProxiesRoute::class)) ||
            (!visibility.chainsTab && destination.hasRoute(ChainsRoute::class))
        if (onHiddenTab) {
            navController.navigate(HomeRoute) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            NavigationBar {
                topLevelRoutes.forEach { topLevelRoute ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.hasRoute(topLevelRoute.route::class)
                    } == true

                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) topLevelRoute.selectedIcon else topLevelRoute.unselectedIcon,
                                contentDescription = stringResource(topLevelRoute.titleResId)
                            )
                        },
                        label = { Text(stringResource(topLevelRoute.titleResId)) },
                        selected = selected,
                        onClick = {
                            navController.navigate(topLevelRoute.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute
        ) {
            composable<HomeRoute> {
                HomeScreen(viewModel = viewModel, contentPadding = innerPadding)
            }
            composable<ProxiesRoute> {
                Box(modifier = Modifier.padding(innerPadding)) {
                    ProxyListScreen(viewModel = viewModel)
                }
            }
            composable<ChainsRoute> {
                Box(modifier = Modifier.padding(innerPadding)) {
                    ChainListScreen(viewModel = viewModel)
                }
            }
            composable<SettingsRoute> {
                Box(modifier = Modifier.padding(innerPadding)) {
                    SettingsScreen(viewModel = viewModel)
                }
            }
        }

        // Routing Rules Updated alert — surfaces orphaned rule-set assignments that
        // were silently re-pointed to Default after a referenced proxy was deleted.
        // Mirrors iOS ContentView's "Routing Rules Updated" alert.
        val orphanedNames by viewModel.orphanedRuleSetNames.collectAsState()
        if (orphanedNames.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { viewModel.clearOrphanedRuleSetNames() },
                title = { Text(stringResource(R.string.routing_rules_updated_title)) },
                text = {
                    Text(stringResource(R.string.routing_rules_updated_message,
                        orphanedNames.joinToString(", ")))
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearOrphanedRuleSetNames() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }
    }
}

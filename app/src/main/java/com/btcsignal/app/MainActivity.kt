package com.btcsignal.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.btcsignal.app.live.LiveMonitoringService
import com.btcsignal.app.ui.screens.*
import com.btcsignal.app.ui.theme.BtcSignalTheme
import com.btcsignal.app.ui.theme.GlassStroke
import com.btcsignal.app.ui.theme.GradientBottom
import com.btcsignal.app.ui.theme.GradientMid
import com.btcsignal.app.ui.theme.GradientTop
import com.btcsignal.app.ui.theme.TabAiColor
import com.btcsignal.app.ui.theme.TabBacktestColor
import com.btcsignal.app.ui.theme.TabHistoryColor
import com.btcsignal.app.ui.theme.TabLiveColor
import com.btcsignal.app.ui.theme.TabPerformanceColor
import com.btcsignal.app.ui.theme.TabSettingsColor
import com.btcsignal.app.ui.theme.TabStrategiesColor
import com.btcsignal.app.ui.theme.TextSecondary

private data class Tab(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
)

private val TABS = listOf(
    Tab("live", "Live", Icons.Filled.ShowChart, TabLiveColor),
    Tab("strategies", "Strategies", Icons.Filled.ListAlt, TabStrategiesColor),
    Tab("backtest", "Backtest", Icons.Filled.History, TabBacktestColor),
    Tab("history", "History", Icons.Filled.Receipt, TabHistoryColor),
    Tab("performance", "Performance", Icons.Filled.BarChart, TabPerformanceColor),
    Tab("ai", "AI", Icons.Filled.SmartToy, TabAiColor),
    Tab("settings", "Settings", Icons.Filled.Settings, TabSettingsColor)
)

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result observed via ContextCompat.checkSelfPermission where needed */ }

    private var highlightSignalIdState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        startLiveMonitoring()
        highlightSignalIdState.value = intent?.data?.lastPathSegment

        setContent {
            BtcSignalTheme {
                AppScaffold(highlightSignalIdState.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        highlightSignalIdState.value = intent.data?.lastPathSegment
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startLiveMonitoring() {
        val intent = Intent(this, LiveMonitoringService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
private fun AppScaffold(highlightSignalId: String?) {
    val navController = rememberNavController()

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GradientTop, GradientMid, GradientBottom)))
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(
                    modifier = Modifier
                        .border(width = 1.dp, color = GlassStroke),
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp
                ) {
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = backStackEntry?.destination?.hierarchy?.firstOrNull()?.route
                    TABS.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = null,
                            alwaysShowLabel = false,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = tab.color,
                                unselectedIconColor = TextSecondary,
                                indicatorColor = tab.color.copy(alpha = 0.20f)
                            )
                        )
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "live",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                composable("live") { LiveSignalScreen(highlightSignalId) }
                composable("strategies") { StrategiesScreen() }
                composable("backtest") { BacktestScreen() }
                composable("history") { HistoryScreen(highlightSignalId) }
                composable("performance") { PerformanceScreen() }
                composable("ai") { AiScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    }
}

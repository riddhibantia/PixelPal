package com.pixelpal.app.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.pixelpal.app.presentation.screens.activity.ActivityCenterScreen
import com.pixelpal.app.presentation.screens.agent.SCANNED_URL_KEY
import com.pixelpal.app.presentation.screens.customize.CustomizeScreen
import com.pixelpal.app.presentation.screens.home.HomeScreen
import com.pixelpal.app.presentation.screens.onboarding.OnboardingScreen
import com.pixelpal.app.presentation.screens.reminders.CreateReminderScreen
import com.pixelpal.app.presentation.screens.reminders.ReminderListScreen
import com.pixelpal.app.presentation.screens.settings.AboutScreen
import com.pixelpal.app.presentation.screens.settings.OverlaySettingsScreen
import com.pixelpal.app.presentation.screens.settings.PermissionsScreen
import com.pixelpal.app.presentation.screens.settings.ProfileScreen
import com.pixelpal.app.presentation.screens.settings.SettingsScreen
import com.pixelpal.app.presentation.screens.tasks.NewTaskScreen
import com.pixelpal.app.presentation.screens.tasks.TaskDetailScreen
import com.pixelpal.app.presentation.screens.tasks.TasksScreen

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Auth : Screen("auth")
    object Home : Screen("home")
    object Reminders : Screen("reminders")
    object CreateReminder : Screen("create_reminder")
    object Customize : Screen("customize")
    object Settings : Screen("settings")
    object Profile : Screen("profile")
    object OverlaySettings : Screen("overlay_settings")
    object About : Screen("about")
    object Permissions : Screen("permissions")
    /** Multi-agent hub backed by the pixelpal-bridge (the "Agent" tab). */
    object ConnectedAgents : Screen("connected_agents")
    object Tasks : Screen("tasks")
    object NewTask : Screen("new_task")
    object TaskDetail : Screen("task_detail/{taskId}") {

        fun route(taskId: Long): String = "task_detail/$taskId"
    }
    object ActivityCenter : Screen("activity_center")
    object QrScan : Screen("qr_scan")

    companion object {
    }
}

private val mainTabRoutes = setOf(
    Screen.Home.route,
    Screen.Tasks.route,
    Screen.ConnectedAgents.route,
    Screen.Reminders.route,
    Screen.Settings.route
)

@Composable
fun PixelPalNavGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            val isTabSwitch = initialState.destination.route in mainTabRoutes && targetState.destination.route in mainTabRoutes
            if (isTabSwitch) {
                fadeIn(animationSpec = tween(220, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
            } else {
                slideInHorizontally(animationSpec = tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { (it * 0.25f).toInt() } +
                    fadeIn(animationSpec = tween(320))
            }
        },
        exitTransition = {
            val isTabSwitch = initialState.destination.route in mainTabRoutes && targetState.destination.route in mainTabRoutes
            if (isTabSwitch) {
                fadeOut(animationSpec = tween(150, easing = androidx.compose.animation.core.FastOutLinearInEasing))
            } else {
                slideOutHorizontally(animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -(it * 0.25f).toInt() } +
                    fadeOut(animationSpec = tween(220))
            }
        },
        popEnterTransition = {
            val isTabSwitch = initialState.destination.route in mainTabRoutes && targetState.destination.route in mainTabRoutes
            if (isTabSwitch) {
                fadeIn(animationSpec = tween(220, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
            } else {
                slideInHorizontally(animationSpec = tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -(it * 0.25f).toInt() } +
                    fadeIn(animationSpec = tween(320))
            }
        },
        popExitTransition = {
            val isTabSwitch = initialState.destination.route in mainTabRoutes && targetState.destination.route in mainTabRoutes
            if (isTabSwitch) {
                fadeOut(animationSpec = tween(150, easing = androidx.compose.animation.core.FastOutLinearInEasing))
            } else {
                slideOutHorizontally(animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { (it * 0.25f).toInt() } +
                    fadeOut(animationSpec = tween(220))
            }
        }
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onOnboardingComplete = {
                    // Every user gets their own profile: onboarding lands on auth first.
                    navController.navigate(Screen.Auth.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Auth.route) {
            com.pixelpal.app.presentation.screens.auth.AuthScreen(
                onAuthSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }
        composable(Screen.Reminders.route) {
            ReminderListScreen(navController = navController)
        }
        composable(Screen.CreateReminder.route) {
            CreateReminderScreen(navController = navController)
        }
        composable(Screen.Customize.route) {
            CustomizeScreen(navController = navController)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
        composable(Screen.Profile.route) {
            ProfileScreen(navController = navController)
        }
        composable(Screen.OverlaySettings.route) {
            OverlaySettingsScreen(navController = navController)
        }
        composable(Screen.About.route) {
            AboutScreen(navController = navController)
        }
        composable(Screen.Permissions.route) { PermissionsScreen(navController = navController) }
        composable(Screen.ActivityCenter.route) {
            ActivityCenterScreen(navController = navController)
        }
        composable(Screen.ConnectedAgents.route) {
            // QrScanScreen writes its result onto this tab's entry handle.
            val handle = navController.currentBackStackEntry?.savedStateHandle
            val scannedUrl by (handle?.getStateFlow(SCANNED_URL_KEY, "")
                ?.collectAsState() ?: kotlinx.coroutines.flow.flowOf("").collectAsState(""))
            com.pixelpal.app.presentation.screens.agents.ConnectedAgentsScreen(
                navController = navController,
                onBack = { navController.popBackStack() },
                onScanQr = { navController.navigate(Screen.QrScan.route) },
                scannedUri = scannedUrl.takeIf { it.isNotBlank() },
                onScannedConsumed = { handle?.remove<String>(SCANNED_URL_KEY) }
            )

        }
        composable(Screen.QrScan.route) {
            com.pixelpal.app.presentation.screens.agent.QrScanScreen(navController = navController)
        }
        composable(Screen.Tasks.route) {
            TasksScreen(navController = navController)
        }
        composable(Screen.NewTask.route) {
            NewTaskScreen(navController = navController)
        }
        composable(
            Screen.TaskDetail.route,
            arguments = listOf(navArgument("taskId") { type = NavType.LongType })
        ) {
            TaskDetailScreen(navController = navController)
        }
    }
}

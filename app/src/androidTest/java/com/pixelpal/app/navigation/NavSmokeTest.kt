package com.pixelpal.app.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pixelpal.app.presentation.navigation.Screen
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side smoke test for the single-companion navigation graph.
 * No Hilt, no UI — validates the route contract NavGraph depends on.
 */
@RunWith(AndroidJUnit4::class)
class NavSmokeTest {

    @Test
    fun allRoutes_areDistinctAndNonBlank() {
        val routes = listOf(
            Screen.Onboarding.route,
            Screen.Auth.route,
            Screen.Home.route,
            Screen.Reminders.route,
            Screen.CreateReminder.route,
            Screen.Customize.route,
            Screen.Settings.route,
            Screen.Profile.route,
            Screen.OverlaySettings.route,
            Screen.About.route,
            Screen.Permissions.route,
            Screen.ConnectedAgents.route,
            Screen.Tasks.route,
            Screen.NewTask.route,
            Screen.TaskDetail.route,
            Screen.ActivityCenter.route,
            Screen.QrScan.route
        )
        assert(routes.all { it.isNotBlank() }) { "blank route in $routes" }
        // TaskDetail carries an arg placeholder; everything else must be unique as-is.
        val static = routes.filterNot { it.startsWith("task_detail/") || it == Screen.TaskDetail.route }
        assert(static.distinct().size == static.size) { "duplicate routes: $routes" }
    }

    @Test
    fun taskDetail_routeFormatsId() {
        assert(Screen.TaskDetail.route(42L) == "task_detail/42")
    }

    @Test
    fun agentHub_isTheOnlyRouteWithArgs_absent() {
        // The Agent tab replaced the per-companion workspace: one agent surface,
        // no companion id in the route (the single-companion invariant).
        assert(Screen.ConnectedAgents.route == "connected_agents")
        assert(!Screen.ConnectedAgents.route.contains("{")) {
            "the Agent hub must not take a companion id: ${Screen.ConnectedAgents.route}"
        }
    }
}

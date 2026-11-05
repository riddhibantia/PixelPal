package com.pixelpal.app.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.pixelpal.app.data.local.datastore.PreferencesManager
import com.pixelpal.app.data.remote.firebase.FirebaseAuthManager
import com.pixelpal.app.presentation.navigation.PixelPalNavGraph
import com.pixelpal.app.presentation.navigation.Screen
import com.pixelpal.app.presentation.theme.PixelPalTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var authManager: FirebaseAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE super.onCreate so the system uses
        // our SplashScreen theme (values-v31/themes.xml). keepOnScreenCondition
        // holds the splash until `start` is resolved and navigation is ready,
        // which eliminates the black flash on cold start.
        var startRoute: String? = null
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { startRoute == null }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val currentTheme by preferencesManager.currentTheme.collectAsStateWithLifecycle(initialValue = "dark")
            // Status-bar icons must follow the IN-APP theme, not the system
            // one: picking Light inside PixelPal while the system is dark
            // would otherwise leave white icons on a pale canvas.
            val themeIsDark = when (currentTheme.lowercase()) {
                "light" -> false
                "dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = androidx.activity.SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { themeIsDark },
                    navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { themeIsDark }
                )
            }
            PixelPalTheme(theme = currentTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Auth gate: onboarding on first launch, then every user
                    // must sign in (own profile) before reaching the app.
                    var start by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        val isFirstLaunch = preferencesManager.isFirstLaunch.first()
                        val destination = when {
                            isFirstLaunch -> Screen.Onboarding.route
                            authManager.currentUser == null -> Screen.Auth.route
                            else -> Screen.Home.route
                        }
                        // Release the SplashScreen keepOnScreenCondition now that
                        // the destination is known. The splash exits with its built-in
                        // scale+fade exit animation, then the NavHost enters.
                        startRoute = destination
                        start = destination
                        // Heal the overlay after process death / reinstall /
                        // battery-manager kills: enabled means running, and a
                        // foreground-activity start is always allowed. Without
                        // this the bubble silently never comes back until the
                        // user finds the toggle, a reboot, or onboarding.
                        // (No-op when disabled, and start() on an already
                        // running service is harmless.)
                        if (preferencesManager.overlayEnabled.first() &&
                            android.provider.Settings.canDrawOverlays(this@MainActivity)
                        ) {
                            com.pixelpal.app.overlay.OverlayService.start(this@MainActivity)
                        }
                    }

                    val navStart = start
                    if (navStart != null) {
                        val navController = rememberNavController()
                        PixelPalNavGraph(
                            navController = navController,
                            startDestination = navStart
                        )
                        // Reactive auth gate: a sign-out from ANY screen
                        // (Profile, settings, session expiry) returns to Auth
                        // instead of leaving a signed-out Home on the stack.
                        // Only fires on an Authenticated → Unauthenticated
                        // transition, never on cold start (which would skip
                        // onboarding or bounce a restoring session).
                        val authState by authManager.authStateFlow
                            .collectAsStateWithLifecycle(initialValue = null)
                        var seenAuthenticated by remember { mutableStateOf(false) }
                        LaunchedEffect(authState) {
                            when (authState) {
                                is com.pixelpal.app.data.remote.firebase.AuthState.Authenticated ->
                                    seenAuthenticated = true
                                is com.pixelpal.app.data.remote.firebase.AuthState.Unauthenticated -> {
                                    if (seenAuthenticated) {
                                        seenAuthenticated = false
                                        navController.navigate(Screen.Auth.route) {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

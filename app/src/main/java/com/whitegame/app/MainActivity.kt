package com.whitegame.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.whitegame.app.ui.screens.Root
import androidx.compose.runtime.LaunchedEffect
import com.whitegame.app.ui.theme.Ink
import com.whitegame.app.ui.theme.ThemeMode
import com.whitegame.app.ui.theme.WhiteGameTheme
import com.whitegame.app.connection.TunnelController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tunnels: TunnelController

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeMode.load(this)
        askForNotificationsIfNeeded()
        setContent {
            // System bar icons must flip with the theme or they vanish against the background.
            val dark = ThemeMode.dark
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent)
                else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            WhiteGameTheme {
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    Root()
                }
            }
        }
    }

    override fun onDestroy() {
        // Leaving the app (not rotating) hands DNS back to Android; a tunnel stays up.
        if (isFinishing) tunnels.onAppClosed()
        super.onDestroy()
    }

    /**
     * The tunnel runs as a foreground service, and its notification is how the user sees state
     * and disconnects from outside the app. Without this the service still runs, but silently.
     */
    private fun askForNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

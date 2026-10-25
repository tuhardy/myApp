package com.focusassistant.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.focusassistant.app.ui.FocusApp

class MainActivity : ComponentActivity() {
    private var quickHideToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.DKGRAY),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.DKGRAY)
        )
        setContent { FocusApp((application as FocusApplication).repository, quickHideToken = quickHideToken) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_QUICK_HIDE, false) == true) quickHideToken++
    }

    companion object {
        const val EXTRA_QUICK_HIDE = "quick_hide"
    }
}

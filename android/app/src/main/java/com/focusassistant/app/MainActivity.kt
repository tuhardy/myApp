package com.focusassistant.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.focusassistant.app.ui.FocusApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.DKGRAY),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.DKGRAY)
        )
        setContent { FocusApp((application as FocusApplication).repository) }
    }
}

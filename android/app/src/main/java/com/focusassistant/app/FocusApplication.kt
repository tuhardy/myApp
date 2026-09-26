package com.focusassistant.app

import android.app.Application
import com.focusassistant.app.data.FocusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FocusApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository: FocusRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FocusRepository(this, appScope)
    }
}

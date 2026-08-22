package com.xai.grokremote

import android.app.Application

class GrokRemoteApp : Application() {
    var resumedActivities: Int = 0
        private set

    val inForeground: Boolean
        get() = resumedActivities > 0

    fun onActivityResumed() {
        resumedActivities++
    }

    fun onActivityPaused() {
        resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
    }
}

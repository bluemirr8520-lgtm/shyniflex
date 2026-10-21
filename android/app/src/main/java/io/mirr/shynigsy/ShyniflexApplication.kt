package io.mirr.shynigsy

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.mirr.shynigsy.data.SpoilerAccessRegistry
import java.util.Collections
import java.util.IdentityHashMap

/** Activity handoffs retain access; leaving the application clears every process-only grant. */
class ShyniflexApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val started = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val lockInBackground = Runnable { if (started.isEmpty()) SpoilerAccessRegistry.clearAll() }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        started.add(activity)
        handler.removeCallbacks(lockInBackground)
    }

    override fun onActivityStopped(activity: Activity) {
        started.remove(activity)
        if (started.isEmpty()) {
            handler.removeCallbacks(lockInBackground)
            handler.postDelayed(lockInBackground, 300)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) { started.remove(activity) }
}

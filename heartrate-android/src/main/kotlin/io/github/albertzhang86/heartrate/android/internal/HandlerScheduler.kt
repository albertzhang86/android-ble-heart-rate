package io.github.albertzhang86.heartrate.android.internal

import android.os.Handler
import android.os.Looper

internal class HandlerScheduler : Scheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun execute(action: () -> Unit) { handler.post(action) }
    override fun after(delayMillis: Long, action: () -> Unit): Resource {
        val callback = Runnable(action)
        handler.postDelayed(callback, delayMillis)
        return Resource { handler.removeCallbacks(callback) }
    }
}

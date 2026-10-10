package io.github.albertzhang86.heartrate.android.internal

import io.github.albertzhang86.heartrate.connector.HeartRateConnector

/** Resource work is serialized on the scheduler; close suppresses observer delivery immediately. */
internal abstract class ManagedSession(
    protected val scheduler: Scheduler,
    private val removed: (ManagedSession) -> Unit,
) : HeartRateConnector.Session {
    private val gate = Any()
    @Volatile protected var closed = false
        private set
    val isClosed: Boolean get() = closed

    protected fun deliver(action: () -> Unit) = synchronized(gate) {
        if (!closed) action()
    }

    protected fun dispatch(action: () -> Unit) {
        scheduler.execute { if (!closed) action() }
    }

    /** Terminal callbacks may reenter close safely. */
    protected fun finish(notify: () -> Unit = {}) {
        try { deliver(notify) } finally { close() }
    }

    final override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
        }
        scheduler.execute {
            cleanup()
            removed(this)
        }
    }

    abstract fun start()
    protected abstract fun cleanup()
}

package io.github.albertzhang86.heartrate.android.internal

import io.github.albertzhang86.heartrate.HeartRateDevice
import io.github.albertzhang86.heartrate.android.BleHeartRateOptions
import io.github.albertzhang86.heartrate.connector.HeartRateConnector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

internal class BleConnector(
    private val platform: BlePlatform,
    private val scheduler: Scheduler,
    private val options: BleHeartRateOptions,
) : HeartRateConnector {
    private val closed = AtomicBoolean()
    private val sessions = ConcurrentHashMap.newKeySet<ManagedSession>()
    private var connection: ManagedSession? = null

    override fun getId() = "io.github.albertzhang86.standard-ble"

    override fun scan(observer: HeartRateConnector.ScanObserver): HeartRateConnector.Session {
        check(!closed.get()) { "Connector is closed." }
        val session = BleScanSession(platform, scheduler, options, observer, sessions::remove)
        sessions.add(session)
        if (closed.get()) session.close()
        scheduler.execute {
            if (closed.get() || session.isClosed) session.close()
            else session.start()
        }
        return session
    }

    override fun connect(device: HeartRateDevice, observer: HeartRateConnector.ConnectionObserver): HeartRateConnector.Session {
        check(!closed.get()) { "Connector is closed." }
        val session = BleConnectionSession(platform, scheduler, options, device, observer) { finished ->
            sessions.remove(finished)
            if (connection === finished) connection = null
        }
        sessions.add(session)
        if (closed.get()) session.close()
        scheduler.execute {
            if (closed.get() || session.isClosed) session.close()
            else {
                // Discovery ends when selecting a sensor. Only one connection is owned at a time.
                sessions.filterIsInstance<BleScanSession>().forEach { it.complete() }
                connection?.close()
                connection = session
                // close() queues native cleanup. Open the next handle only after that work runs.
                scheduler.execute {
                    if (!closed.get() && connection === session) session.start()
                }
            }
        }
        return session
    }

    override fun close() {
        if (closed.getAndSet(true)) return
        sessions.toList().forEach { it.close() }
        scheduler.execute {
            connection = null
        }
    }
}

package io.github.albertzhang86.heartrate.connector;

import io.github.albertzhang86.heartrate.HeartRateDevice;
import io.github.albertzhang86.heartrate.HeartRateMeasurement;

/**
 * Extension point for sensor vendors and transports. No Android, UI, or coroutine dependency
 * appears in this interface. Device IDs are opaque and scoped to this connector.
 *
 * All methods return promptly. Callbacks may be synchronous or arrive on any thread, but
 * events for one session must be ordered. Report connection before measurements. Transport
 * implementations own operation timeouts and resource cleanup.
 */
public interface HeartRateConnector extends AutoCloseable {
    /** Stable namespaced identifier, for example "org.example.standard-ble". */
    String getId();

    default boolean isSimulated() { return false; }

    /** Starts one discovery session. Closing the returned handle stops its callbacks/resources. */
    Session scan(ScanObserver observer);

    /** Starts one connection. Failures go to onError; disconnection clears the live sample. */
    Session connect(HeartRateDevice device, ConnectionObserver observer);

    /** Terminal, idempotent cleanup of all sessions. Must not throw. */
    @Override void close();

    interface Session extends AutoCloseable {
        /** Idempotent, non-blocking cleanup; no new callbacks after this returns. */
        @Override void close();
    }

    interface ScanObserver {
        void onDeviceDiscovered(HeartRateDevice device);
        void onError(ConnectorException error);
        /** A bounded scan ended successfully. No further results arrive for that session. */
        default void onScanCompleted() {}
    }

    interface ConnectionObserver {
        void onConnected();
        void onMeasurement(HeartRateMeasurement measurement);
        void onDisconnected();
        void onError(ConnectorException error);
        /** The session remains open while the connector retries; previous readings are no longer live. */
        default void onReconnecting(int attempt) {}
        /** No recent measurement is available, even though the connection may still be open. */
        default void onMeasurementUnavailable() {}
    }
}

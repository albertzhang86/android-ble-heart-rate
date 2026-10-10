# Implementing a heart-rate connector

## Boundary

Implement `io.github.albertzhang86.heartrate.connector.HeartRateConnector` from
`heartrate-core`. It is a Java interface. A connector does not need Android, Kotlin
coroutines, a screen, or access to the gym app.

The public contract provides:

- A stable namespaced connector ID.
- Discovery callbacks with opaque device IDs and optional names.
- Connection readiness, measurements, optional freshness/reconnecting events, terminal disconnect, and typed errors.
- Closeable scan/connection handles and terminal connector cleanup.
- Explicit simulation provenance (real by default).

The Kotlin `ConnectorHeartRateMonitor` wraps any implementation in the same `StateFlow`
API and discards callbacks from replaced connections. It timestamps received samples,
clears readings on disconnect/error/reconnect/staleness, and closes scan sessions when collection stops or discovery completes.
It owns its connector. Serialize its connect/disconnect/close calls on the owner's
thread; connector callbacks may arrive from platform threads.

Device IDs are connector-local, not necessarily Bluetooth MAC addresses. Measurements
contain BPM and optional contact/energy/RR data. Do not fabricate unavailable fields.
Convert units in the connector; the app should not need vendor-specific parsing.

## Implementation path

1. Use the [plain-Java fake](../heartrate-testing/src/main/java/io/github/albertzhang86/heartrate/testing/FakeHeartRateConnector.java)
   as an executable interface example, not as a Bluetooth implementation.
2. Implement discovery and a cancellable connection in your own module or repository.
   Transport timeouts and permissions belong in that connector/platform integration.
3. Return promptly from scan/connect. Report success only when the sensor is ready.
   Deliver callbacks in order: connected, measurements, then disconnected or error.
   For recoverable interruptions, send `onReconnecting(attempt)` (one-based), then
   `onConnected()` before resuming measurements. Send `onMeasurementUnavailable()`
   when a previous reading is stale. Both events immediately clear the facade's sample.
4. Make each returned handle's close operation idempotent, non-blocking, and release
   its underlying callbacks/resources. Connector close cleans up all handles.
5. Use `ConnectorException.Code` for errors. Do not log device identifiers or measurements.
6. Inject the connector explicitly into `ConnectorHeartRateMonitor`; there is no global
   registry, reflection, hardcoded Garmin selection, or app-specific dependency.

A monitor using the standard BLE Heart Rate Service should share the standard BLE
`StandardBleHeartRateConnector` implementation. A new brand alone does not require a connector fork.
Alternative protocols can implement the same interface in a separate module with
their own permissions and dependencies.

## Standard BLE implementation

The Android module separates platform calls from connection policy:

- `StandardBleHeartRateConnector` is the public Java-compatible entry point.
- `internal/AndroidBlePlatform` owns Android scanner/GATT objects, permission race
  handling, service validation and both legacy and Android 13+ notification callbacks.
- `internal/BleScanSession` owns scan lifetime, deduplication and bounded discovery.
- `internal/BleConnectionSession` owns the connection handshake, freshness and retries.
- `internal/ManagedSession` gates late callbacks and cleanup; `HandlerScheduler`
  serializes Android work on the main looper.

The `BlePlatform` and `Scheduler` boundaries are internal test seams, not public vendor
extension APIs. Use `HeartRateConnector` for another protocol. Do not subclass or
fork the standard BLE internals merely to add a sensor brand.

Session `close()` must stop new observer deliveries immediately, including already
queued events. Native resource disposal may be queued. Keep callbacks short and do
not wait for other threads from a callback. Terminal errors must release resources;
manual close does not need to send a disconnect callback. A finite scan ends with
`onScanCompleted()`, which completes the facade's Flow normally. Cancelling collection
stops scanning without a completion callback.

### Migration from the initial scaffold

The three new observer methods (`onScanCompleted`, `onReconnecting`, and
`onMeasurementUnavailable`) have Java default implementations, so existing connector
implementations still compile. Consumers with exhaustive Kotlin `when` expressions
over `HeartRateConnection` must handle the new `Reconnecting` case. Reconnect attempts
belong to the same session; do not discard its handle until a terminal event or close.

## Shared contract tests

The testing module publishes Gradle test fixtures with lifecycle contract checks:

```kotlin
dependencies {
    testImplementation(testFixtures("io.github.albertzhang86.heartrate:heartrate-testing:0.1.0-SNAPSHOT"))
}
```

Extend `HeartRateConnectorContractTest` and return a deterministic fixture containing
your connector, a discovered device, and a way to emit a synthetic measurement through
your transport. The fixture must drain measurement callbacks before emit returns.
See `FakeHeartRateConnectorContractTest` for a complete example. These tests check
connection-before-measurement ordering, no delivery after closing, and idempotent cleanup.

Also test timeouts, cancellation, unexpected disconnection, malformed packets,
permission revocation, and queued callbacks from old connections as applicable.
Use a fake transport to run CI without owning every supported sensor.

The standard BLE tests use a deterministic event loop and an injected fake at the
Android SDK boundary. They cover descriptor acknowledgement ordering, early packets,
deadlines, retry exhaustion/reset, stale readings, permission/radio failures, replacing
connections, and cancellation with queued events. They do not exercise the real Android
Bluetooth stack; use the [hardware checklist](HARDWARE_TESTING.md) for qualification.

## Hardware reports

Add a row to [COMPATIBILITY.md](COMPATIBILITY.md) with exact model/firmware, host OS,
tested commit, and observed results. A passing protocol or fake test does not mean
a physical product has been verified. Keep heart-rate recordings and hardware IDs
out of pull requests.

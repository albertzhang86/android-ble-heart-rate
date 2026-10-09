# Implementing a heart-rate connector

## Boundary

Implement `io.github.albertzhang86.heartrate.connector.HeartRateConnector` from
`heartrate-core`. It is a Java interface. A connector does not need Android, Kotlin
coroutines, a screen, or access to the gym app.

The public contract provides:

- A stable namespaced connector ID.
- Discovery callbacks with opaque device IDs and optional names.
- A connection callback, measurements, disconnect notification, and typed errors.
- Closeable scan/connection handles and terminal connector cleanup.
- Explicit simulation provenance (real by default).

The Kotlin `ConnectorHeartRateMonitor` wraps any implementation in the same `StateFlow`
API and discards callbacks from replaced connections. It timestamps received samples,
clears readings on disconnect/error, and closes scan sessions when collection stops.
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
4. Make each returned handle's close operation idempotent, non-blocking, and release
   its underlying callbacks/resources. Connector close cleans up all handles.
5. Use `ConnectorException.Code` for errors. Do not log device identifiers or measurements.
6. Inject the connector explicitly into `ConnectorHeartRateMonitor`; there is no global
   registry, reflection, hardcoded Garmin selection, or app-specific dependency.

A monitor using the standard BLE Heart Rate Service should share the standard BLE
implementation once available. A new brand alone does not require a connector fork.
Alternative protocols can implement the same interface in a separate module with
their own permissions and dependencies.

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

## Hardware reports

Add a row to [COMPATIBILITY.md](COMPATIBILITY.md) with exact model/firmware, host OS,
tested commit, and observed results. A passing protocol or fake test does not mean
a physical product has been verified. Keep heart-rate recordings and hardware IDs
out of pull requests.

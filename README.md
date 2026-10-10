# Android BLE Heart Rate

A Java-extensible Kotlin library for Android apps that consume Bluetooth Low Energy heart-rate sensors.
No screens, Compose, account system, workout logic, networking, or data storage.

**Status: standard BLE connector implemented; physical-device validation pending.**
Supports foreground discovery, GATT notification subscription, measurement parsing,
bounded reconnect, stale-sample detection, and cancellable cleanup. Transport behavior
is covered by deterministic tests at the Android SDK boundary. No physical Garmin model
or virtual BLE peripheral has been verified yet. ANT+ is outside this project's scope.

## Modules

| Module | Responsibility |
| --- | --- |
| `heartrate-core` | Plain-Java connector interface, Kotlin Flow facade, models, standard Heart Rate Measurement parser. Pure Kotlin/JVM. |
| `heartrate-android` | Standard BLE connector, Android capability/permission checks, scanner and GATT transport. |
| `heartrate-testing` | Plain-Java fake connector, simulated monitor, reusable connector contract test fixtures. Keep out of release builds. |

Dependencies flow from Android/testing to core. The consuming app owns views, lifecycle,
permission prompts, device selection, persistence, and any upload of readings. No personal
readings, device addresses, credentials, or consumer-app source are bundled in this repo.

## Build

Use JDK 17 or newer and Android SDK 35. Set `ANDROID_HOME` or add an ignored
`local.properties` with your `sdk.dir`. Open this directory directly in Android Studio.

For a cloud-synced checkout, optionally set `heartRate.buildRoot=/tmp/heart-rate-build`
in that same ignored file. This keeps generated classes outside the synced source folder.

```sh
./gradlew :heartrate-core:test :heartrate-testing:test \
  :heartrate-android:testDebugUnitTest :heartrate-android:assembleRelease :heartrate-android:lintDebug
```

## Import into an Android app

No artifacts are published to Maven Central yet. Use a pinned Git submodule and a
Gradle composite build; this keeps the library in its own repository while allowing
navigation, debugging, and edits from the consuming project.

```sh
git submodule add https://github.com/albertzhang86/android-ble-heart-rate.git libraries/android-ble-heart-rate
# After a fresh clone of the consuming app:
git submodule update --init --recursive
```

In `settings.gradle.kts`:

```kotlin
includeBuild("libraries/android-ble-heart-rate")
```

In the app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.albertzhang86.heartrate:heartrate-android:0.1.0-SNAPSHOT")
    debugImplementation("io.github.albertzhang86.heartrate:heartrate-testing:0.1.0-SNAPSHOT")
}
```

Gradle substitutes the included projects for those coordinates. The Git commit pinned by
the app determines the source version. For local artifact experiments, `./gradlew publishToMavenLocal`
publishes JARs/AARs and sources; it does not publish anything publicly.

## Connect a BLE sensor

Use the same connector for sensors that implement the standard Heart Rate Service.
The app obtains runtime permissions and handles device selection. `readiness()`
reports what the app needs to resolve before scanning; it never opens a prompt.

```kotlin
import io.github.albertzhang86.heartrate.ConnectorHeartRateMonitor
import io.github.albertzhang86.heartrate.android.AndroidBleEnvironment
import io.github.albertzhang86.heartrate.android.BleReadiness
import io.github.albertzhang86.heartrate.android.StandardBleHeartRateConnector
import io.github.albertzhang86.heartrate.connector.ConnectorException
import kotlinx.coroutines.launch

// Keep one monitor per owning feature, not one per render or scan.
val monitor = ConnectorHeartRateMonitor(StandardBleHeartRateConnector(context))

if (AndroidBleEnvironment(context).readiness() == BleReadiness.Ready) {
    val scanJob = scope.launch {
        try {
            monitor.scan().collect { devices ->
                // Offer this current list to the app's device picker.
            }
        } catch (error: ConnectorException) {
            // Map error.code to the app's retry / permission / Bluetooth action.
        }
    }
    // scanJob.cancel() stops discovery early; otherwise it completes after 10 seconds.
}

// On selection, pass a HeartRateDevice from the latest discovery results:
// monitor.connect(selectedDevice)
// Connection stops active scans. Observe state; connect() does not imply success.
scope.launch {
    monitor.state.collect { state ->
        val bpm = state.latestSample?.measurement?.beatsPerMinute
        // A null sample means there is no current reading. Do not keep displaying the old BPM.
        // state.connection distinguishes Connecting, Connected, Reconnecting and Failed.
    }
}

// On an explicit disconnect: monitor.disconnect()
// When the owner is disposed: cancel its observation jobs and call monitor.close().
```

Java consumers can use `new StandardBleHeartRateConnector(context)` directly with
the `HeartRateConnector.ScanObserver` and `ConnectionObserver` callbacks. Callbacks
run on Android's main looper; return promptly and do expensive work elsewhere.
Scan and connection handles can be closed from any thread. Closing immediately
suppresses new observer deliveries and queues native cleanup on that looper.

The connector owns one sensor connection. It declares the sensor connected only after
service discovery and successful acknowledgement of the 0x2902 subscription to
0x2A37 notifications. Early notifications during that subscription are buffered until
acknowledgement. Unsupported services and malformed packets fail explicitly.

`BleHeartRateOptions` makes these policies configurable:

| Policy | Default |
| --- | --- |
| Discovery | 10 seconds; filter advertised Heart Rate Service UUID; deduplicate device IDs |
| Connect + discovery + subscription deadline | 15 seconds per attempt |
| Stale reading | Clear the current sample after 10 seconds without a valid notification |
| Silent sensor | Reconnect after 30 seconds without a valid notification |
| Retry | 3 retries after the initial attempt, delayed 1, 2, then 4 seconds |
| Backoff cap | 8 seconds when configured for more retries |

Each valid measurement resets the retry budget. Retry only applies to connection failures
and timeouts. Missing permissions, disabled Bluetooth, unsupported sensors, and invalid
packets are terminal errors; the app resolves the cause and starts a new connection.
Availability is checked before operations and once per second during active sessions.

Set `filterByHeartRateService = false` only for sensors that omit their service UUID
from advertisements. This returns other connectable BLE devices too; the connector
still validates the Heart Rate Service on connection. Device IDs are connector-local
addresses for this implementation and must not be logged or treated as permanent identities.

## Simulated monitor

```kotlin
val monitor = SimulatedHeartRateMonitor(applicationScope)
monitor.connect(SimulatedHeartRateMonitor.DEVICE)

applicationScope.launch {
    monitor.state.collect { state ->
        val bpm = state.latestSample?.measurement?.beatsPerMinute
        // Map to your app's own model. This is explicitly simulated data.
    }
}

// When the owning feature is disposed:
monitor.close()
```

The simulated monitor uses the same connector facade as the BLE connector. The caller supplies the coroutine scope. The simulator creates a child job, emits
112–129 BPM by default, clears its sample on disconnect/close, and never cancels the
parent scope. Tests can supply a virtual-time dispatcher, sample list, interval, and clock.
It is an in-process fake, not a virtual BLE peripheral.

## Extend with another monitor

Implement the plain-Java `HeartRateConnector` interface and supply it to
`ConnectorHeartRateMonitor`. The interface uses callbacks and generic device/measurement
models, with no UI, Android, coroutine, Garmin, or BLE-address requirement. Java and Kotlin
implementations are supported. Consumers can also use the callback interface directly.

```kotlin
val monitor: HeartRateMonitor = ConnectorHeartRateMonitor(yourConnector)
monitor.connect(selectedDevice) // Observe state for confirmation or failure.
```

See [the connector guide](docs/CONNECTORS.md), the executable
[Java fake](heartrate-testing/src/main/java/io/github/albertzhang86/heartrate/testing/FakeHeartRateConnector.java),
and [compatibility matrix](docs/COMPATIBILITY.md). Use the standard BLE connector when a
sensor speaks the standard Heart Rate Service; add a vendor-specific connector only for
differences in protocol or transport.

## Android boundary

`AndroidBleEnvironment(context).readiness()` reports unsupported BLE, missing permissions,
Bluetooth disabled, legacy location services disabled, or readiness for foreground scanning.
The app requests permissions from `HeartRatePermissions.requiredForScan()`. This library
does not grant permissions or enable radios. The manifest declares BLE as optional.

The permission policy is foreground scanning: Android 12+ uses Nearby devices; Android
8–11 uses foreground location. No background-location permission is declared. The library
does not derive location from scan results. `readiness(forScan = false)` and
`requiredForConnection()` check the narrower connection requirements. Location services
are required for legacy scanning, not for keeping a selected sensor connected.

The consuming app owns background execution. This library starts no foreground service
and makes no guarantee that Android will keep its process alive. Apps that continue
training over another TV app must provide an appropriate lifecycle/service integration.

## Next milestones

1. Virtual BLE tests with Bumble/Netsim, then compatibility checks on actual Android TV + Garmin hardware.
2. Versioned releases and Maven Central distribution.

See [CONTRIBUTING.md](CONTRIBUTING.md) for boundaries and checks.

## References

- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Bluetooth Heart Rate Service](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/HRS_v1.0/out/en/index-en.html)
- [Gradle composite builds](https://docs.gradle.org/current/userguide/composite_builds.html)
- [Bumble Android testing](https://google.github.io/bumble/platforms/android.html)

Licensed under MIT. The project is independent of Garmin and Bluetooth SIG.

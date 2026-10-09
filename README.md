# Android BLE Heart Rate

A Java-extensible Kotlin library for Android apps that consume Bluetooth Low Energy heart-rate sensors.
No screens, Compose, account system, workout logic, networking, or data storage.

**Status: initial scaffold. Physical scanning, GATT connection, notification subscription,
automatic reconnect, and stale-sample detection are not implemented yet.**
The simulator is functional; it does not exercise Bluetooth. Garmin model compatibility
has not been verified. ANT+ is outside this project's scope.

## Modules

| Module | Responsibility |
| --- | --- |
| `heartrate-core` | Plain-Java connector interface, Kotlin Flow facade, models, standard Heart Rate Measurement parser. Pure Kotlin/JVM. |
| `heartrate-android` | Android BLE capability/permission preflight and standard GATT identifiers; home for the future transport. |
| `heartrate-testing` | Plain-Java fake connector, simulated monitor, reusable connector contract test fixtures. Keep out of release builds. |

Dependencies flow from Android/testing to core. The consuming app owns views, lifecycle,
permission prompts, device selection, persistence, and any upload of readings. No personal
readings, device addresses, credentials, or consumer-app source are bundled in this repo.

## Build

Use JDK 17 or newer and Android SDK 35. Set `ANDROID_HOME` or add an ignored
`local.properties` with your `sdk.dir`. Open this directory directly in Android Studio.

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

The simulated monitor uses the same connector facade as future physical connectors. The caller supplies the coroutine scope. The simulator creates a child job, emits
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
differences in protocol or transport. The standard BLE transport is the next milestone.

## Android boundary

`AndroidBleEnvironment(context).readiness()` reports unsupported BLE, missing permissions,
Bluetooth disabled, legacy location services disabled, or readiness for foreground scanning.
The app requests permissions from `HeartRatePermissions.requiredForScan()`. This library
does not grant permissions or enable radios. The manifest declares BLE as optional.

The permission policy is foreground scanning: Android 12+ uses Nearby devices; Android
8–11 uses foreground location. No background-location permission is declared. The library
does not derive location from scan results. A future background connection policy must
also account for the consuming app's lifecycle and Android service requirements.

## Next milestones

1. Foreground scanner with service filtering, cancellation, timeout, and device deduplication.
2. Single-sensor GATT transport: discover service 0x180D, subscribe to 0x2A37 through CCCD,
   feed notifications through the tested parser, and cleanly release resources.
3. Connection failure model, bounded reconnect, freshness, and adapter-off/permission-revocation handling.
4. Virtual BLE tests with Bumble/Netsim, then compatibility checks on actual Android TV + Garmin hardware.
5. Versioned releases and Maven Central distribution.

See [CONTRIBUTING.md](CONTRIBUTING.md) for boundaries and checks.

## References

- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Bluetooth Heart Rate Service](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/HRS_v1.0/out/en/index-en.html)
- [Gradle composite builds](https://docs.gradle.org/current/userguide/composite_builds.html)
- [Bumble Android testing](https://google.github.io/bumble/platforms/android.html)

Licensed under MIT. The project is independent of Garmin and Bluetooth SIG.

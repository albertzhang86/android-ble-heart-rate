# Qualifying a heart-rate sensor

This library has no device-picker UI. Run these steps from a consuming app that requests
permissions and injects `StandardBleHeartRateConnector` into `ConnectorHeartRateMonitor`.
The gym app currently injects its simulator; its dashboard alone does not test Bluetooth.

Record the exact strap model/firmware, Android host model/version, and library commit.
Use the sensor's documented BLE pairing/broadcast procedure. ANT+-only devices cannot
be qualified with this connector. Never include device addresses or personal readings
in public reports.

1. Deny the app's Bluetooth permissions. Scanning should report `PERMISSION_DENIED`
   without a crash; grant permissions and retry. On Android 8–11 also check the
   foreground-location permission and location-services setting.
2. Scan with the default service filter. Confirm the sensor appears once, named when
   available, and scanning completes after 10 seconds. Cancel a second scan early.
   If the sensor omits its advertised service UUID, repeat with filtering disabled
   and document that limitation.
3. Select the sensor. Expect `Connecting` followed by `Connected` only after the
   notification subscription succeeds. Check BPM and optional fields against a
   trusted display without publishing personal recordings.
4. Observe a sustained session of at least 10 minutes. Valid readings should refresh
   the sample timestamp; there should be no false disconnects or reconnect loops.
5. Stop the sensor or move it out of range. The old sample should clear on disconnect,
   or after 10 seconds of silence if the radio still reports connected. Restore the
   sensor during retries and confirm new data resumes. If the retry budget expires,
   expect a terminal failure and allow an explicit reconnect.
6. Switch Bluetooth off and revoke permission during separate active connections.
   Expect a typed failure, cleared sample, and no automatic permission prompts or
   radio toggles. Restore availability and connect again.
7. Disconnect during connection, active readings, and reconnect backoff. Late
   notifications must not repopulate state and no background reconnect should occur.
8. Repeat connect/disconnect several times. Confirm scans and connections continue
   to work and another Bluetooth client can connect after this app releases the sensor.
9. Test any intended background/TV-overlay use separately. The consumer must supply
   its Android service/lifecycle integration; the library does not keep a process alive.

Add the outcomes and limitations to `COMPATIBILITY.md`. Emulator tests using Bumble/Netsim
can validate Android stack integration, but must be recorded separately from physical
sensor tests. Neither is currently claimed as completed by this repository.

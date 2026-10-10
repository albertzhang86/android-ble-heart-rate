# Compatibility

No physical heart-rate monitor has been verified in this repository yet.

| Connector / sensor | Status | Evidence |
| --- | --- | --- |
| Plain-Java fake connector | Implemented | Shared lifecycle contract tests and Flow facade tests |
| In-process simulated monitor | Implemented | Virtual-time unit tests; no Bluetooth traffic |
| Standard BLE Heart Rate Service | Implemented; hardware unverified | Parser, permission-policy, and transport state-machine tests using a fake Android SDK boundary; AAR build and Android lint |
| Garmin strap | Planned first physical test | Exact model, firmware and host device still needed |
| Other vendors / transports | Open to contributions | Implement the Java connector interface or validate standard BLE support |

For each physical report, record model, firmware, Android host/version, commit,
discovery, connection, sustained notifications, reconnect, and any limitations.
Do not claim all models from a vendor work based on testing one model.

Follow [HARDWARE_TESTING.md](HARDWARE_TESTING.md) when qualifying a sensor. No result
from the in-process simulator or fake transport establishes BLE radio interoperability.

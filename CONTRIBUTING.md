# Contributing

Use Kotlin conventions, explicit names and small classes with a single responsibility.

- Keep Android dependencies in `heartrate-android`, never in `heartrate-core`.
- Keep all user interface and application-specific behavior in the consuming app.
- Keep simulated data in `heartrate-testing`; never substitute it after a real-device failure.
- Avoid logging device identifiers, raw readings, or credentials.
- Specify ownership and cancellation for every coroutine, scan, and connection.
- Clear stale/disconnected samples instead of continuing to present them as live.
- Add behavioral tests for protocol parsing, state transitions and lifecycle changes.
- Add no telemetry, network clients, or persistence without an explicit API decision.

Run the verification command in README before submitting a change. A successful simulator
test does not establish physical-device compatibility. Document which hardware was tested.

This initial API is pre-1.0 and can change. Record consumer migration notes for API changes.

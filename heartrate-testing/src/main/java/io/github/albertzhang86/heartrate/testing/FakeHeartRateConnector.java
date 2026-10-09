package io.github.albertzhang86.heartrate.testing;

import io.github.albertzhang86.heartrate.HeartRateDevice;
import io.github.albertzhang86.heartrate.HeartRateMeasurement;
import io.github.albertzhang86.heartrate.connector.ConnectorException;
import io.github.albertzhang86.heartrate.connector.HeartRateConnector;
import java.util.HashSet;
import java.util.Set;

/** Executable plain-Java connector example. Call emit() to supply a synthetic measurement. */
public final class FakeHeartRateConnector implements HeartRateConnector {
    public static final HeartRateDevice DEVICE = new HeartRateDevice("fake-sensor", "Simulated heart-rate monitor");
    private final Set<Session> scans = new HashSet<>();
    private ConnectionObserver observer;
    private long generation;
    private boolean closed;

    @Override public String getId() { return "io.github.albertzhang86.fake"; }
    @Override public boolean isSimulated() { return true; }

    @Override public synchronized Session scan(ScanObserver listener) {
        ensureOpen();
        Session session = new Session() {
            @Override public void close() {
                synchronized (FakeHeartRateConnector.this) { scans.remove(this); }
            }
        };
        scans.add(session);
        listener.onDeviceDiscovered(DEVICE);
        return session;
    }

    @Override public synchronized Session connect(HeartRateDevice device, ConnectionObserver listener) {
        ensureOpen();
        if (!DEVICE.getId().equals(device.getId())) {
            throw new ConnectorException(ConnectorException.Code.UNSUPPORTED, "Unknown fake sensor.");
        }
        long token = ++generation;
        observer = listener;
        listener.onConnected();
        return () -> {
            synchronized (FakeHeartRateConnector.this) {
                if (token == generation) { generation++; observer = null; }
            }
        };
    }

    public synchronized void emit(HeartRateMeasurement measurement) {
        ensureOpen();
        if (observer != null) observer.onMeasurement(measurement);
    }

    public synchronized void fail(ConnectorException error) {
        ensureOpen();
        ConnectionObserver current = observer;
        observer = null;
        generation++;
        if (current != null) current.onError(error);
    }

    @Override public synchronized void close() {
        closed = true;
        generation++;
        observer = null;
        scans.clear();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Connector is closed.");
    }
}

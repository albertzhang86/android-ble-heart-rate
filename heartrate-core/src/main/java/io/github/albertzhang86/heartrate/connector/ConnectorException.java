package io.github.albertzhang86.heartrate.connector;

/** Portable failures; never put device addresses, credentials, or raw readings in the message. */
public final class ConnectorException extends RuntimeException {
    public enum Code { UNSUPPORTED, PERMISSION_DENIED, UNAVAILABLE, CONNECTION_FAILED, TIMEOUT, PROTOCOL_ERROR }
    private final Code code;

    public ConnectorException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public ConnectorException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() { return code; }
}

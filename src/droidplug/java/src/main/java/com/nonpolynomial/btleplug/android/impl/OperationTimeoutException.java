package com.nonpolynomial.btleplug.android.impl;

class OperationTimeoutException extends BluetoothException {
    private final long timeoutMs;

    OperationTimeoutException(long timeoutMs) {
        super("GATT operation got no callback within " + timeoutMs + " ms");
        this.timeoutMs = timeoutMs;
    }

    public long getTimeoutMs() {
        return this.timeoutMs;
    }
}

package com.nonpolynomial.btleplug.android.impl;

class CommandTimeoutException extends BluetoothException {
    private final String message;

    CommandTimeoutException(long timeoutMs) {
        this.message = "GATT command got no callback within " + timeoutMs + "ms";
    }

    @Override
    public String getMessage() {
        return this.message;
    }
}

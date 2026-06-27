package com.specdriven.regression.provider;

import java.io.IOException;

class GrpcClientException extends IOException {

    private final boolean timeout;

    GrpcClientException(String message, Throwable cause, boolean timeout) {
        super(message, cause);
        this.timeout = timeout;
    }

    boolean timeout() {
        return timeout;
    }
}

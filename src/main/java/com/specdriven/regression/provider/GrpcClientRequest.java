package com.specdriven.regression.provider;

import java.nio.file.Path;

record GrpcClientRequest(
        String endpoint,
        Path descriptorPath,
        String service,
        String method,
        String payloadJson,
        int timeoutSeconds,
        boolean plaintext,
        String authority) {
}

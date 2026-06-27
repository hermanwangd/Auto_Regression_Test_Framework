package com.specdriven.regression.provider;

import java.util.Map;

record MessagingTransportRequest(
        String providerName,
        String providerType,
        String connectionRef,
        String targetRef,
        String targetField,
        String actionName,
        String mode,
        String payloadBinding,
        String correlationId,
        String payload,
        int timeoutSeconds,
        Map<String, Object> contract,
        Map<?, ?> action) {
}

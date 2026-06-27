package com.specdriven.regression.provider;

import java.io.IOException;

interface MessagingTransport {

    default MessagingTransportResult execute(MessagingTransportRequest request)
            throws IOException, InterruptedException {
        String mode = request.mode() == null || request.mode().isBlank()
                ? "publish"
                : request.mode().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "publish" -> publish(request);
            case "consume", "observe" -> observe(request);
            case "cleanup" -> cleanup(request);
            default -> throw new IOException("Unsupported messaging action mode `" + request.mode() + "`.");
        };
    }

    MessagingTransportResult publish(MessagingTransportRequest request) throws IOException, InterruptedException;

    default MessagingTransportResult observe(MessagingTransportRequest request)
            throws IOException, InterruptedException {
        throw new IOException("Unsupported messaging observe mode for provider_type `"
                + request.providerType() + "`.");
    }

    default MessagingTransportResult cleanup(MessagingTransportRequest request)
            throws IOException, InterruptedException {
        throw new IOException("Unsupported messaging cleanup mode for provider_type `"
                + request.providerType() + "`.");
    }
}

package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagingTransportTest {

    @Test
    void defaultsBlankModeToPublish() throws Exception {
        RecordingTransport transport = new RecordingTransport();

        MessagingTransportResult result = transport.execute(request("   "));

        assertThat(result.stdout()).isEqualTo("publish stdout\n");
        assertThat(transport.invokedMode).isEqualTo("publish");
    }

    @Test
    void defaultsNullModeToPublish() throws Exception {
        RecordingTransport transport = new RecordingTransport();

        MessagingTransportResult result = transport.execute(request(null));

        assertThat(result.actualOutput()).isEqualTo("publish actual\n");
        assertThat(transport.invokedMode).isEqualTo("publish");
    }

    @Test
    void routesRequestAliasToRequestReply() throws Exception {
        RecordingTransport transport = new RecordingTransport();

        MessagingTransportResult result = transport.execute(request("request"));

        assertThat(result.messageCount()).isEqualTo(2);
        assertThat(transport.invokedMode).isEqualTo("request_reply");
    }

    @Test
    void routesRequestReplyModeWithDashToRequestReply() throws Exception {
        RecordingTransport transport = new RecordingTransport();

        MessagingTransportResult result = transport.execute(request("request-reply"));

        assertThat(result.stdout()).isEqualTo("request_reply stdout\n");
        assertThat(transport.invokedMode).isEqualTo("request_reply");
    }

    @Test
    void routesConsumeAliasToObserve() throws Exception {
        RecordingTransport transport = new RecordingTransport();

        MessagingTransportResult result = transport.execute(request("consume"));

        assertThat(result.actualOutput()).isEqualTo("observe actual\n");
        assertThat(transport.invokedMode).isEqualTo("observe");
    }

    @Test
    void routesObserveModeToObserve() throws Exception {
        RecordingTransport transport = new RecordingTransport();

        MessagingTransportResult result = transport.execute(request("OBSERVE"));

        assertThat(result.stdout()).isEqualTo("observe stdout\n");
        assertThat(transport.invokedMode).isEqualTo("observe");
    }

    @Test
    void routesCleanupModeToCleanup() throws Exception {
        RecordingTransport transport = new RecordingTransport();

        MessagingTransportResult result = transport.execute(request("cleanup"));

        assertThat(result.messageCount()).isEqualTo(4);
        assertThat(transport.invokedMode).isEqualTo("cleanup");
    }

    @Test
    void rejectsUnsupportedModeWithOriginalMode() {
        RecordingTransport transport = new RecordingTransport();

        assertThatThrownBy(() -> transport.execute(request("replay")))
                .isInstanceOf(IOException.class)
                .hasMessage("Unsupported messaging action mode `replay`.");
    }

    @Test
    void defaultRequestReplyReportsUnsupportedProviderType() {
        PublishOnlyTransport transport = new PublishOnlyTransport();

        assertThatThrownBy(() -> transport.requestReply(request("request_reply")))
                .isInstanceOf(IOException.class)
                .hasMessage("Unsupported messaging request/reply mode for provider_type `nats`.");
    }

    @Test
    void defaultObserveReportsUnsupportedProviderType() {
        PublishOnlyTransport transport = new PublishOnlyTransport();

        assertThatThrownBy(() -> transport.observe(request("observe")))
                .isInstanceOf(IOException.class)
                .hasMessage("Unsupported messaging observe mode for provider_type `nats`.");
    }

    @Test
    void defaultCleanupReportsUnsupportedProviderType() {
        PublishOnlyTransport transport = new PublishOnlyTransport();

        assertThatThrownBy(() -> transport.cleanup(request("cleanup")))
                .isInstanceOf(IOException.class)
                .hasMessage("Unsupported messaging cleanup mode for provider_type `nats`.");
    }

    private MessagingTransportRequest request(String mode) {
        return new MessagingTransportRequest(
                "orders_bus",
                "nats",
                "nats://localhost:4222",
                "orders.created",
                "subject",
                "publish_order",
                mode,
                "payload",
                "corr-1",
                "{\"id\":\"O-1\"}",
                2,
                Map.of("provider_type", "nats"),
                Map.of("mode", mode == null ? "" : mode));
    }

    private static class PublishOnlyTransport implements MessagingTransport {

        @Override
        public MessagingTransportResult publish(MessagingTransportRequest request) {
            return new MessagingTransportResult("publish stdout\n", "publish actual\n", 1);
        }
    }

    private static final class RecordingTransport extends PublishOnlyTransport {

        private String invokedMode;

        @Override
        public MessagingTransportResult publish(MessagingTransportRequest request) {
            invokedMode = "publish";
            return super.publish(request);
        }

        @Override
        public MessagingTransportResult requestReply(MessagingTransportRequest request) {
            invokedMode = "request_reply";
            return new MessagingTransportResult("request_reply stdout\n", "request_reply actual\n", 2);
        }

        @Override
        public MessagingTransportResult observe(MessagingTransportRequest request) {
            invokedMode = "observe";
            return new MessagingTransportResult("observe stdout\n", "observe actual\n", 3);
        }

        @Override
        public MessagingTransportResult cleanup(MessagingTransportRequest request) {
            invokedMode = "cleanup";
            return new MessagingTransportResult("cleanup stdout\n", "cleanup actual\n", 4);
        }
    }
}

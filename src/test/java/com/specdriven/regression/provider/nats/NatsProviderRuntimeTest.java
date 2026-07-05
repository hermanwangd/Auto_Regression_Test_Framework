package com.specdriven.regression.provider.nats;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NatsProviderRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void natsObserveReturnsObservedEventEvidenceForPublishedSubject() {
        NatsProviderRuntime runtime = new NatsProviderRuntime();
        ProviderExecutionContext context = context();

        ProviderOperationResult published = runtime.execute(context, new ProviderOperationRequest(
                "nats_publish",
                List.of(
                        Map.of("bind_as", "subject", "ref", "orders.ready"),
                        Map.of("bind_as", "payload", "ref", "{\"eventId\":\"EVT-1\"}")),
                Map.of("_operation_id", "publish_event")));
        ProviderOperationResult observed = runtime.execute(context, new ProviderOperationRequest(
                "nats_observe",
                List.of(
                        Map.of("bind_as", "subject", "ref", "orders.ready"),
                        Map.of("bind_as", "consume_from", "ref", "test_start_time")),
                Map.of("_operation_id", "observe_event", "_test_start_time", Instant.EPOCH.toString())));

        assertThat(published.passed()).isTrue();
        assertThat(observed.passed()).isTrue();
        assertThat(observed.outputs())
                .containsEntry("subject", "orders.ready")
                .containsEntry("matched", true)
                .containsEntry("observed_count", 1);
        assertThat(tempDir.resolve("provider-evidence/nats/observe_event.yaml")).isRegularFile();
    }

    @Test
    void natsObserveTimesOutWhenNoEventArrives() {
        NatsProviderRuntime runtime = new NatsProviderRuntime();
        ProviderExecutionContext context = context();

        ProviderOperationResult observed = runtime.execute(context, new ProviderOperationRequest(
                "nats_observe",
                List.of(
                        Map.of("bind_as", "subject", "ref", "orders.ready"),
                        Map.of("bind_as", "consume_from", "ref", "test_start_time"),
                        Map.of("bind_as", "timeout", "ref", "PT0.02S"),
                        Map.of("bind_as", "poll_interval", "ref", "PT0.01S")),
                Map.of("_operation_id", "timeout_observe", "_test_start_time", Instant.now().toString())));

        assertThat(observed.passed()).isFalse();
        assertThat(observed.failure()).isNotNull();
        assertThat(observed.failure().code()).isEqualTo("NATS_TIMEOUT");
        assertThat(observed.outputs())
                .containsEntry("subject", "orders.ready")
                .containsEntry("matched", false)
                .containsEntry("observed_count", 0);
    }

    @Test
    void natsObserveReturnsFailureForInvalidDurationInsteadOfThrowing() {
        NatsProviderRuntime runtime = new NatsProviderRuntime();
        ProviderExecutionContext context = context();

        ProviderOperationResult observed = runtime.execute(context, new ProviderOperationRequest(
                "nats_observe",
                List.of(
                        Map.of("bind_as", "subject", "ref", "orders.ready"),
                        Map.of("bind_as", "timeout", "ref", "not-a-duration")),
                Map.of("_operation_id", "invalid_duration_observe", "_test_start_time", Instant.now().toString())));

        assertThat(observed.passed()).isFalse();
        assertThat(observed.failure()).isNotNull();
        assertThat(observed.failure().code()).isEqualTo("INVALID_DURATION");
        assertThat(observed.outputs()).containsKey("event_evidence_ref");
    }

    @Test
    void natsPublishRejectsPayloadRefsOutsideSuiteRoot() {
        NatsProviderRuntime runtime = new NatsProviderRuntime();
        ProviderExecutionContext context = context();

        ProviderOperationResult published = runtime.execute(context, new ProviderOperationRequest(
                "nats_publish",
                List.of(
                        Map.of("bind_as", "subject", "ref", "orders.ready"),
                        Map.of("bind_as", "payload_ref", "ref", "../outside.json")),
                Map.of("_operation_id", "outside_payload_ref")));

        assertThat(published.passed()).isFalse();
        assertThat(published.failure()).isNotNull();
        assertThat(published.failure().code()).isEqualTo("REF_OUTSIDE_SUITE_ROOT");
        assertThat(published.outputs()).containsKey("event_evidence_ref");
    }

    @Test
    void consumeFromTestStartTimeDoesNotMatchPreviouslyPublishedEvents() {
        NatsProviderRuntime runtime = new NatsProviderRuntime();
        ProviderExecutionContext context = context();

        ProviderOperationResult published = runtime.execute(context, new ProviderOperationRequest(
                "nats_publish",
                List.of(
                        Map.of("bind_as", "subject", "ref", "orders.ready"),
                        Map.of("bind_as", "payload", "ref", "{\"eventId\":\"OLD-1\"}")),
                Map.of("_operation_id", "publish_old_event")));

        ProviderOperationResult observed = runtime.execute(context, new ProviderOperationRequest(
                "event_published",
                List.of(
                        Map.of("bind_as", "subject", "ref", "orders.ready"),
                        Map.of("bind_as", "consume_from", "ref", "test_start_time")),
                Map.of("_operation_id", "observe_after_start", "_test_start_time", Instant.now().plusSeconds(1).toString())));

        assertThat(published.passed()).isTrue();
        assertThat(observed.passed()).isFalse();
        assertThat(observed.failure()).isNotNull();
        assertThat(observed.failure().code()).isEqualTo("EVENT_NOT_FOUND");
        assertThat(observed.outputs())
                .containsEntry("subject", "orders.ready")
                .containsEntry("matched", false)
                .containsEntry("observed_count", 0);
    }

    private ProviderExecutionContext context() {
        return new ProviderExecutionContext(
                "local-nats-event-bus",
                "nats",
                "local_nats",
                "local",
                Path.of("samples/20-provider-capability-p0/messaging/nats"),
                tempDir,
                Map.of(
                        "provider_type", "nats",
                        "binding_keys", Map.of(
                                "connection", Map.of("required", true),
                                "subject", Map.of("required", true)),
                        "operations", Map.of(
                                "nats_publish", Map.of(
                                        "allowed_bind_as", List.of("subject", "payload", "payload_ref"),
                                        "required_parameters", List.of("subject")),
                                "event_published", Map.of(
                                        "allowed_bind_as", List.of("subject", "consume_from"),
                                        "required_parameters", List.of("subject")))),
                Map.of("provider_id", "local-nats-event-bus", "provider_type", "nats"),
                Map.of(
                        "connection", Map.of("local_ref", "approved_local_nats_ref"),
                        "subject", "orders.ready",
                        "timeout", "PT0.05S",
                        "poll_interval", "PT0.01S"));
    }
}

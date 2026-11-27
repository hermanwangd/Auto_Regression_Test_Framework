package com.specdriven.regression.contract.v03.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.JsonPointer;
import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ResolvedTarget;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.contract.v03.ref.V03ReferenceResolver;
import com.specdriven.regression.provider.websocket.WebSocketSubscription;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebSocketV03AdapterTest {
    private static final String ENV = "FRAMEWORK_WEBSOCKET_URI";
    private static final Map<String, Object> BINDING = Map.of("endpoint_ref", "env://" + ENV);
    private static final Map<String, Object> EXPECT = Map.of("event_pointer", "/kind", "expected_event", "changed",
            "correlation_pointer", "/meta/id", "timeout_ms", 200, "max_events", 3);
    private final WebSocketV03Adapter adapter = new WebSocketV03Adapter();
    @TempDir Path run;

    @Test void rejectsEndpointSubstitutionSecretsAndOutOfScopeUris() throws Exception {
        assertThat(adapter.supports("websocket.v0.3", "websocket_subscribe")).isTrue();
        assertThat(adapter.supports("websocket.v0.2", "websocket_subscribe")).isFalse();
        assertThat(adapter.supports("websocket.v0.3", "websocket_send")).isFalse();
        for (String uri : List.of("http://localhost/stream", "ws:///missing", "ws://user:private@host/stream",
                "wss://host/stream?access_token=private", "wss://host:0/stream", "ws://host:65536", "ws://host/#x")) {
            assertThat(WebSocketV03Adapter.endpoint(uri)).isNull();
        }
        assertThat(WebSocketV03Adapter.endpoint("wss://example.org/events")).isNotNull();
        var ctx = context(BINDING, Map.of(ENV, "ws://user:private-marker@localhost"));
        var failed = step(ctx, "websocket_subscribe", Map.of());
        assertThat(failed.failureCode()).isEqualTo("WEBSOCKET_CONNECTION_CONFIG");
        assertThat(Files.readString(run.resolve(failed.evidenceRefs().get(0)))).doesNotContain("private-marker", "localhost");
        assertThat(step(context(Map.of("endpoint_ref", "env://OTHER"), Map.of("OTHER", "ws://localhost")),
                "websocket_subscribe", Map.of()).failureCode()).isEqualTo("WEBSOCKET_CONNECTION_CONFIG");
    }

    @Test void admitsOnlyBoundedValidInputsAndClosesAnUnopenedTargetIdempotently() throws Exception {
        var ctx = context(BINDING, Map.of());
        for (var item : Map.of("max_events", List.of(0, 65, 2.2), "timeout_ms", List.of(-1, 10001, "100"),
                "correlation_pointer", List.of("field", "/~2", " "), "event_pointer", List.of("a".repeat(257))).entrySet()) {
            for (Object bad : item.getValue()) {
                var inputs = new HashMap<>(EXPECT);
                inputs.put(item.getKey(), bad);
                assertThat(step(ctx, "websocket_await_event", inputs).failureCode()).isEqualTo("WEBSOCKET_INVALID_INPUT");
            }
        }
        var subscribe = Map.<String, Object>of("subscription_ref", "artifact://fixtures/subscribe.json",
                "ready_pointer", "/ready", "ready_value", "ok");
        Path fixture = run.resolve("subscribe.json");
        for (String invalid : List.of(" ".repeat(8193), "null", "{\"a\":1,\"a\":2}", "{}{}")) {
            Files.writeString(fixture, invalid);
            assertThat(step(ctx, "websocket_subscribe", subscribe).failureCode()).isEqualTo("WEBSOCKET_INVALID_INPUT");
        }
        Files.write(fixture, new byte[]{(byte) 0xff});
        assertThat(step(ctx, "websocket_subscribe", subscribe).failureCode()).isEqualTo("WEBSOCKET_INVALID_INPUT");
        assertThat(step(ctx, "websocket_subscribe", Map.of("ready_pointer", "/x")).failureCode()).isEqualTo("WEBSOCKET_INVALID_INPUT");
        assertThat(step(ctx, "websocket_await_event", EXPECT).failureCode()).isEqualTo("WEBSOCKET_SESSION_MISSING");
        assertThat(step(ctx, "websocket_close", Map.of()).outputs()).containsEntry("was_open", false).containsEntry("closed", true);
        adapter.endRun(ctx);
    }

    @Test void reassemblesFragmentedJsonAndRequiresBothExactEventAndCorrelation() throws Exception {
        var session = new WebSocketSubscription();
        var socket = mock(WebSocket.class);
        String correct = "{\"kind\":\"changed\",\"meta\":{\"id\":\"" + session.correlationId() + "\"}}";
        session.onText(socket, correct.replace(session.correlationId(), "earlier-run"), true);
        session.onText(socket, correct.replace("changed", "viewed"), true);
        session.onText(socket, correct.substring(0, 6), false);
        session.onText(socket, correct.substring(6), true);
        assertThat(session.awaitEvent(JsonPointer.compile("/kind"), "changed", JsonPointer.compile("/meta/id"), 200, 3))
                .isEqualTo(3);
        assertThatThrownBy(() -> await(session, 1)).isInstanceOf(WebSocketSubscription.Failure.class)
                .hasMessageContaining("No matching");
    }

    @Test void rejectsUtf8ByteOverflowBinaryMalformedJsonAndPeerReasonsWithoutDisclosingPayloads() {
        var socket = mock(WebSocket.class);
        var huge = new WebSocketSubscription();
        huge.onText(socket, "資".repeat(2800), true);
        assertThatThrownBy(() -> await(huge, 1)).hasMessageContaining("limit exceeded");
        verify(socket).abort();
        var binary = new WebSocketSubscription();
        binary.onBinary(mock(WebSocket.class), ByteBuffer.allocate(1), true);
        assertThatThrownBy(() -> await(binary, 1)).hasMessageContaining("JSON object");
        var malformed = new WebSocketSubscription();
        malformed.onText(mock(WebSocket.class), "private-message-marker", true);
        assertThatThrownBy(() -> await(malformed, 1)).hasMessageContaining("JSON object").hasMessageNotContaining("private");
        var closed = new WebSocketSubscription();
        closed.onClose(mock(WebSocket.class), 1008, "private close reason");
        assertThatThrownBy(() -> await(closed, 1)).hasMessageContaining("closed").hasMessageNotContaining("private");
    }

    @Test void limitsCountAcrossQueuedMessagesAndReturnsAfterTheDeclaredScanBudget() throws Exception {
        var session = new WebSocketSubscription();
        var socket = mock(WebSocket.class);
        for (int i = 0; i <= WebSocketSubscription.MAX_EVENTS; i++) session.onText(socket, "{}", true);
        assertThatThrownBy(() -> await(session, 3)).isInstanceOfSatisfying(WebSocketSubscription.Failure.class, error -> {
            assertThat(error.problem()).isEqualTo(WebSocketSubscription.Problem.NO_MATCH);
            assertThat(error.inspected()).isEqualTo(3);
        });
        assertThatThrownBy(() -> await(session, 64)).isInstanceOfSatisfying(WebSocketSubscription.Failure.class,
                error -> assertThat(error.problem()).isEqualTo(WebSocketSubscription.Problem.MESSAGE_LIMIT));
    }

    private static int await(WebSocketSubscription session, int maxEvents) throws Exception {
        return session.awaitEvent(JsonPointer.compile("/kind"), "changed", JsonPointer.compile("/meta/id"), 100, maxEvents);
    }

    private V03ExecutionContext context(Map<String, Object> bindings, Map<String, String> environment) {
        var target = new V03ResolvedTarget("events", "websocket.v0.3", "websocket", "external_websocket", "native", bindings);
        return new V03ExecutionContext(run, run, "external_websocket", Map.of("events", target), Map.of("fixtures", run),
                Map.of(), Map.of(), List.of(), Map.of(), environment, new V03ReferenceResolver(ignored -> Map.of()), Instant.now());
    }

    private V03StepResult step(V03ExecutionContext context, String operation, Map<String, Object> inputs) {
        String phase = switch (operation) {
            case "websocket_close" -> "cleanup";
            case "websocket_await_event" -> "verify";
            default -> "setup";
        };
        return adapter.execute(new V03ExecutionStep("case", phase, operation, "events", "websocket.v0.3", "websocket",
                "external_websocket", "native", operation, inputs, ""), context);
    }
}

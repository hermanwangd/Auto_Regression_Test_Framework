package com.specdriven.regression.contract.v03.adapter;

import com.fasterxml.jackson.core.JsonPointer;
import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03PlanCanonicalizer;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.contract.v03.ref.V03Reference;
import com.specdriven.regression.contract.v03.ref.V03ReferenceParser;
import com.specdriven.regression.provider.ProviderDataYaml;
import com.specdriven.regression.provider.websocket.WebSocketSubscription;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WebSocketV03Adapter implements V03ProviderRuntimeAdapter {
    private static final String ENV = "FRAMEWORK_WEBSOCKET_URI";
    private static final Set<String> SUBSCRIPTION = Set.of("subscription_ref", "ready_pointer", "ready_value");
    private static final Set<String> EXPECTATION = Set.of(
            "event_pointer", "expected_event", "correlation_pointer", "timeout_ms", "max_events");
    private static final V03PlanCanonicalizer HASH = new V03PlanCanonicalizer();

    private record SessionKey(V03ExecutionContext run, String test, String target) {}
    private final Map<SessionKey, WebSocketSubscription> sessions = new HashMap<>();

    @Override public String providerType() { return "websocket"; }
    @Override public boolean supports(String contract, String operation) {
        return "websocket.v0.3".equals(contract)
                && Set.of("websocket_subscribe", "websocket_await_event", "websocket_close").contains(operation);
    }

    @Override public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        V03StepResult result;
        try {
            if (!"native".equals(step.runtimeMode()) || !supports(step.providerContract(), step.operation())) {
                throw new IllegalArgumentException();
            }
            var key = new SessionKey(context, step.testCaseId(), step.target());
            result = switch (step.operation()) {
                case "websocket_subscribe" -> subscribe(step, key);
                case "websocket_await_event" -> await(step, key);
                case "websocket_close" -> close(step, key);
                default -> throw new IllegalArgumentException();
            };
        } catch (WebSocketSubscription.Failure error) {
            Map<String, Object> outputs = "websocket_await_event".equals(step.operation())
                    ? Map.of("inspected_events", error.inspected(), "matched", false) : Map.of();
            result = failed(step, "WEBSOCKET_" + error.problem().name(), error.getMessage(), outputs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result = failed(step, "WEBSOCKET_INTERRUPTED", "The WebSocket operation was interrupted.", Map.of());
        } catch (IOException | UncheckedIOException | IllegalArgumentException error) {
            result = failed(step, "WEBSOCKET_INVALID_INPUT",
                    "Check the operation phase, fixture, JSON pointers and bounded time or event inputs.", Map.of());
        }
        return evidence(context, step, result);
    }

    private V03StepResult subscribe(V03ExecutionStep step, SessionKey key)
            throws IOException, WebSocketSubscription.Failure, InterruptedException {
        var inputs = step.inputs();
        if (!"setup".equals(step.phase()) || !(inputs.isEmpty() || SUBSCRIPTION.equals(inputs.keySet()))) {
            throw new IllegalArgumentException();
        }
        String request = null;
        JsonPointer readyPointer = null;
        String readyValue = null;
        if (!inputs.isEmpty()) {
            readyPointer = pointer(inputs.get("ready_pointer"));
            readyValue = text(inputs.get("ready_value"));
            var ref = new V03ReferenceParser().parse(inputs.get("subscription_ref"));
            if (!(ref instanceof V03Reference.Artifact artifact) || !"fixtures".equals(artifact.root())
                    || !artifact.jsonPointer().isEmpty()) throw new IllegalArgumentException();
            var file = key.run().referenceResolver().artifactPath(artifact, key.run().referenceContext(step.testCaseId()));
            try (var stream = Files.newInputStream(file)) {
                byte[] bytes = stream.readNBytes(WebSocketSubscription.MAX_TEXT_BYTES + 1);
                if (bytes.length > WebSocketSubscription.MAX_TEXT_BYTES) throw new IllegalArgumentException();
                request = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                WebSocketSubscription.validateRequest(request);
            }
        }
        var target = key.run().targets().get(step.target());
        URI uri = target != null && Map.of("endpoint_ref", "env://" + ENV).equals(target.bindings())
                ? endpoint(key.run().environment().get(ENV)) : null;
        if (uri == null) {
            return failed(step, "WEBSOCKET_CONNECTION_CONFIG",
                    "Supply the runner-authorized ws or wss URI through the fixed endpoint binding.", Map.of());
        }
        var session = new WebSocketSubscription();
        synchronized (sessions) {
            if (sessions.size() >= 32 || sessions.putIfAbsent(key, session) != null) {
                return failed(step, "WEBSOCKET_SESSION_CONFLICT",
                        "Close the existing subscription or reduce concurrent WebSocket sessions.", Map.of());
            }
        }
        boolean opened = false;
        try {
            session.open(uri, request, readyPointer, readyValue);
            opened = true;
            return passed(step, Map.of("correlation_id", session.correlationId(), "opened", true));
        } finally {
            if (!opened) {
                synchronized (sessions) { sessions.remove(key, session); }
                session.abort();
            }
        }
    }

    private V03StepResult await(V03ExecutionStep step, SessionKey key)
            throws WebSocketSubscription.Failure, InterruptedException {
        var inputs = step.inputs();
        if (!"verify".equals(step.phase()) || !EXPECTATION.equals(inputs.keySet())) throw new IllegalArgumentException();
        JsonPointer eventPointer = pointer(inputs.get("event_pointer"));
        String expected = text(inputs.get("expected_event"));
        JsonPointer correlation = pointer(inputs.get("correlation_pointer"));
        int timeoutMs = integer(inputs.get("timeout_ms"), 10000);
        int maxEvents = integer(inputs.get("max_events"), WebSocketSubscription.MAX_EVENTS);
        WebSocketSubscription session;
        synchronized (sessions) { session = sessions.get(key); }
        if (session == null) return failed(step, "WEBSOCKET_SESSION_MISSING", "Open this target during setup.", Map.of());
        int inspected = session.awaitEvent(eventPointer, expected, correlation, timeoutMs, maxEvents);
        return passed(step, Map.of("matched", true, "inspected_events", inspected));
    }

    private V03StepResult close(V03ExecutionStep step, SessionKey key)
            throws WebSocketSubscription.Failure, InterruptedException {
        if (!"cleanup".equals(step.phase()) || !step.inputs().isEmpty()) throw new IllegalArgumentException();
        WebSocketSubscription session;
        synchronized (sessions) { session = sessions.remove(key); }
        if (session != null) session.close();
        return passed(step, Map.of("closed", true, "was_open", session != null));
    }

    @Override public void endRun(V03ExecutionContext context) {
        synchronized (sessions) {
            sessions.entrySet().removeIf(entry -> {
                if (entry.getKey().run() != context) return false;
                entry.getValue().abort();
                return true;
            });
        }
    }

    static URI endpoint(String raw) {
        if (raw == null || raw.length() > 2048) return null;
        try {
            URI uri = URI.create(raw);
            if (!Set.of("ws", "wss").contains(uri.getScheme() == null ? "" : uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) return null;
            return uri;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String text(Object raw) {
        if (!(raw instanceof String value) || value.isBlank() || value.length() > 256
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return value;
    }

    private static JsonPointer pointer(Object raw) {
        String path = text(raw);
        if (!path.startsWith("/") || !path.matches("(?:[^~]|~[01])*")) throw new IllegalArgumentException();
        return JsonPointer.compile(path);
    }

    private static int integer(Object raw, int max) {
        if (!(raw instanceof Integer || raw instanceof Long)) throw new IllegalArgumentException();
        long number = ((Number) raw).longValue();
        if (number < 1 || number > max) throw new IllegalArgumentException();
        return (int) number;
    }

    private static V03StepResult passed(V03ExecutionStep step, Map<String, Object> outputs) {
        return new V03StepResult(step.id(), "passed", outputs, List.of(), "", "");
    }

    private static V03StepResult failed(V03ExecutionStep step, String code, String message, Map<String, Object> outputs) {
        return new V03StepResult(step.id(), "failed", outputs, List.of(), code, message);
    }

    private static V03StepResult evidence(V03ExecutionContext context, V03ExecutionStep step, V03StepResult result) {
        String digest = HASH.digest(List.of(step.testCaseId(), step.id()));
        String kind = "websocket_close".equals(step.operation()) ? "cleanup" : "operation";
        String ref = "provider-evidence/websocket/" + kind + "-" + digest + ".yaml";
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("operation", step.operation());
        fields.put("status", result.status());
        fields.put("outputs", result.outputs());
        if (!result.passed()) fields.put("failure_code", result.failureCode());
        try {
            ProviderDataYaml.writeEvidence(context.runDir().resolve(ref), fields);
            return new V03StepResult(step.id(), result.status(), result.outputs(), List.of(ref), result.failureCode(), result.message());
        } catch (IOException error) {
            return failed(step, "WEBSOCKET_EVIDENCE_FAILED", "Could not write WebSocket operation evidence.", Map.of());
        }
    }
}

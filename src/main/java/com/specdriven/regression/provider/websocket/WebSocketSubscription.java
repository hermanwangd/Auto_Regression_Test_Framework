package com.specdriven.regression.provider.websocket;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/** One bounded JSON text stream. Neither frame contents nor peer-supplied close reasons leave this class. */
public final class WebSocketSubscription implements WebSocket.Listener {
    public static final int MAX_TEXT_BYTES = 8192;
    public static final int MAX_EVENTS = 64;
    private static final int IO_TIMEOUT_MS = 3000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(IO_TIMEOUT_MS);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public enum Problem {
        UNAVAILABLE("WebSocket connection or transport failed."),
        MESSAGE_LIMIT("WebSocket message, fragment or event limit exceeded."),
        INVALID_MESSAGE("Expected a UTF-8 JSON object in a WebSocket text message."),
        NO_MATCH("No matching WebSocket event arrived within the declared time or count."),
        STREAM_CLOSED("The peer closed the stream before a matching event arrived."),
        CLOSE_FAILED("The peer did not confirm a normal WebSocket close."),
        INTERRUPTED("The WebSocket operation was interrupted.");

        private final String description;
        Problem(String description) { this.description = description; }
        public String description() { return description; }
    }

    public static final class Failure extends Exception {
        private final Problem problem;
        private final int inspected;

        private Failure(Problem problem, int inspected) {
            super(problem.description());
            this.problem = problem;
            this.inspected = inspected;
        }
        public Problem problem() { return problem; }
        public int inspected() { return inspected; }
    }

    private record Received(String text, Problem failure) {}
    // The extra slot always fits the final close/error notification after MAX_EVENTS text messages.
    private final ArrayBlockingQueue<Received> inbox = new ArrayBlockingQueue<>(MAX_EVENTS + 1);
    private final CompletableFuture<Integer> peerClose = new CompletableFuture<>();
    private final String correlationId = UUID.randomUUID().toString();
    private final StringBuilder fragments = new StringBuilder();
    private int messageCount;
    private int fragmentCount;
    private boolean terminal;
    private boolean aborted;
    private WebSocket socket;

    public String correlationId() { return correlationId; }

    public static void validateRequest(String text) throws IOException {
        JsonNode document = JSON.readTree(text);
        if (document == null || !document.isObject()) throw new IOException("Expected a JSON object.");
    }

    public void open(URI endpoint, String request, JsonPointer readyPointer, String readyValue)
            throws Failure, InterruptedException {
        var opening = CLIENT.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT).buildAsync(endpoint, this);
        WebSocket connected = awaitFuture(opening, Problem.UNAVAILABLE, IO_TIMEOUT_MS);
        if (request != null) {
            awaitFuture(connected.sendText(request, true), Problem.UNAVAILABLE, IO_TIMEOUT_MS);
            await(node -> readyValue.equals(node.at(readyPointer).textValue()), IO_TIMEOUT_MS, MAX_EVENTS);
        }
    }

    public int awaitEvent(JsonPointer eventPointer, String event, JsonPointer correlationPointer,
            int timeoutMs, int maxEvents) throws Failure, InterruptedException {
        return await(node -> event.equals(node.at(eventPointer).textValue())
                && correlationId.equals(node.at(correlationPointer).textValue()), timeoutMs, maxEvents);
    }

    private int await(Predicate<JsonNode> matches, int timeoutMs, int maxEvents) throws Failure, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (int inspected = 0; inspected < maxEvents; inspected++) {
            long remaining = deadline - System.nanoTime();
            Received item = remaining > 0 ? inbox.poll(remaining, TimeUnit.NANOSECONDS) : null;
            if (item == null) throw new Failure(Problem.NO_MATCH, inspected);
            if (item.failure() != null) {
                // Keep the terminal outcome visible to subsequent consumers.
                inbox.offer(item);
                throw new Failure(item.failure(), inspected);
            }
            try {
                JsonNode object = JSON.readTree(item.text());
                if (object == null || !object.isObject()) throw new Failure(Problem.INVALID_MESSAGE, inspected + 1);
                if (matches.test(object)) return inspected + 1;
            } catch (IOException error) {
                throw new Failure(Problem.INVALID_MESSAGE, inspected + 1);
            }
        }
        throw new Failure(Problem.NO_MATCH, maxEvents);
    }

    @Override public synchronized void onOpen(WebSocket connected) {
        if (aborted) {
            connected.abort();
            return;
        }
        socket = connected;
        connected.request(1);
    }

    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (++fragmentCount > 128 || data.length() > MAX_TEXT_BYTES - fragments.length()) {
            reject(webSocket, Problem.MESSAGE_LIMIT);
            return null;
        }
        fragments.append(data);
        if (last) {
            String text = fragments.toString();
            fragments.setLength(0);
            fragmentCount = 0;
            if (++messageCount > MAX_EVENTS || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
                reject(webSocket, Problem.MESSAGE_LIMIT);
                return null;
            }
            inbox.add(new Received(text, null));
        }
        webSocket.request(1);
        return null;
    }

    @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        reject(webSocket, Problem.INVALID_MESSAGE);
        return null;
    }

    private void reject(WebSocket webSocket, Problem problem) {
        finish(problem);
        webSocket.abort();
        peerClose.completeExceptionally(new IOException(problem.description()));
    }

    @Override public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
        finish(Problem.STREAM_CLOSED);
        peerClose.complete(code);
        return null;
    }

    @Override public void onError(WebSocket webSocket, Throwable error) {
        finish(Problem.UNAVAILABLE);
        peerClose.completeExceptionally(new IOException(Problem.UNAVAILABLE.description()));
    }

    // The JDK serializes listener callbacks for a WebSocket.
    private void finish(Problem problem) {
        if (!terminal) {
            terminal = true;
            inbox.add(new Received(null, problem));
        }
    }

    public void close() throws Failure, InterruptedException {
        WebSocket connected;
        synchronized (this) { connected = socket; }
        try {
            if (connected == null) return;
            if (!connected.isOutputClosed()) {
                awaitFuture(connected.sendClose(WebSocket.NORMAL_CLOSURE, ""), Problem.CLOSE_FAILED, IO_TIMEOUT_MS);
            }
            int code = awaitFuture(peerClose, Problem.CLOSE_FAILED, IO_TIMEOUT_MS);
            if (code != WebSocket.NORMAL_CLOSURE && code != 1001) throw new Failure(Problem.CLOSE_FAILED, 0);
        } finally {
            abort();
        }
    }

    public synchronized void abort() {
        aborted = true;
        if (socket != null) socket.abort();
    }

    private static <T> T awaitFuture(CompletableFuture<T> future, Problem problem, int timeoutMs)
            throws Failure, InterruptedException {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException error) {
            future.cancel(true);
            throw new Failure(problem, 0);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            throw interrupted;
        }
    }
}

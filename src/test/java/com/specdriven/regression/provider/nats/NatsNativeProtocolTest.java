package com.specdriven.regression.provider.nats;

import static org.assertj.core.api.Assertions.assertThat;
import com.specdriven.regression.provider.runtime.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A loopback protocol peer checks framing; this is not public-product execution evidence. */
class NatsNativeProtocolTest {
    @TempDir Path temporary;
    static final String SUBJECT = "sit." + "a".repeat(32) + ".echo";

    @Test void publishRequiresTheExactNativeEcho() throws Exception {
        try (Peer peer = new Peer("echo")) {
            var result = publish(peer.port(), "PT0.5S");
            assertThat(result.passed()).as(String.valueOf(result.failure())).isTrue();
            assertThat(result.outputs()).containsEntry("published", true);
            peer.completed();
        }
    }

    @ParameterizedTest @ValueSource(strings={"pong", "wrong", "wrong_subject", "wrong_sid", "oversized", "truncated", "bad_crlf", "bad_utf8", "error", "long_line"})
    void invalidOrMissingNativeEchoCannotPass(String response) throws Exception {
        try (Peer peer = new Peer(response)) {
            var result = publish(peer.port(), "PT0.2S");
            assertThat(result.passed()).as(response).isFalse();
            assertThat(result.failure()).isNotNull();
            assertThat(result.outputs()).doesNotContainEntry("published", true);
            peer.completed();
        }
    }

    @Test void partialBodyCannotOutliveTheWholeOperationDeadline() throws Exception {
        try (Peer peer = new Peer("stall")) {
            long started = System.nanoTime();
            var result = publish(peer.port(), "PT0.1S");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            assertThat(result.passed()).isFalse();
        }
    }

    @Test void absentListenerIsAConnectionFailure() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = unused.getLocalPort(); }
        var result = publish(port, "PT0.1S");
        assertThat(result.passed()).isFalse();
        assertThat(result.failure().code()).isEqualTo("NATS_CONNECTION_FAILED");
    }

    ProviderOperationResult publish(int port, String timeout) {
        System.setProperty("NATS_NATIVE_PROTOCOL_TEST", "nats://127.0.0.1:" + port);
        try {
            var context = new ProviderExecutionContext("bus", "nats", "native_test", "native", temporary, temporary,
                    Map.of(), Map.of(), Map.of("connection", Map.of("secret_ref", "env://NATS_NATIVE_PROTOCOL_TEST"),
                    "subject", SUBJECT, "timeout", timeout, "poll_interval", "PT0.01S"));
            return new NatsProviderRuntime().execute(context, new ProviderOperationRequest("nats_publish",
                    List.of(Map.of("bind_as", "payload", "ref", "{\"id\":\"reviewed\"}")), Map.of("_operation_id", "publish")));
        } finally { System.clearProperty("NATS_NATIVE_PROTOCOL_TEST"); }
    }

    static final class Peer implements AutoCloseable {
        final ServerSocket server;
        final ExecutorService executor = Executors.newSingleThreadExecutor(task -> { var t = new Thread(task, "nats-test-peer"); t.setDaemon(true); return t; });
        final Future<?> task;
        Peer(String behavior) throws Exception {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            task = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    var in = new BufferedInputStream(socket.getInputStream()); var out = socket.getOutputStream();
                    out.write("INFO {}\r\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                    line(in); String subscription = line(in); String publish = line(in);
                    if (!subscription.equals("SUB " + SUBJECT + " 1")) throw new IOException("wrong subscription");
                    int length = Integer.parseInt(publish.substring(publish.lastIndexOf(' ') + 1));
                    byte[] payload = in.readNBytes(length); line(in); line(in);
                    String subject = behavior.equals("wrong_subject") ? "other.subject" : SUBJECT;
                    String sid = behavior.equals("wrong_sid") ? "2" : "1";
                    byte[] echo = behavior.equals("wrong") ? "wrong".getBytes(StandardCharsets.UTF_8) : payload;
                    if (behavior.equals("pong")) out.write("PONG\r\n".getBytes(StandardCharsets.UTF_8));
                    else if (behavior.equals("error")) out.write("-ERR 'Permissions Violation'\r\n".getBytes(StandardCharsets.UTF_8));
                    else if (behavior.equals("long_line")) out.write(("+OK " + "x".repeat(9000) + "\r\n").getBytes(StandardCharsets.UTF_8));
                    else if (behavior.equals("oversized")) out.write(("MSG " + subject + " 1 2147483647\r\n").getBytes(StandardCharsets.UTF_8));
                    else {
                        int frameLength = behavior.equals("bad_utf8") ? 2 : echo.length;
                        out.write(("MSG " + subject + " " + sid + " " + frameLength + "\r\n").getBytes(StandardCharsets.UTF_8));
                        if (behavior.equals("stall")) { out.write('{'); out.flush(); Thread.sleep(500); }
                        else if (behavior.equals("truncated")) out.write('{');
                        else {
                            out.write(behavior.equals("bad_utf8") ? new byte[]{(byte)0xc3, (byte)0x28} : echo);
                            out.write((behavior.equals("bad_crlf") ? "xx" : "\r\n").getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    out.flush();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        int port() { return server.getLocalPort(); }
        void completed() throws Exception { task.get(3, TimeUnit.SECONDS); }
        public void close() throws Exception { server.close(); executor.shutdownNow(); }
        static String line(InputStream in) throws IOException {
            var bytes = new ByteArrayOutputStream(); int next;
            while ((next = in.read()) >= 0) { if (next == '\n') return bytes.toString(StandardCharsets.UTF_8).strip(); if (next != '\r') bytes.write(next); }
            throw new EOFException();
        }
    }
}

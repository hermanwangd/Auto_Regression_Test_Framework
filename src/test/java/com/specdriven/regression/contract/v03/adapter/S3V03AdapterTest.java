package com.specdriven.regression.contract.v03.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ResolvedTarget;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.contract.v03.ref.V03ReferenceResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class S3V03AdapterTest {
    private static final Map<String, Object> BINDINGS = Map.of(
            "endpoint_ref", "env://REGRESS_S3_ENDPOINT", "bucket_ref", "env://REGRESS_S3_BUCKET",
            "access_key_ref", "env://REGRESS_S3_ACCESS_KEY", "secret_key_ref", "env://REGRESS_S3_SECRET_KEY");
    private static final Map<String, Object> NAME = Map.of("name", "hello.txt");
    private static final Map<String, Object> UPLOAD = Map.of("name", "hello.txt", "payload_ref", "artifact://fixtures/data");
    private static final String SHA_HELLO = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    @TempDir Path suite;
    private final S3V03Adapter adapter = new S3V03Adapter();
    private final Map<String, String> env = new HashMap<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile String objectPath;
    private volatile byte[] contents;
    private volatile String owner;
    private volatile String deleteQuery;
    private volatile String deleteIfMatch;
    private volatile String putIfNoneMatch;
    private volatile boolean signed;
    private volatile int deniedStatus;
    private volatile boolean retainOnDelete;
    private volatile String putVersion;
    private volatile boolean foreignVersionIsCurrent;

    @BeforeEach
    void start() throws IOException {
        Files.createDirectories(suite.resolve("fixtures"));
        Files.writeString(suite.resolve("fixtures/data"), "hello");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        env.put("REGRESS_S3_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort());
        env.put("REGRESS_S3_BUCKET", "fixture-bucket");
        env.put("REGRESS_S3_ACCESS_KEY", "test-access-key");
        env.put("REGRESS_S3_SECRET_KEY", "test-signing-material");
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void signsPathStyleCallsVerifiesDownloadedBytesAndDeletesTheExactVersion() {
        var context = context("run-one", BINDINGS);
        assertThat(run(context, "s3_put_object", UPLOAD).outputs()).containsEntry("sha256", SHA_HELLO);
        assertThat(objectPath).matches("/fixture-bucket/regress/[0-9a-f]{64}/hello\\.txt");
        assertThat(putIfNoneMatch).isEqualTo("*");
        assertThat(signed).isTrue();
        assertThat(run(context, "s3_head_object", NAME).outputs()).containsEntry("exists", true);
        var verify = Map.<String,Object>of("name", "hello.txt", "expected_sha256", SHA_HELLO);
        assertThat(run(context, "s3_verify_object", verify).passed()).isTrue();
        contents = new byte[] {42};
        var mismatch = run(context, "s3_verify_object", verify);
        assertThat(mismatch.failureCode()).isEqualTo("S3_CHECKSUM_MISMATCH");
        assertThat(mismatch.outputs()).containsEntry("content_length", 1);
        assertThat(run(context, "s3_delete_object", NAME).outputs()).containsEntry("deleted", true);
        assertThat(deleteQuery).isEqualTo("versionId=v1");
        assertThat(deleteIfMatch).isEqualTo("\"fixture-etag\"");
        assertThat(run(context, "s3_delete_object", NAME).outputs()).containsEntry("deleted", false);
        assertThat(methods).containsExactly("PUT", "HEAD", "GET", "GET", "HEAD", "DELETE", "HEAD", "HEAD");
    }

    @Test
    void cleanupTargetsAcknowledgedUploadEvenWhenAnotherVersionIsCurrent() {
        var context = context("run-versioned", BINDINGS);
        putVersion = "v1";
        assertThat(run(context, "s3_put_object", UPLOAD).passed()).isTrue();
        foreignVersionIsCurrent = true;

        assertThat(run(context, "s3_delete_object", NAME).outputs()).containsEntry("deleted", true);
        assertThat(deleteQuery).isEqualTo("versionId=v1");
        assertThat(run(context, "s3_delete_object", NAME).outputs()).containsEntry("deleted", false);

        adapter.endRun(context);
        assertThat(run(context, "s3_delete_object", NAME).failureCode()).isEqualTo("S3_OWNERSHIP_MISMATCH");
        assertThat(methods).containsExactly("PUT", "HEAD", "DELETE", "HEAD", "HEAD", "HEAD");
    }

    @Test
    void readsApplicationExportOnlyAtTheAssignedKeyAndLeavesItUntouched() {
        var context = context("app-run", BINDINGS);
        var key = run(context, "s3_object_key", NAME);
        assertThat(key.passed()).isTrue();
        String assigned = (String) key.outputs().get("object_key");
        assertThat(assigned).matches("regress/[0-9a-f]{64}/exports/hello\\.txt");
        assertThat(methods).isEmpty();

        // Simulate the SUT writing the object without any framework ownership metadata.
        objectPath = "/fixture-bucket/" + assigned;
        contents = "hello".getBytes(StandardCharsets.UTF_8);
        var app = Map.<String, Object>of("name", "hello.txt", "application_export", true);
        var verify = Map.<String, Object>of("name", "hello.txt", "application_export", true, "expected_sha256", SHA_HELLO);
        assertThat(run(context, "s3_head_object", app).outputs()).containsEntry("object_key", assigned);
        assertThat(run(context, "s3_verify_object", verify).outputs()).containsEntry("sha256", SHA_HELLO);
        contents = new byte[]{42};
        assertThat(run(context, "s3_verify_object", verify).failureCode()).isEqualTo("S3_CHECKSUM_MISMATCH");

        assertThat(run(context, "s3_head_object", NAME).failureCode()).isEqualTo("S3_OBJECT_MISSING");
        assertThat(run(context("another-run", BINDINGS), "s3_head_object", app).failureCode()).isEqualTo("S3_OBJECT_MISSING");
        assertThat(run(context, "s3_head_object", Map.of("name", assigned, "application_export", true)).failureCode())
                .isEqualTo("S3_INVALID_CONFIGURATION");
        assertThat(run(context, "s3_delete_object", app).failureCode()).isEqualTo("S3_INVALID_CONFIGURATION");
        assertThat(run(context, "s3_delete_object", NAME).outputs()).containsEntry("deleted", false);
        assertThat(contents).containsExactly(42);
        assertThat(methods).containsExactly("HEAD", "GET", "GET", "HEAD", "HEAD", "HEAD");
    }

    @Test
    void detectsAcknowledgedDeleteWithAnObjectStillPresent() {
        retainOnDelete = true;
        var context = context("run-retained", BINDINGS);
        assertThat(run(context, "s3_put_object", UPLOAD).passed()).isTrue();
        assertThat(run(context, "s3_delete_object", NAME).failureCode()).isEqualTo("S3_CLEANUP_NOT_CONFIRMED");
        assertThat(contents).isNotNull();
    }

    @Test
    void cleanupLeavesAnotherOwnerAndRejectsUnscopedNamesAndBindings() {
        var context = context("run-one", BINDINGS);
        assertThat(run(context, "s3_put_object", UPLOAD).passed()).isTrue();
        owner = "different-owner";
        assertThat(run(context, "s3_delete_object", NAME).failureCode()).isEqualTo("S3_OWNERSHIP_MISMATCH");
        assertThat(run(context, "s3_delete_object", Map.of("name", "../other")).failureCode())
                .isEqualTo("S3_INVALID_CONFIGURATION");
        var bindings = new HashMap<>(BINDINGS);
        bindings.put("secret_key_ref", "env://UNRELATED");
        assertThat(run(context("run-one", bindings), "s3_delete_object", NAME).failureCode())
                .isEqualTo("S3_INVALID_CONFIGURATION");
        assertThat(methods).containsExactly("PUT", "HEAD");
    }

    @Test
    void capsUploadsAndDownloadsAndUsesDistinctKeysForConcurrentRuns() throws IOException {
        var first = context("run-one", BINDINGS);
        assertThat(run(first, "s3_put_object", UPLOAD).passed()).isTrue();
        String firstKey = objectPath;
        contents = new byte[S3V03Adapter.MAX_BYTES + 1];
        assertThat(run(first, "s3_verify_object", Map.of("name", "hello.txt", "expected_sha256", SHA_HELLO))
                .failureCode()).isEqualTo("S3_OBJECT_TOO_LARGE");
        var second = context("run-two", BINDINGS);
        assertThat(run(second, "s3_put_object", UPLOAD).passed()).isTrue();
        assertThat(objectPath).isNotEqualTo(firstKey);
        Files.write(suite.resolve("fixtures/data"), new byte[S3V03Adapter.MAX_BYTES + 1]);
        assertThat(run(second, "s3_put_object", UPLOAD).failureCode()).isEqualTo("S3_OBJECT_TOO_LARGE");
        assertThat(methods).containsExactly("PUT", "GET", "PUT");
    }

    @Test
    void serviceErrorsAndMalformedEndpointsKeepSensitiveTextOutOfEvidence() throws IOException {
        deniedStatus = 403;
        var context = context("run-three", BINDINGS);
        V03StepResult result = run(context, "s3_put_object", UPLOAD);
        assertThat(result.failureCode()).isEqualTo("S3_ACCESS_DENIED");
        assertThat(result.toString()).doesNotContain("test-signing-material");
        assertThat(Files.readString(context.runDir().resolve(result.evidenceRefs().get(0))))
                .doesNotContain("test-access-key", "test-signing-material", "127.0.0.1", "hello.txt");
        env.put("REGRESS_S3_ENDPOINT", "https://test-signing-material@127.0.0.1/");
        assertThat(run(context, "s3_head_object", NAME).toString()).contains("S3_INVALID_CONFIGURATION")
                .doesNotContain("test-signing-material");
        assertThat(methods).containsExactly("PUT");
    }

    private V03ExecutionContext context(String run, Map<String, Object> bindings) {
        var target = new V03ResolvedTarget("store", "s3.v0.3", "s3", "external_s3", "native", bindings);
        return new V03ExecutionContext(suite, suite.resolve(run), "external_s3", Map.of("store", target),
                Map.of("fixtures", suite.resolve("fixtures")), new HashMap<>(), new HashMap<>(),
                List.of(), Map.of(), env, new V03ReferenceResolver(), Instant.EPOCH);
    }

    private V03StepResult run(V03ExecutionContext context, String operation, Map<String, Object> inputs) {
        String phase = switch (operation) {
            case "s3_object_key" -> "setup";
            case "s3_put_object" -> "execute";
            case "s3_delete_object" -> "cleanup";
            default -> "verify";
        };
        var step = new V03ExecutionStep("CASE", phase, operation, "store", "s3.v0.3",
                "s3", "external_s3", "native", operation, inputs, "");
        return adapter.execute(step, context);
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            methods.add(method);
            var headers = exchange.getRequestHeaders();
            if (deniedStatus != 0) {
                byte[] xml = "<Error><Code>AccessDenied</Code><Message>test-signing-material</Message></Error>"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(deniedStatus, xml.length);
                exchange.getResponseBody().write(xml);
                return;
            }
            switch (method) {
                case "PUT" -> {
                    objectPath = exchange.getRequestURI().getPath();
                    contents = exchange.getRequestBody().readAllBytes();
                    owner = headers.getFirst("x-amz-meta-regress-owner");
                    putIfNoneMatch = headers.getFirst("If-None-Match");
                    String authorization = headers.getFirst("Authorization");
                    signed = authorization != null && authorization.startsWith("AWS4-HMAC-SHA256 ");
                    if (putVersion != null) exchange.getResponseHeaders().set("x-amz-version-id", putVersion);
                    exchange.sendResponseHeaders(200, -1);
                }
                case "HEAD", "GET" -> {
                    if (foreignVersionIsCurrent && exchange.getRequestURI().getQuery() == null) {
                        exchange.getResponseHeaders().set("x-amz-version-id", "v2");
                        exchange.getResponseHeaders().set("ETag", "\"foreign-etag\"");
                        exchange.sendResponseHeaders(200, -1);
                        return;
                    }
                    if (contents == null || !exchange.getRequestURI().getPath().equals(objectPath)) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    var response = exchange.getResponseHeaders();
                    if (owner != null) response.set("x-amz-meta-regress-owner", owner);
                    response.set("ETag", "\"fixture-etag\"");
                    response.set("x-amz-version-id", "v1");
                    response.set("Content-Length", String.valueOf(contents.length));
                    if (method.equals("GET")) {
                        exchange.sendResponseHeaders(200, contents.length);
                        exchange.getResponseBody().write(contents);
                    } else {
                        exchange.sendResponseHeaders(200, -1);
                    }
                }
                case "DELETE" -> {
                    deleteIfMatch = headers.getFirst("If-Match");
                    deleteQuery = exchange.getRequestURI().getQuery();
                    if (!retainOnDelete) contents = null;
                    exchange.sendResponseHeaders(204, -1);
                }
                default -> exchange.sendResponseHeaders(405, -1);
            }
        }
    }
}

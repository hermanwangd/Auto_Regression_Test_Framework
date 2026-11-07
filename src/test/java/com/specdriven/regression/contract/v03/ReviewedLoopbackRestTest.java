package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.producttest.ProductTestHandoffService;
import com.specdriven.regression.producttest.ProductTestHandoffService.InvocationRequirement;
import com.specdriven.regression.summary.SuiteExecutionContext;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

class ReviewedLoopbackRestTest {
    @TempDir Path temporary;
    final ObjectMapper json = new ObjectMapper();
    final Yaml yaml = new Yaml();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger redirected = new AtomicInteger();
    HttpServer server;
    static final String BODY = "[{\"id\":\"fixture-1\",\"name\":\"public fixture\",\"completed\":true}]";
    @AfterEach void closeServer() { if (server != null) server.stop(0); }

    @ParameterizedTest @ValueSource(strings={"GET","HEAD"})
    void reviewedNativeReadUsesExistingProviderAndCanonicalEvidence(String method) throws Exception {
        String origin = serve(200, BODY);
        Path suite = fixture(origin, method, method.equals("GET"));
        var run = run(suite);
        assertThat(run.status()).as(run.findings().toString()).isEqualTo("passed");
        assertThat(calls).hasValue(1);
        var result = json.readTree(run.resultJson().toFile());
        assertThat(result.path("provider_results").get(0).path("provider_type").asText()).isEqualTo("rest_client");
        assertThat(result.path("provider_results").get(0).path("runtime_mode").asText()).isEqualTo("native");
        assertThat(result.path("product_test").path("test_level").asText()).isEqualTo("integration");
        assertThat(result.path("labels").path("downstream_release_evidence").asBoolean()).isFalse();
        assertValid(run);
    }

    @Test void wrongReviewedJsonOracleProducesActualAssertionFailure() throws Exception {
        Path suite = fixture(serve(200,BODY),"GET",true);
        Files.writeString(suite.resolve("expected/items.json"),BODY.replace("true","false"));
        var run=run(suite);
        assertThat(calls).hasValue(1);
        assertThat(run.status()).isEqualTo("failed");
        assertThat(json.readTree(run.resultJson().toFile()).path("failure").path("code").asText()).isEqualTo("ASSERTION_FAILED");
        assertValid(run);
    }

    @ParameterizedTest @ValueSource(strings={"remote","dns","userinfo","missing_port","post","put","delete","authority_path","body","header","zero_timeout","multiple_calls","shared_state","fake_tuple"})
    void unsupportedNativeInputsRejectBeforeAnyRequest(String variant) throws Exception {
        Path suite=fixture(serve(200,BODY),"GET",true);
        var profile=read(suite.resolve("env_profiles/local_native.yaml"));
        var test=read(suite.resolve("test_cases/items.yaml"));
        Map<String,Object> inputs=map(map(((List<?>)test.get("execute")).get(0)).get("with"));
        Map<String,Object> binding=map(map(map(profile.get("targets")).get("todo_api")).get("bindings"));
        switch(variant) {
            case "remote" -> binding.put("base_url","http://192.0.2.1:8080");
            case "dns" -> binding.put("base_url","http://localhost:8080");
            case "userinfo" -> binding.put("base_url","http://user@127.0.0.1:8080");
            case "missing_port" -> binding.put("base_url","http://127.0.0.1");
            case "post","put","delete" -> inputs.put("request.method",variant.toUpperCase(Locale.ROOT));
            case "authority_path" -> inputs.put("request.path","//example.invalid/api/items");
            case "body" -> inputs.put("request.body","unreviewed payload");
            case "header" -> inputs.put("request.headers.Authorization","Bearer unreviewed");
            case "zero_timeout" -> inputs.put("request.timeout","PT0S");
            case "multiple_calls" -> {
                Map<String,Object> other=new LinkedHashMap<>(map(((List<?>)test.get("execute")).get(0)));
                other.put("id","second_read"); ((List<Object>)test.get("execute")).add(other);
            }
            case "shared_state" -> map(profile.get("product_test")).put("shared_state",Map.of("environment_id","public","namespace","todo","owner","test"));
            case "fake_tuple" -> map(profile.get("product_test")).put("test_level","component");
        }
        write(suite.resolve("env_profiles/local_native.yaml"),profile); write(suite.resolve("test_cases/items.yaml"),test);
        assertThatThrownBy(() -> prepare(suite)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @Test void redirectIsRejectedWithoutFollowingItsLocation() throws Exception {
        Path suite=fixture(serve(302,"redirect"),"GET",false);
        var run=run(suite);
        assertThat(run.status()).isEqualTo("failed");
        assertThat(calls).hasValue(1); assertThat(redirected).hasValue(0);
        assertThat(json.readTree(run.resultJson().toFile()).path("failure").path("code").asText()).isEqualTo("HTTP_REQUEST_FAILED");
        assertValid(run);
    }

    @Test void responseBodyIsCappedBeforeCanonicalArtifactsAreBuilt() throws Exception {
        Path suite=fixture(serve(200,"x".repeat(1024*1024+1)),"GET",false);
        var run=run(suite);
        assertThat(run.status()).isEqualTo("failed");
        assertThat(json.readTree(run.resultJson().toFile()).path("failure").path("code").asText()).isEqualTo("HTTP_REQUEST_FAILED");
        assertThat(Files.size(run.evidenceDir().resolve("provider-evidence/http/response.json"))).isLessThan(4096);
        assertValid(run);
    }

    @Test void heldOpenResponseBodyHonorsTheWholeReviewedDeadline() throws Exception {
        var headersSent = new java.util.concurrent.CountDownLatch(1);
        var releaseBody = new java.util.concurrent.CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/items", exchange -> {
            calls.incrementAndGet();
            try {
                exchange.sendResponseHeaders(200,2);
                exchange.getResponseBody().write('['); exchange.getResponseBody().flush();
                headersSent.countDown();
                releaseBody.await(6,java.util.concurrent.TimeUnit.SECONDS);
                exchange.getResponseBody().write(']');
            } catch (java.io.IOException ignored) { /* The timed-out client closes its response. */ }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        Path suite = fixture("http://127.0.0.1:"+server.getAddress().getPort(),"GET",false);
        Files.writeString(suite.resolve("test_cases/items.yaml"),Files.readString(suite.resolve("test_cases/items.yaml")).replace("PT2S","PT0.5S"));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task,"reviewed-http-deadline-test"); thread.setDaemon(true); return thread;
        });
        var future = executor.submit(() -> run(suite));
        V03RuntimeExecutionService.V03RuntimeRunResult result = null;
        boolean exceededBound = false;
        try {
            assertThat(headersSent.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try { result = future.get(3,java.util.concurrent.TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException timeout) { exceededBound = true; }
        } finally {
            releaseBody.countDown();
            try { future.get(3,java.util.concurrent.TimeUnit.SECONDS); }
            finally { executor.shutdownNow(); }
        }
        assertThat(exceededBound).as("the reviewed deadline must cover a body stalled after headers").isFalse();
        assertThat(calls).hasValue(1);
        assertThat(result.status()).isEqualTo("failed");
        assertThat(json.readTree(result.resultJson().toFile()).path("failure").path("code").asText()).isEqualTo("PROVIDER_TIMEOUT");
        assertValid(result);
    }

    @ParameterizedTest @ValueSource(strings={"missing_observed","wrong_runtime","fake_tuple"})
    void directValidationRejectsNativeEvidenceRelabeling(String alteration) throws Exception {
        var run=run(fixture(serve(200,BODY),"GET",true)); assertThat(run.status()).isEqualTo("passed");
        Path provenancePath=run.evidenceDir().resolve("product-test/reviewed-handoff.json");
        ObjectNode provenance=(ObjectNode)json.readTree(provenancePath.toFile());
        ObjectNode result=(ObjectNode)json.readTree(run.resultJson().toFile());
        if (alteration.equals("missing_observed")) provenance.putObject("observed_provider_modes");
        if (alteration.equals("wrong_runtime")) ((ObjectNode)result.path("provider_results").get(0)).put("runtime_mode","stub");
        if (alteration.equals("fake_tuple")) {
            provenance.put("test_level","component"); ((ObjectNode)result.path("product_test")).put("test_level","component");
        }
        json.writeValue(provenancePath.toFile(),provenance);
        ((ObjectNode)result.path("product_test")).put("provenance_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(provenancePath))));
        json.writeValue(run.resultJson().toFile(),result);
        assertThat(new EvidenceHardeningService().validateResult(run.resultJson()).valid()).isFalse();
    }

    String serve(int status,String body) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/items", exchange -> {
            calls.incrementAndGet(); byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            if (status==302) exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/followed");
            boolean head=exchange.getRequestMethod().equals("HEAD");
            exchange.sendResponseHeaders(status,head ? -1 : bytes.length);
            try { if (!head) exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        });
        server.createContext("/followed", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200,-1); exchange.close(); });
        server.start(); return "http://127.0.0.1:"+server.getAddress().getPort();
    }

    Path fixture(String origin,String method,boolean bodyOracle) throws Exception {
        Path suite=Files.createTempDirectory(temporary.toRealPath(),"reviewed-http-");
        Files.createDirectories(suite.resolve("env_profiles")); Files.createDirectories(suite.resolve("test_cases"));
        Files.createDirectories(suite.resolve("contracts")); Files.createDirectories(suite.resolve("expected")); Files.createDirectories(suite.resolve("handoff"));
        // Preserve the contract's values while making every reviewed YAML value explicit (no aliases).
        Object contract = yaml.load(Files.readString(Path.of("docs/02-architecture/contracts/provider-contracts/rest_client_v0_3.yaml")));
        Files.writeString(suite.resolve("contracts/rest_client_v0_3.yaml"),json.writeValueAsString(contract));
        Files.writeString(suite.resolve("suite_manifest.yaml"),"""
                manifest_version: v0.3
                suite_id: PUBLIC-HTTP-LOCAL
                default_profile: local_native
                artifact_roots:
                  expected: expected/
                targets:
                  todo_api:
                    provider_contract: rest_client.v0.3
                env_profiles:
                  local_native:
                    ref: env_profiles/local_native.yaml
                tests:
                  - ref: test_cases/items.yaml
                """);
        Files.writeString(suite.resolve("env_profiles/local_native.yaml"),"""
                profile_id: local_native
                execution_mode: local
                isolation: per_run
                evidence_classification: framework_verification_only
                downstream_release_evidence: false
                targets:
                  todo_api:
                    runtime_mode: native
                    bindings:
                      base_url: %s
                product_test:
                  contract_version: product-test.v1
                  test_level: integration
                  test_type: regression
                  environment_stage: local
                  knowledge_mode: not_used
                  provider_modes: {todo_api: real}
                  dependency_modes: {}
                  reviewed_handoff_ref: handoff/reviewed.json
                """.formatted(origin));
        String test="""
                dsl_version: v0.3
                test_case_id: TC-HTTP
                title: Reviewed native HTTP fixture
                execute:
                  - id: read_items
                    target: todo_api
                    op: http_request
                    with:
                      request.method: %s
                      request.path: /api/items
                      request.timeout: PT2S
                verify:
                  - id: status_ok
                    type: assertion
                    assert:
                      actual: step://read_items/response.status
                      operator: equals
                      expected: 200
                """.formatted(method);
        if (bodyOracle) test += """
                  - id: exact_items
                    type: assertion
                    assert:
                      actual: step://read_items/response.body
                      operator: json_match
                      expected_ref: artifact://expected/items.json
                """;
        Files.writeString(suite.resolve("test_cases/items.yaml"),test);
        Files.writeString(suite.resolve("expected/items.json"),BODY);
        Map<String,Object> review=json.readValue(Path.of("samples/41-product-testing/reviewed_local/review-input.json").toFile(),Map.class);
        review.remove("suite_id"); review.remove("profile");
        List<Map<String,Object>> files=new ArrayList<>(List.of(Map.of("path","suite_manifest.yaml","role","manifest"),Map.of("path","env_profiles/local_native.yaml","role","profile"),Map.of("path","test_cases/items.yaml","role","test")));
        if (bodyOracle) files.add(Map.of("path","expected/items.json","role","oracle"));
        review.put("reviewed_files",files);
        review.put("reviewed_contracts",List.of(Map.of("path","contracts/rest_client_v0_3.yaml","provider_contract","rest_client.v0.3")));
        review.put("requirement_mapping",List.of(Map.of("requirement_ref","synthetic://requirement/http-items","test_case_ids",List.of("TC-HTTP"),
                "oracle_refs",bodyOracle ? List.of("test_cases/items.yaml#status_ok","expected/items.json") : List.of("test_cases/items.yaml#status_ok"),"review_ref",review.get("review_ref"))));
        json.writeValue(suite.resolve("review-input.json").toFile(),review); return suite;
    }
    void prepare(Path suite) { new ProductTestHandoffService().prepare(suite.resolve("suite_manifest.yaml"),"local_native",suite.resolve("review-input.json"),null,suite.resolve("handoff/reviewed.json")); }
    V03RuntimeExecutionService.V03RuntimeRunResult run(Path suite) throws Exception {
        assertDoesNotThrow(() -> prepare(suite));
        return new V03RuntimeExecutionService().runReviewed(suite.resolve("suite_manifest.yaml"),"local_native",
                SuiteExecutionContext.standalone("local_native",temporary.toRealPath().resolve("runs"),"V03"),
                new InvocationRequirement(Files.readString(suite.resolve("handoff/reviewed.json.sha256")).trim()));
    }
    void assertValid(V03RuntimeExecutionService.V03RuntimeRunResult run) {
        var checked=new EvidenceHardeningService().validateResult(run.resultJson()); assertThat(checked.valid()).as(checked.findings().toString()).isTrue();
    }
    Map<String,Object> read(Path path) throws Exception { return map(yaml.load(Files.readString(path))); }
    void write(Path path,Map<String,Object> value) throws Exception { Files.writeString(path,yaml.dump(value)); }
    @SuppressWarnings("unchecked") Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
}

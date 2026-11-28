package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.build.RunnerBuildVariant;
import com.specdriven.regression.contract.ContractBaselineService;
import com.specdriven.regression.contract.v03.ref.V03ReferenceResolver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;

/** Compares independent registrations with authored contracts, production dispatch and observed suite results. */
class ProviderConformanceTest {
    private static final Path SOURCES = Path.of("docs/02-architecture/contracts");
    private static final Path CONTRACTS = SOURCES.resolve("provider-contracts");
    private static final Path SAMPLES = Path.of("samples/20-provider-capability-p0");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> PHASES = List.of("setup", "execute", "verify", "cleanup");
    @TempDir Path temp;

    record Fixture(String contract, String proof, String suite, List<String> operations, String boundaryTest) {}

    private static List<Fixture> registrations() throws IOException {
        return Arrays.asList(JSON.readValue(Path.of("src/test/resources/provider-conformance.json").toFile(), Fixture[].class));
    }

    @Test
    void allPublicContractsAgreeWithTheActualDispatcherAndTestRegistrations() throws Exception {
        var catalog = new V03ProviderContractCatalog().loadDirectory(CONTRACTS);
        var registry = V03RuntimeExecutionService.defaultRuntimeRegistry();
        var entries = registrations();
        var byId = entries.stream().collect(Collectors.toMap(Fixture::contract, entry -> entry));
        Map<String, Object> types = map(yaml(SOURCES.resolve("provider_capability_registry.v0.2.yaml")).get("provider_types"));
        var publicTypes = new HashSet<String>();
        for (var type : types.entrySet()) {
            Object status = map(type.getValue()).get("support_status");
            assertThat(status).as(type.getKey()).isIn("supported", "deprecated", "unsupported", "contract_only");
            if ("supported".equals(status)) publicTypes.add(type.getKey());
        }
        var expected = catalog.values().stream().filter(c -> publicTypes.contains(c.providerType())).toList();
        assertThat(expected.stream().map(V03ProviderContract::providerType)).containsExactlyInAnyOrderElementsOf(publicTypes);
        assertThat(byId.keySet()).containsExactlyInAnyOrderElementsOf(expected.stream().map(V03ProviderContract::id).toList());
        var registered = entries.stream().filter(f -> !f.proof().equals("assertions"))
                .map(f -> catalog.get(f.contract()).providerType()).collect(Collectors.toSet());
        registered.addAll(Set.of("sample_fake_provider", "grpc_descriptor_admission"));
        assertThat(registry.providerTypes()).as("production registrations, including reviewed/internal routes")
                .containsExactlyInAnyOrderElementsOf(registered);
        assertThat(map(types.get("sample_fake_provider")).get("support_status")).isEqualTo("unsupported");
        assertThat(map(types.get("grpc_descriptor_admission")).get("support_status")).isEqualTo("contract_only");
        assertThatThrownBy(() -> registry.resolve("grpc_descriptor_admission").execute(null, null))
                .hasMessageContaining("requires a reviewed handoff");
        for (var contract : catalog.values()) {
            var type = contract.providerType();
            assertThat(types).containsKey(type);
            Fixture fixture = byId.get(contract.id());
            if (fixture == null) continue;
            assertThat(fixture.boundaryTest()).as("%s boundary case", type).isNotBlank();
            assertThat(fixture.proof()).isIn("native", "portable", "assertions");
            var source = yaml(CONTRACTS.resolve(type + "_v0_3.yaml"));
            var ops = new HashSet<>(contract.operations().keySet());
            var declarations = map(source.get("operations"));
            for (var op : declarations.entrySet()) {
                var fields = map(op.getValue());
                if (!fields.containsKey("execution_status")) continue;
                assertThat(fields.get("execution_status")).isEqualTo("contract_only");
                assertThat(String.valueOf(fields.getOrDefault("execution_reason", ""))).isNotBlank();
                assertThat(registry.resolve(type).supports(contract.id(), op.getKey()))
                        .as("%s/%s is reserved", type, op.getKey()).isFalse();
                ops.remove(op.getKey());
            }
            assertThat(fixture.operations()).as("%s operation registration", type).doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(ops);
            boolean frameworkAssertions = "framework_verifier".equals(source.get("provider_role"));
            assertThat("assertions".equals(fixture.proof())).as(type).isEqualTo(frameworkAssertions);
            if (frameworkAssertions || omittedMq(contract.id())) {
                assertThatThrownBy(() -> registry.resolve(type)).isInstanceOf(IllegalArgumentException.class);
                continue;
            }
            var adapter = registry.resolve(type);
            assertThat(adapter.providerType()).isEqualTo(type);
            for (var op : ops) {
                assertThat(adapter.supports(contract.id(), op)).as("%s/%s", type, op).isTrue();
                assertThat(adapter.supports("another.v0.3", op)).isFalse();
            }
            assertThat(adapter.supports(contract.id(), "not_a_public_operation")).isFalse();
        }
    }

    @Test
    void eachPortableRegistrationRunsItsPublicOperationsThroughTheProductionRunner() throws Exception {
        Map<String, List<Fixture>> suites = new LinkedHashMap<>();
        for (var fixture : registrations()) {
            if (!fixture.proof().equals("native") && !omittedMq(fixture.contract())) {
                suites.computeIfAbsent(fixture.suite(), ignored -> new ArrayList<>()).add(fixture);
            }
        }
        for (var suite : suites.entrySet()) {
            Path root = copySuite(suite.getKey());
            addMissingOperations(root, suite.getKey());
            var run = new V03RuntimeExecutionService().run(root.resolve("suite_manifest.yaml"), "local_v03", temp.resolve("runs"));
            assertThat(run.passed()).as("%s %s", suite.getKey(), run.findings()).withFailMessage(
                    () -> suite.getKey() + " " + (run.resultJson() == null ? run.findings() : readFailure(run.resultJson()))).isTrue();
            var result = JSON.readTree(run.resultJson().toFile());
            for (var evidence : result.path("evidence_refs")) {
                Path file = run.evidenceDir().resolve(evidence.asText()).normalize();
                assertThat(file).startsWith(run.evidenceDir()).isRegularFile();
            }
            for (var fixture : suite.getValue()) {
                Set<String> observed = new HashSet<>();
                if (fixture.proof().equals("assertions")) {
                    assertThat(run.providerRuntimeExecuted()).as(fixture.contract()).isFalse();
                    for (var assertion : result.path("verify_results")) {
                        assertThat(assertion.path("status").asText()).isEqualTo("passed");
                        assertThat(run.evidenceDir().resolve(assertion.path("evidence_ref").asText())).isRegularFile();
                        observed.add(assertion.path("type").asText());
                    }
                } else {
                    assertThat(run.providerRuntimeExecuted()).isTrue();
                    for (var provider : result.path("provider_results")) {
                        if (!fixture.contract().equals(provider.path("provider_contract").asText())) continue;
                        for (var step : result.path("step_results")) {
                            if (!step.path("target").equals(provider.path("target"))) continue;
                            assertThat(step.path("status").asText()).isEqualTo("passed");
                            assertThat(step.path("outputs").size()).as(step.path("operation").asText()).isPositive();
                            observed.add(step.path("operation").asText());
                        }
                    }
                }
                assertThat(observed).as("%s observed successful operations", fixture.contract())
                        .containsExactlyInAnyOrderElementsOf(fixture.operations());
                String receipts = System.getenv("PROVIDER_CONFORMANCE_RECEIPTS");
                if (receipts != null && !receipts.isBlank()) {
                    Path directory = Files.createDirectories(Path.of(receipts).resolve("portable"));
                    var scenario = Map.of("suite", run.suiteId(), "profile", run.profile(), "run", run.runId());
                    var env = System.getenv();
                    var receipt = Map.of("contract", fixture.contract(), "proof", fixture.proof(),
                            "platform", System.getProperty("os.name"), "operations", observed.stream().sorted().toList(),
                            "scenarios", List.of(scenario), "revision", env.getOrDefault("GITHUB_SHA", "local"),
                            "job_run", env.getOrDefault("GITHUB_RUN_ID", "local"),
                            "job_attempt", env.getOrDefault("GITHUB_RUN_ATTEMPT", "local"));
                    JSON.writeValue(directory.resolve(fixture.contract() + ".json").toFile(), receipt);
                }
            }
        }
    }

    @Test
    void rejectsForeignModesBindingsAndInputsBeforeProviderDispatch() throws Exception {
        for (var fixture : registrations()) {
            if (omittedMq(fixture.contract())) continue;
            var root = copySuite(fixture.suite());
            var manifest = root.resolve("suite_manifest.yaml");
            var document = yaml(manifest);
            var profile = (String) document.get("default_profile");
            var target = map(document.get("targets")).entrySet().stream()
                    .filter(entry -> fixture.contract().equals(map(entry.getValue()).get("provider_contract")))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            var envPath = root.resolve((String) map(map(document.get("env_profiles")).get(profile)).get("ref"));
            var env = yaml(envPath);
            var binding = map(map(env.get("targets")).get(target));
            if (fixture.proof().equals("assertions")) {
                // These suites have no provider steps. The typed compiler checks their environment.
                Object mode = binding.put("runtime_mode", "unregistered_mode");
                writeYaml(envPath, env);
                assertThatThrownBy(() -> new V03ExecutionPlanBuilder().build(manifest, profile))
                        .hasMessageContaining("unsupported_runtime_mode");
                binding.put("runtime_mode", mode);
                map(binding.get("bindings")).put("unregistered_binding", "forbidden");
                writeYaml(envPath, env);
                assertThatThrownBy(() -> new V03ExecutionPlanBuilder().build(manifest, profile))
                        .hasMessageContaining("unknown_binding_key");
                continue;
            }
            binding.put("runtime_mode", "unregistered_mode");
            map(binding.get("bindings")).put("unregistered_binding", "forbidden");
            writeYaml(envPath, env);
            var casePath = testCase(root, document);
            var test = yaml(casePath);
            Map<String, Object> chosen = PHASES.stream().flatMap(phase -> steps(test, phase).stream())
                    .filter(step -> target.equals(step.get("target")) && step.containsKey("op"))
                    .findFirst().orElseThrow();
            map(chosen.computeIfAbsent("with", ignored -> new LinkedHashMap<>())).put("unregistered_input", "forbidden");
            writeYaml(casePath, test);
            var checked = new ContractBaselineService().validateSuite(manifest);
            var findings = checked.findings().stream().filter(f -> f.providerType().equals(fixture.contract().split("[.]")[0]))
                    .map(f -> f.reason()).toList();
            assertThat(findings).as("%s: %s", fixture.contract(), checked.findings())
                    .contains("unsupported_runtime_mode", "unknown_binding_key", "unsupported_input");
            assertThat(checked.valid()).isFalse();
            var denied = new V03RuntimeExecutionService().run(manifest, profile, temp.resolve("denied"));
            assertThat(denied.providerRuntimeExecuted()).isFalse();
            assertThat(denied.resultJson()).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "http/rest_client_with_wiremock,http_mock,base_url",
        "rpc/soap_mock,soap_mock,endpoint_url",
        "rpc/grpc_mock,grpc_mock,target_uri"
    })
    void releasesMocksAfterAFailedSuiteWithoutAnExplicitCleanup(String suite, String type, String endpoint) throws Exception {
        var root = copySuite(suite);
        var manifest = root.resolve("suite_manifest.yaml");
        var path = testCase(root, yaml(manifest));
        var test = yaml(path);
        test.put("cleanup", List.of());
        steps(test, "verify").add(Map.of("id", "force_failure", "type", "assertion",
                "assert", Map.of("actual", "left", "operator", "equals", "expected", "right")));
        writeYaml(path, test);
        var run = new V03RuntimeExecutionService().run(manifest, "local_v03", temp.resolve("aborted"));
        assertThat(run.providerRuntimeExecuted()).as("%s: %s", type, run.findings()).isTrue();
        var result = JSON.readTree(run.resultJson().toFile());
        assertThat(result.at("/failure/code").asText()).isEqualTo("ASSERTION_FAILED");
        var step = result.path("steps").get(0);
        assertThat(step.path("status").asText()).isEqualTo("passed");
        int port = port(step.path("outputs").path(endpoint).asText());
        assertThatThrownBy(() -> probe(port)).isInstanceOf(java.net.ConnectException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "http/rest_client_with_wiremock,http_mock,base_url",
        "rpc/soap_mock,soap_mock,endpoint_url",
        "rpc/grpc_mock,grpc_mock,target_uri"
    })
    void isolatesMocksAcrossRunsCasesAndTargets(String suite, String type, String endpoint) throws Exception {
        var plan = new V03ExecutionPlanBuilder().build(SAMPLES.resolve(suite).resolve("suite_manifest.yaml"), "local_v03");
        var setup = plan.steps().get(0);
        var originalTarget = plan.targets().get(setup.target());
        var secondTarget = new V03ResolvedTarget("another", originalTarget.providerContract(), type,
                originalTarget.profile(), originalTarget.runtimeMode(), originalTarget.bindings());
        var targets = Map.of(setup.target(), originalTarget, secondTarget.target(), secondTarget);
        var first = context(plan, targets, temp.resolve("one"));
        var second = context(plan, targets, temp.resolve("two"));
        var adapter = V03RuntimeExecutionService.defaultRuntimeRegistry().resolve(type);
        try {
            int a = open(adapter, first, setup, "case-one", setup.target(), endpoint);
            int b = open(adapter, first, setup, "case-two", setup.target(), endpoint);
            int c = open(adapter, first, setup, "case-one", secondTarget.target(), endpoint);
            int d = open(adapter, second, setup, "case-one", setup.target(), endpoint);
            assertThat(List.of(a, b, c, d)).doesNotHaveDuplicates();
            adapter.endRun(first);
            adapter.endRun(first);
            for (int port : List.of(a, b, c)) {
                assertThatThrownBy(() -> probe(port)).isInstanceOf(java.net.ConnectException.class);
            }
            probe(d);
        } finally {
            try { adapter.endRun(first); } finally { adapter.endRun(second); }
        }
    }

    private static int open(V03ProviderRuntimeAdapter adapter, V03ExecutionContext context, V03ExecutionStep setup,
                            String test, String target, String output) {
        var step = new V03ExecutionStep(test, setup.kind(), setup.phase(), setup.id(), target, setup.providerContract(),
                setup.providerType(), setup.profile(), setup.runtimeMode(), setup.operation(), setup.inputs(), "");
        var result = adapter.execute(step, context);
        assertThat(result.passed()).as(result.message()).isTrue();
        return port((String) result.outputs().get(output));
    }

    private static V03ExecutionContext context(V03ExecutionPlan plan, Map<String,V03ResolvedTarget> targets, Path path)
            throws IOException {
        return new V03ExecutionContext(plan.suiteRoot(), Files.createDirectories(path), plan.profile(), targets,
                plan.artifactRoots(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(), new V03ReferenceResolver(), Instant.now());
    }

    private static int port(String endpoint) {
        return URI.create(endpoint.contains("://") ? endpoint : "http://" + endpoint).getPort();
    }

    private static void probe(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
        }
    }

    private static String readFailure(Path result) {
        try { return JSON.readTree(result.toFile()).path("failure").toString(); }
        catch (IOException error) { throw new AssertionError(error); }
    }

    private Path copySuite(String suite) throws IOException {
        Path root = Files.createTempDirectory(temp, "suite");
        Path source = SAMPLES.resolve(suite);
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var destination = root.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        return root;
    }

    private void addMissingOperations(Path root, String suite) throws IOException {
        Path path = testCase(root, yaml(root.resolve("suite_manifest.yaml")));
        Map<String, Object> test = yaml(path);
        var verify = steps(test, "verify");
        switch (suite) {
            case "http/rest_client_with_wiremock" -> {
                Files.writeString(root.resolve("fixtures/filter.json"), "{\"method\":\"POST\",\"url\":\"/payments\"}");
                check(verify, "payment_mock", "verify_requests", Map.of("mock.request_filter_ref", "artifact://fixtures/filter.json",
                        "mock.expected_count", 1), "matched_count", 1);
            }
            case "data/jdbc" -> check(verify, "order_db", "db_record_exists", Map.of(
                    "query_ref", "artifact://queries/find_order_by_id.sql", "params.order_id", "ORD-V03-001",
                    "expected.min_rows", 1), "matched", true);
            case "messaging/kafka" -> check(verify, "order_events", "kafka_observe", Map.of(
                    "key", "ORD-KAFKA-V03-001", "consume_from", "earliest", "timeout", "PT1S"), "matched", true);
            case "messaging/ibm_mq" -> {
                var filter = Map.<String,Object>of("correlation_id", "CORR-IBM-MQ-V03-001", "timeout", "PT1S");
                check(verify, "payment_queue", "mq_browse", filter, "browse_count", 1);
                check(verify, "payment_queue", "mq_message_exists", filter, "matched", true);
            }
            case "verification/polling_observer" -> {
                var files = Map.<String,Object>of("actual_ref", "artifact://actual_samples/observed_event.json",
                        "expected_ref", "artifact://expected_results/expected_event.json");
                check(verify, "polling_observer", "observe_condition", files, "matched", true);
                check(verify, "polling_observer", "db_record_exists", files, "matched", true);
            }
            default -> { return; }
        }
        writeYaml(path, test);
    }

    private static void check(List<Map<String, Object>> verify, String target, String op, Map<String, Object> inputs,
                              String output, Object expected) {
        verify.add(Map.of("id", op, "type", "provider_check", "op", op, "target", target, "with", inputs));
        verify.add(Map.of("id", op + "_result", "type", "assertion", "assert",
                Map.of("actual", "step://" + op + "/" + output, "operator", "equals", "expected", expected)));
    }

    private static Path testCase(Path root, Map<String,Object> manifest) {
        return root.resolve((String) steps(manifest, "tests").get(0).get("ref"));
    }

    private static boolean omittedMq(String contract) {
        return contract.equals("ibm_mq.v0.3") && !RunnerBuildVariant.ibmMqAvailable();
    }

    private static Map<String,Object> yaml(Path path) throws IOException {
        return new Yaml().load(Files.readString(path));
    }

    private static void writeYaml(Path path, Map<String,Object> value) throws IOException {
        Files.writeString(path, new Yaml().dump(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) { return (Map<String,Object>) value; }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> steps(Map<String,Object> document, String field) {
        return (List<Map<String,Object>>) document.getOrDefault(field, List.of());
    }
}

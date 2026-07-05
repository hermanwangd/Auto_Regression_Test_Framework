package com.specdriven.regression.provider.wiremock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.specdriven.regression.cli.RegressionCommand;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WireMockExternalBaseUrlSupportTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Path REST_WIREMOCK_SUITE =
            Path.of("samples/provider_capability/mock_server_cross_verify/rest_wiremock_http");

    @TempDir
    Path tempDir;

    @Test
    void consumesProjectProvidedWireMockBaseUrlWithoutStartingFrameworkManagedWireMock() throws Exception {
        Path suite = mutableRestWireMockSuite();
        WireMockServer externalWireMock = new WireMockServer(0);
        externalWireMock.start();
        try {
            externalWireMock.stubFor(post(urlEqualTo("/payments"))
                    .withRequestBody(equalToJson(Files.readString(suite.getParent().resolve("fixtures/payment_request.json"))))
                    .willReturn(aResponse()
                            .withStatus(201)
                            .withHeader("Content-Type", "application/json")
                            .withBody(Files.readString(suite.getParent().resolve("expected_results/payment_response.json")))));
            writeExternalProfile(suite.getParent(), externalWireMock.baseUrl());

            CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock_external");

            assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
            assertThat(run.stdout())
                    .contains("run_status: passed")
                    .contains("provider_runtime_executed: true");
            externalWireMock.verify(1, postRequestedFor(urlEqualTo("/payments")));
            Path resultJson = extractPath(run.stdout(), "result_json");
            Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
            JsonNode result = OBJECT_MAPPER.readTree(resultJson.toFile());
            JsonNode wireMockProviderResult = providerResult(result, "wiremock-payment-api");
            assertThat(wireMockProviderResult.at("/resolved_operation_result/operation").asText())
                    .isEqualTo("connect_mock");
            assertThat(Files.readString(resultJson))
                    .contains("\"framework_started_wiremock\": false")
                    .contains("\"external_base_url_consumed\": true")
                    .contains("\"request_url\": \"" + externalWireMock.baseUrl() + "/payments\"");
            assertThat(Files.readString(evidenceDir.resolve("provider-evidence/wiremock/server_log.txt")))
                    .contains("framework_started_wiremock: false")
                    .contains("external_base_url_consumed: true")
                    .contains("base_url: " + externalWireMock.baseUrl());
        } finally {
            externalWireMock.stop();
        }
    }

    @Test
    void malformedExternalWireMockBaseUrlIsBlockedBeforeProviderDispatch() throws Exception {
        Path suite = mutableRestWireMockSuite();
        writeExternalProfile(suite.getParent(), "ftp://127.0.0.1:8080");

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock_external");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: blocked")
                .contains("provider_runtime_invoked: false")
                .contains("failure_code: WIREMOCK_EXTERNAL_BASE_URL_INVALID")
                .contains("category: CONFIGURATION_ERROR");
    }

    @Test
    void missingExternalWireMockBaseUrlIsBlockedBeforeProviderDispatch() throws Exception {
        Path suite = mutableRestWireMockSuite();
        writeExternalProfileWithoutBaseUrl(suite.getParent());

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock_external");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: blocked")
                .contains("provider_runtime_invoked: false")
                .contains("failure_code: WIREMOCK_EXTERNAL_BASE_URL_MISSING")
                .contains("category: CONFIGURATION_ERROR");
    }

    @Test
    void secretBearingExternalWireMockBaseUrlIsBlockedBeforeProviderDispatch() throws Exception {
        Path suite = mutableRestWireMockSuite();
        writeExternalProfile(suite.getParent(), "http://user:password@127.0.0.1:8080/payments?token=abc");

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock_external");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: blocked")
                .contains("provider_runtime_invoked: false")
                .contains("failure_code: WIREMOCK_EXTERNAL_BASE_URL_SECRET_LEAK")
                .contains("category: SECRET_GUARDRAIL_ERROR");
    }

    private Path mutableRestWireMockSuite() throws IOException {
        Path target = tempDir.resolve("rest_wiremock_external");
        copyDirectory(REST_WIREMOCK_SUITE, target);
        Path manifest = target.resolve("suite_manifest.yaml");
        Files.writeString(manifest, Files.readString(manifest)
                .replace("profile: local_wiremock_http", "profile: local_wiremock_external")
                .replace("""
                        tests:
                          - test_case.yaml
                          - test_case_boundary.yaml""", """
                        tests:
                          - test_case.yaml"""));
        Path testCase = target.resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase)
                .replace("compatible_profiles: [local_wiremock_http]",
                        "compatible_profiles: [local_wiremock_external]"));
        return manifest;
    }

    private void writeExternalProfile(Path suiteRoot, String baseUrl) throws IOException {
        Files.writeString(suiteRoot.resolve("env_profiles/local_wiremock_external.yaml"), """
                env_profile_id: local_wiremock_external
                execution_mode: local
                isolation_scope: per_run
                dependency_policy:
                  require_readiness_evidence: false
                  allow_framework_managed_dependencies: false
                dependency_substitution_policy:
                  allowed_runtime_modes: [mock, native]
                  mock_evidence_release_claim: prohibited
                dependency_provisioning_policy:
                  allowed_provisioners: [project_provided]
                data_policy:
                  approved_expected_results_required: true
                  production_data_allowed: false
                  generated_data_allowed: true
                  secrets_must_use_refs: true
                providers:
                  wiremock-payment-api:
                    runtime_mode: mock
                    binding_keys:
                      base_url:
                        value: %s
                  payment-api-client:
                    runtime_mode: native
                    binding_keys:
                      base_url:
                        generated_ref: generated://wiremock-payment-api.base_url
                """.formatted(baseUrl));
    }

    private void writeExternalProfileWithoutBaseUrl(Path suiteRoot) throws IOException {
        Files.writeString(suiteRoot.resolve("env_profiles/local_wiremock_external.yaml"), """
                env_profile_id: local_wiremock_external
                execution_mode: local
                isolation_scope: per_run
                dependency_policy:
                  require_readiness_evidence: false
                  allow_framework_managed_dependencies: false
                dependency_substitution_policy:
                  allowed_runtime_modes: [mock, native]
                  mock_evidence_release_claim: prohibited
                dependency_provisioning_policy:
                  allowed_provisioners: [project_provided]
                data_policy:
                  approved_expected_results_required: true
                  production_data_allowed: false
                  generated_data_allowed: true
                  secrets_must_use_refs: true
                providers:
                  wiremock-payment-api:
                    runtime_mode: mock
                    binding_keys: {}
                  payment-api-client:
                    runtime_mode: native
                    binding_keys:
                      base_url:
                        generated_ref: generated://wiremock-payment-api.base_url
                """);
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private Path extractPath(String stdout, String key) {
        return stdout.lines()
                .filter(line -> line.startsWith(key + ": "))
                .map(line -> line.substring((key + ": ").length()).trim())
                .map(Path::of)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing path line for " + key + " in:\n" + stdout));
    }

    private JsonNode providerResult(JsonNode result, String providerId) {
        for (JsonNode providerResult : result.path("provider_results")) {
            if (providerId.equals(providerResult.path("provider_id").asText())) {
                return providerResult;
            }
        }
        throw new AssertionError("Missing provider result for " + providerId + " in:\n" + result);
    }

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, print(stdout), print(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private PrintStream print(ByteArrayOutputStream stream) {
        return new PrintStream(stream);
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}

package com.specdriven.regression.provider.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

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

class RestClientExternalEndpointSupportTest {

    private static final Path EXTERNAL_REST_SUITE =
            Path.of("samples/20-provider-capability-p0/http/rest_client_external");

    @TempDir
    Path tempDir;

    @Test
    void consumesProjectProvidedExternalBaseUrlThroughV03ExternalSuite() throws Exception {
        Path suite = mutableExternalRestSuite();
        WireMockServer externalHttpEndpoint = new WireMockServer(0);
        externalHttpEndpoint.start();
        try {
            externalHttpEndpoint.stubFor(get(urlEqualTo("/ready"))
                    .withHeader("X-Regression-Trace", equalTo("rest-client-external-v03"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(Files.readString(suite.getParent()
                                    .resolve("expected_results/ready_response.json")))));
            Path envProfile = suite.getParent().resolve("env_profiles/external_native.yaml");
            Files.writeString(envProfile, Files.readString(envProfile)
                    .replace("base_url: env://REST_BASE_URL", "base_url: " + externalHttpEndpoint.baseUrl()));

            CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "external_native");

            assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
            assertThat(run.stdout())
                    .contains("run_status: passed")
                    .contains("provider_runtime_executed: true")
                    .contains("provider_type: rest_client")
                    .contains("profile: external_native")
                    .doesNotContain("provider_type: http_mock")
                    .doesNotContain("provider_type: wiremock_http_mock");
            Path resultJson = extractPath(run.stdout(), "result_json");
            Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
            assertThat(Files.readString(resultJson))
                    .contains("\"provider_type\": \"rest_client\"")
                    .contains("\"profile\": \"external_native\"")
                    .contains("\"runtime_mode\": \"native\"")
                    .contains("\"request_url\": \"[MASKED]\"")
                    .doesNotContain("wiremock_http_mock");
            assertThat(Files.readString(evidenceDir.resolve("provider-evidence/http/request.json")))
                    .contains("\"request_url\":\"" + externalHttpEndpoint.baseUrl() + "/ready\"")
                    .contains("rest-client-external-v03");
            assertThat(externalHttpEndpoint.getServeEvents().getRequests()).hasSize(1);
        } finally {
            externalHttpEndpoint.stop();
        }
    }

    @Test
    void blocksExternalV03SuiteBeforeDispatchWhenBaseUrlIsMissing() throws Exception {
        Path suite = mutableExternalRestSuite();
        Path envProfile = suite.getParent().resolve("env_profiles/external_native.yaml");
        String missingEnv = "REGRESS_TEST_MISSING_REST_BASE_URL";
        Files.writeString(envProfile, Files.readString(envProfile)
                .replace("env://REST_BASE_URL", "env://" + missingEnv));

        CommandResult dryRun = execute("run", "--suite", suite.toString(), "--profile", "external_native", "--dry-run");

        assertThat(dryRun.exit()).isEqualTo(1);
        assertThat(dryRun.stdout())
                .contains("run_status: blocked")
                .contains("missing_environment_value")
                .contains(missingEnv)
                .doesNotContain("provider_runtime_invoked: true");
    }

    private Path mutableExternalRestSuite() throws IOException {
        Path target = tempDir.resolve("rest_client_external");
        copyDirectory(EXTERNAL_REST_SUITE, target);
        return target.resolve("suite_manifest.yaml");
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

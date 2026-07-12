package com.specdriven.regression.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ReleaseUsageKitVerificationTest {

    @Test
    void releaseWorkflowRunsSupportedProviderSampleGateWithExternalMessagingOptionalByDefault() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/release.yml"));

        assertThat(workflow)
                .contains("Verify supported provider samples")
                .contains("scripts/release/verify-supported-provider-samples.sh")
                .contains("REQUIRE_EXTERNAL_MESSAGING: ${{ vars.REQUIRE_EXTERNAL_MESSAGING || 'false' }}")
                .contains("REQUIRE_EXTERNAL_JDBC: ${{ vars.REQUIRE_EXTERNAL_JDBC || 'false' }}")
                .contains("JDBC_EXTERNAL_DIALECT: ${{ vars.JDBC_EXTERNAL_DIALECT || 'oracle' }}")
                .contains("JDBC_CONNECTION: ${{ secrets.JDBC_CONNECTION }}")
                .contains("NATS_CONNECTION: ${{ secrets.NATS_CONNECTION }}")
                .contains("KAFKA_BOOTSTRAP_SERVERS: ${{ secrets.KAFKA_BOOTSTRAP_SERVERS }}")
                .contains("IBM_MQ_CONN_NAME: ${{ secrets.IBM_MQ_CONN_NAME }}")
                .contains("IBM_MQ_CREDENTIAL: ${{ secrets.IBM_MQ_CREDENTIAL }}");
    }

    @Test
    void releaseWorkflowRunsV03RuntimeSampleGate() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/release.yml"));

        assertThat(workflow)
                .contains("Verify v0.3 runtime samples")
                .contains("REGRESS_JAR: target/spec-driven-auto-regression-${{ steps.version.outputs.version }}.jar")
                .contains("scripts/release/verify-v0-3-runtime-samples.sh");
    }

    @Test
    void v03RuntimeGateRejectsExternalProfileFallbackWithoutNativeInfrastructure() throws Exception {
        String script = Files.readString(Path.of("scripts/release/verify-v0-3-runtime-samples.sh"));

        assertThat(script)
                .contains("verify_external_profile_plan")
                .contains("external_nats")
                .contains("external_kafka")
                .contains("external_ibm_mq")
                .contains("runtime_mode: native")
                .contains("Explicit profile fell back to local/mock")
                .contains("contract-validation.invalid");
    }

    @Test
    void supportedProviderSampleGateUsesOnlyCanonicalFrameworkCommands() throws Exception {
        String script = Files.readString(Path.of("scripts/release/verify-supported-provider-samples.sh"));

        assertThat(script)
                .contains("validate --suite")
                .contains("run --suite")
                .contains("report --result")
                .contains("validate-evidence --result")
                .contains("samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml")
                .contains("JDBC_EXTERNAL_DIALECT")
                .contains("validate_external_profile")
                .contains("contract-validation.invalid")
                .contains("samples/20-provider-capability-p0/messaging/nats/suite_manifest.yaml external_nats")
                .contains("NATS_CONNECTION")
                .contains("samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml external_kafka")
                .contains("samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml external_ibm_mq")
                .doesNotContain("--rp-id");
    }

    @Test
    void buildUsageKitCreatesJdbcDriverPlaceholdersWithoutBundlingVendorDrivers() throws Exception {
        String script = Files.readString(Path.of("scripts/release/build-usage-kit.sh"));

        assertThat(script)
                .contains("drivers/README.md")
                .contains("drivers/oracle/put-ojdbc-here.txt")
                .contains("drivers/db2/put-db2-jcc-here.txt")
                .contains("Oracle and DB2 vendor JDBC drivers are not bundled")
                .contains("--driver-path")
                .contains("--driver-dir")
                .contains("REGRESS_DRIVER_PATH")
                .contains("default_sample_roots:")
                .contains("old_path: samples/v0_3_dsl/negative/target_resolution/unknown_target")
                .doesNotContain("ojdbc11.jar\" \"${KIT_ROOT}")
                .doesNotContain("jcc.jar\" \"${KIT_ROOT}");
    }

    @Test
    void usageKitVerifierChecksDefaultRootsAndNegativePathRewrites() throws Exception {
        String script = Files.readString(Path.of("scripts/release/verify-usage-kit.sh"));

        assertThat(script)
                .contains("default_sample_roots")
                .contains("old_path: samples/v0_3_dsl/negative/target_resolution/unknown_target")
                .contains("Usage kit manifest is missing negative sample path rewrites")
                .contains("Usage kit deprecated path guide is missing negative sample rewrites");
    }

    @Test
    void usageKitDocumentsProviderContractCatalogEntryPoint() throws Exception {
        String buildScript = Files.readString(Path.of("scripts/release/build-usage-kit.sh"));
        String verifyScript = Files.readString(Path.of("scripts/release/verify-usage-kit.sh"));

        assertThat(buildScript)
                .contains("Provider Contract Catalog")
                .contains("docs/02-architecture/contracts/provider-contracts/README.md")
                .contains("jdbc.v0.3")
                .contains("rest_client.v0.3");
        assertThat(verifyScript)
                .contains("usage-kit/docs/02-architecture/contracts/provider-contracts/README.md")
                .contains("Usage kit README is missing Provider Contract catalog guidance")
                .contains("Usage kit samples README is missing Provider Contract catalog guidance")
                .contains("Usage kit Provider Contract catalog is missing v0.3 contract examples");
    }

    @Test
    void wireMockExternalBaseUrlVerifierUsesCompatibilityFixture() throws Exception {
        String script = Files.readString(Path.of("scripts/release/verify-wiremock-external-base-url.sh"));

        assertThat(script)
                .contains("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock")
                .contains("wiremock_admin_api_external_base_url_compatibility_verification")
                .doesNotContain("samples/20-provider-capability-p0/http/wiremock_http_mock");
    }

    @Test
    void supportedProviderSampleGatePassesCiVerifiableSamplesWhenExternalMessagingIsNotConfigured() throws Exception {
        String script = Files.readString(Path.of("scripts/release/verify-supported-provider-samples.sh"));

        assertThat(script)
                .contains("external_messaging_runtime_verification: not_configured")
                .contains("external_jdbc_runtime_verification: not_configured")
                .contains("missing_external_jdbc_env: JDBC_CONNECTION")
                .contains("invalid_external_jdbc_dialect")
                .contains("supported_provider_sample_verification_status: passed_ci_verifiable_external_messaging_not_configured")
                .contains("missing_external_messaging_env")
                .doesNotContain("ALLOW_EXTERNAL_MESSAGING_SKIP")
                .doesNotContain("blocked_external_messaging_skipped")
                .contains("supported_provider_sample_verification_status: passed");
    }

    @Test
    void usageKitSamplesCoverSupportedRuntimeModeRows() throws Exception {
        Set<ProviderRuntimeSample> samples = providerRuntimeSamples(Path.of("samples/20-provider-capability-p0"));

        assertThat(samples)
                .contains(
                        new ProviderRuntimeSample("grpc_client", "native"),
                        new ProviderRuntimeSample("polling_observer", "framework"),
                        new ProviderRuntimeSample("rest_client", "mock"));
    }

    @Test
    void committedSamplesUseEnvProfileOnlyRuntimeConfiguration() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("samples"))) {
            List<Path> splitConfigPaths = files
                    .filter(path -> path.toString().contains("/execution_profiles/")
                            || path.toString().contains("/environment_bindings/")
                            || path.getFileName().toString().equals("environment_binding.yaml"))
                    .toList();

            assertThat(splitConfigPaths).isEmpty();
        }

        try (Stream<Path> files = Files.walk(Path.of("samples"))) {
            List<Path> manifestsWithDeprecatedRoots = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("suite_manifest"))
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .filter(path -> containsAny(path, "execution_profiles:", "environment_bindings:"))
                    .toList();

            assertThat(manifestsWithDeprecatedRoots).isEmpty();
        }

        try (Stream<Path> files = Files.walk(Path.of("samples"))) {
            List<Path> envProfilesWithDeprecatedBindings = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().contains("/env_profiles/"))
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .filter(path -> containsAny(path, "binding_keys:"))
                    .toList();

            assertThat(envProfilesWithDeprecatedBindings).isEmpty();
        }
    }

    private static Set<ProviderRuntimeSample> providerRuntimeSamples(Path sampleRoot) throws IOException {
        Set<ProviderRuntimeSample> samples = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(sampleRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("suite_manifest.yaml"))
                    .forEach(path -> samples.addAll(providerRuntimeSamplesFromSuite(path)));
        }
        return samples;
    }

    private static Set<ProviderRuntimeSample> providerRuntimeSamplesFromSuite(Path suiteManifest) {
        try {
            Set<ProviderRuntimeSample> samples = new LinkedHashSet<>();
            Map<String, Object> suite = yamlMap(suiteManifest);
            if (!"v0.3".equals(String.valueOf(suite.get("manifest_version")))) {
                return samples;
            }

            Map<String, String> targetContracts = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : objectMap(suite.get("targets")).entrySet()) {
                String providerContract = String.valueOf(objectMap(entry.getValue()).get("provider_contract"));
                if (!providerContract.isBlank() && providerContract.endsWith(".v0.3")) {
                    targetContracts.put(entry.getKey(), providerContract.substring(0, providerContract.length() - ".v0.3".length()));
                }
            }

            Path suiteRoot = suiteManifest.getParent();
            for (Map<String, Object> profileRef : objectMap(suite.get("env_profiles")).values().stream()
                    .map(ReleaseUsageKitVerificationTest::objectMap)
                    .toList()) {
                String ref = String.valueOf(profileRef.get("ref"));
                if (ref.isBlank()) {
                    continue;
                }
                Map<String, Object> envProfile = yamlMap(suiteRoot.resolve(ref).normalize());
                for (Map.Entry<String, Object> entry : objectMap(envProfile.get("targets")).entrySet()) {
                    String providerType = targetContracts.get(entry.getKey());
                    String runtimeMode = String.valueOf(objectMap(entry.getValue()).get("runtime_mode"));
                    if (providerType != null && !runtimeMode.isBlank()) {
                        samples.add(new ProviderRuntimeSample(providerType, runtimeMode));
                    }
                }
            }
            return samples;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read v0.3 sample suite " + suiteManifest, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yamlMap(Path path) throws IOException {
        Object loaded = new Yaml().load(Files.readString(path));
        if (loaded instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static boolean containsAny(Path path, String... needles) {
        try {
            String text = Files.readString(path);
            for (String needle : needles) {
                if (text.contains(needle)) {
                    return true;
                }
            }
            return false;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read sample file " + path, exception);
        }
    }

    private record ProviderRuntimeSample(String providerType, String runtimeMode) {}
}

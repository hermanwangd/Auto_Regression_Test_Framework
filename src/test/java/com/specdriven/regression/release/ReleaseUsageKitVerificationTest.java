package com.specdriven.regression.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                .contains("JDBC_CONNECTION: ${{ secrets.JDBC_CONNECTION }}")
                .contains("KAFKA_BOOTSTRAP_SERVERS: ${{ secrets.KAFKA_BOOTSTRAP_SERVERS }}")
                .contains("IBM_MQ_CONN_NAME: ${{ secrets.IBM_MQ_CONN_NAME }}")
                .contains("IBM_MQ_CREDENTIAL: ${{ secrets.IBM_MQ_CREDENTIAL }}");
    }

    @Test
    void supportedProviderSampleGateUsesOnlyCanonicalFrameworkCommands() throws Exception {
        String script = Files.readString(Path.of("scripts/release/verify-supported-provider-samples.sh"));

        assertThat(script)
                .contains("validate --suite")
                .contains("run --suite")
                .contains("report --result")
                .contains("validate-evidence --result")
                .contains("samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml external_jdbc_env_secret_ref")
                .contains("samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml ci_kafka_external")
                .contains("samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml ci_ibm_mq_external")
                .doesNotContain("--rp-id");
    }

    @Test
    void supportedProviderSampleGatePassesCiVerifiableSamplesWhenExternalMessagingIsNotConfigured() throws Exception {
        String script = Files.readString(Path.of("scripts/release/verify-supported-provider-samples.sh"));

        assertThat(script)
                .contains("external_messaging_runtime_verification: not_configured")
                .contains("external_jdbc_runtime_verification: not_configured")
                .contains("missing_external_jdbc_env: JDBC_CONNECTION")
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
                        new ProviderRuntimeSample("grpc_client", "mock"),
                        new ProviderRuntimeSample("grpc_client", "stub"),
                        new ProviderRuntimeSample("polling_observer", "ephemeral"),
                        new ProviderRuntimeSample("rest_client", "mock"),
                        new ProviderRuntimeSample("rest_client", "stub"));
    }

    private static Set<ProviderRuntimeSample> providerRuntimeSamples(Path sampleRoot) throws IOException {
        Set<ProviderRuntimeSample> samples = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(sampleRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".yaml"))
                    .filter(path -> path.toString().contains("/provider_instances/"))
                    .forEach(path -> samples.addAll(providerRuntimeSamplesFrom(path)));
        }
        return samples;
    }

    private static Set<ProviderRuntimeSample> providerRuntimeSamplesFrom(Path providerInstance) {
        try {
            Map<String, Object> document = yamlMap(providerInstance);
            String providerType = String.valueOf(document.get("provider_type"));
            List<String> runtimeModes = stringList(document.get("runtime_modes"));
            Set<ProviderRuntimeSample> samples = new LinkedHashSet<>();
            for (String runtimeMode : runtimeModes) {
                samples.add(new ProviderRuntimeSample(providerType, runtimeMode));
            }
            return samples;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read sample provider instance " + providerInstance, exception);
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

    private record ProviderRuntimeSample(String providerType, String runtimeMode) {}
}

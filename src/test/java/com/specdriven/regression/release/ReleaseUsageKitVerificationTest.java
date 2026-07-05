package com.specdriven.regression.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReleaseUsageKitVerificationTest {

    @Test
    void releaseWorkflowRunsSupportedProviderSampleGateWithExternalMessagingOptionalByDefault() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/release.yml"));

        assertThat(workflow)
                .contains("Verify supported provider samples")
                .contains("scripts/release/verify-supported-provider-samples.sh")
                .contains("REQUIRE_EXTERNAL_MESSAGING: ${{ vars.REQUIRE_EXTERNAL_MESSAGING || 'false' }}")
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
                .contains("samples/provider_capability/kafka/suite_manifest.yaml ci_kafka_external")
                .contains("samples/provider_capability/ibm_mq/suite_manifest.yaml ci_ibm_mq_external")
                .doesNotContain("--rp-id");
    }

    @Test
    void supportedProviderSampleGatePassesCiVerifiableSamplesWhenExternalMessagingIsNotConfigured() throws Exception {
        String script = Files.readString(Path.of("scripts/release/verify-supported-provider-samples.sh"));

        assertThat(script)
                .contains("external_messaging_runtime_verification: not_configured")
                .contains("supported_provider_sample_verification_status: passed_ci_verifiable_external_messaging_not_configured")
                .contains("missing_external_messaging_env")
                .doesNotContain("ALLOW_EXTERNAL_MESSAGING_SKIP")
                .doesNotContain("blocked_external_messaging_skipped")
                .contains("supported_provider_sample_verification_status: passed");
    }
}

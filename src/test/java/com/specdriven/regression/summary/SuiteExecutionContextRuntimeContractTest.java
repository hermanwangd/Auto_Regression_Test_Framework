package com.specdriven.regression.summary;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.contract.CommonVerifyService;
import com.specdriven.regression.contract.ContractBaselineRuntimeService;
import com.specdriven.regression.contract.GoldenE2eService;
import com.specdriven.regression.contract.GrpcMockCapabilityService;
import com.specdriven.regression.contract.JdbcProviderCapabilityService;
import com.specdriven.regression.contract.MessagingClientProviderCapabilityService;
import com.specdriven.regression.contract.NatsProviderCapabilityService;
import com.specdriven.regression.contract.RestClientCapabilityService;
import com.specdriven.regression.contract.SoapMockCapabilityService;
import com.specdriven.regression.contract.WireMockHttpRequestCapabilityService;
import com.specdriven.regression.contract.WireMockProviderCapabilityService;
import com.specdriven.regression.contract.v03.V03RuntimeExecutionService;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteExecutionContextRuntimeContractTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();

    @TempDir Path tempDir;

    @Test
    void everyChildCapableRuntimeKeepsStandaloneAndAddsContextOverload() throws Exception {
        for (Class<?> service : List.of(
                V03RuntimeExecutionService.class,
                WireMockHttpRequestCapabilityService.class,
                SoapMockCapabilityService.class,
                GrpcMockCapabilityService.class,
                RestClientCapabilityService.class,
                WireMockProviderCapabilityService.class,
                JdbcProviderCapabilityService.class,
                MessagingClientProviderCapabilityService.class,
                NatsProviderCapabilityService.class,
                CommonVerifyService.class,
                GoldenE2eService.class,
                ContractBaselineRuntimeService.class)) {
            Method standalone = service.getMethod("run", Path.class, String.class, Path.class);
            Method supplied = service.getMethod("run", Path.class, String.class, SuiteExecutionContext.class);
            assertThat(standalone.getReturnType()).as(service.getSimpleName()).isEqualTo(supplied.getReturnType());
        }
    }

    @Test
    void representativeRuntimesHonorSuppliedContextAndStandaloneCallsRemainUsable() {
        assertRuntimePair(
                (manifest, profile, output) -> new V03RuntimeExecutionService().run(manifest, profile, output),
                (manifest, profile, context) -> new V03RuntimeExecutionService().run(manifest, profile, context),
                sample("samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml"), "local_v03");
        assertRuntimePair(
                (manifest, profile, output) -> new WireMockHttpRequestCapabilityService().run(manifest, profile, output),
                (manifest, profile, context) -> new WireMockHttpRequestCapabilityService().run(manifest, profile, context),
                sample("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml"),
                "local_wiremock_http");
        assertRuntimePair(
                (manifest, profile, output) -> new JdbcProviderCapabilityService().run(manifest, profile, output),
                (manifest, profile, context) -> new JdbcProviderCapabilityService().run(manifest, profile, context),
                sample("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/suite_manifest.yaml"),
                "local_jdbc");
        assertRuntimePair(
                (manifest, profile, output) -> new MessagingClientProviderCapabilityService().run(manifest, profile, output),
                (manifest, profile, context) -> new MessagingClientProviderCapabilityService().run(manifest, profile, context),
                sample("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml"),
                "local_kafka");
        assertRuntimePair(
                (manifest, profile, output) -> new CommonVerifyService().run(manifest, profile, output),
                (manifest, profile, context) -> new CommonVerifyService().run(manifest, profile, context),
                sample("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/verification/common_verify/suite_manifest.yaml"),
                "local_verify");
        assertRuntimePair(
                (manifest, profile, output) -> new GoldenE2eService().run(manifest, profile, output),
                (manifest, profile, context) -> new GoldenE2eService().run(manifest, profile, context),
                sample("samples/90-compatibility/legacy-v0.2/00-getting-started/golden_e2e/suite_manifest.yaml"),
                "local_golden");
    }

    @Test
    void runtimeEntryTimeDoesNotReuseParentStartTime() throws Exception {
        Path root = tempDir.resolve("timing");
        Instant parentStart = Instant.parse("2020-01-01T00:00:00Z");
        var context = new SuiteExecutionContext("BATCH-TIMING", "local_v03", parentStart, root);

        var result = new V03RuntimeExecutionService().run(
                sample("samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml"),
                "local_v03",
                context);

        String resultJson = java.nio.file.Files.readString(result.resultJson());
        assertThat(resultJson).doesNotContain("\"start_time\": \"" + parentStart + "\"");
    }

    @Test
    void goldenSuppliedContextUsesUniqueRunIdsEvenWithLegacyBatchId() {
        Path manifest = sample("samples/90-compatibility/legacy-v0.2/00-getting-started/golden_e2e/suite_manifest.yaml");
        var context = new SuiteExecutionContext(
                "BATCH-GOLDEN-E2E-001", "local_golden", Instant.now(), tempDir.resolve("golden-legacy-batch"));

        var first = new GoldenE2eService().run(manifest, "local_golden", context);
        var second = new GoldenE2eService().run(manifest, "local_golden", context);

        assertThat(first.batchId()).isEqualTo("BATCH-GOLDEN-E2E-001");
        assertThat(first.runId()).isNotEqualTo("RUN-GOLDEN-E2E-001").isNotEqualTo(second.runId());
    }

    @Test
    void blankStandaloneProfileReturnsStructuredBlockedResultAndSuppliedMismatchIsActionable() {
        Path manifest = sample("samples/90-compatibility/legacy-v0.2/00-getting-started/golden_e2e/suite_manifest.yaml");

        var blocked = new GoldenE2eService().run(manifest, " ", tempDir.resolve("blank-profile"));
        assertThat(blocked.status()).isEqualTo("blocked");
        assertThat(blocked.findings()).isNotEmpty();

        var mismatch = new SuiteExecutionContext("BATCH-PARENT", "ci", Instant.now(), tempDir.resolve("mismatch"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new GoldenE2eService().run(manifest, "local_golden", mismatch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context")
                .hasMessageContaining("local_golden");
    }

    private void assertRuntimePair(
            StandaloneRun standalone,
            ContextRun supplied,
            Path manifest,
            String profile) {
        Object standaloneResult = standalone.run(manifest, profile, tempDir.resolve("standalone"));
        assertThat(value(standaloneResult, "batchId")).isNotBlank();
        assertThat(value(standaloneResult, "runId")).isNotBlank();

        Path suppliedRoot = tempDir.resolve("parent").resolve(profile);
        SuiteExecutionContext context = new SuiteExecutionContext(
                "BATCH-PARENT-SHARED", profile, Instant.parse("2026-07-11T01:02:03Z"), suppliedRoot);
        Object first = supplied.run(manifest, profile, context);
        Object second = supplied.run(manifest, profile, context);

        assertThat(value(first, "batchId")).isEqualTo("BATCH-PARENT-SHARED");
        assertThat(value(second, "batchId")).isEqualTo("BATCH-PARENT-SHARED");
        assertThat(value(first, "runId")).isNotBlank().isNotEqualTo(value(second, "runId"));
        Path runDir = (Path) invoke(first, "evidenceDir");
        assertThat(runDir.toAbsolutePath().normalize()).startsWith(suppliedRoot.toAbsolutePath().normalize());
    }

    private Path sample(String relative) {
        return ROOT.resolve(relative).normalize();
    }

    private String value(Object result, String method) {
        Object value = invoke(result, method);
        return value == null ? "" : value.toString();
    }

    private Object invoke(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Runtime result does not expose " + method, error);
        }
    }

    @FunctionalInterface
    private interface StandaloneRun {
        Object run(Path manifest, String profile, Path outputRoot);
    }

    @FunctionalInterface
    private interface ContextRun {
        Object run(Path manifest, String profile, SuiteExecutionContext context);
    }
}

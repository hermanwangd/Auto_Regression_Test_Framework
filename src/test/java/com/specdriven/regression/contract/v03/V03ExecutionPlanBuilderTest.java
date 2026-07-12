package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V03ExecutionPlanBuilderTest {

    @TempDir
    Path tempDir;

    private static final Path HTTP_MOCK_SUITE =
            Path.of("samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml");
    private static final Path MULTI_TEST_SUITE =
            Path.of("samples/20-provider-capability-p0/verification/multi_test_shared_env/suite_manifest.yaml");

    @Test
    void compilesMultiTestSuiteWithSharedTargetsAndPerTestSteps() {
        V03ExecutionPlan compiled = new V03ExecutionPlanBuilder().build(MULTI_TEST_SUITE, "local_v03");

        assertThat(compiled.suiteId()).isEqualTo("MULTI-TEST-v0.3");
        assertThat(compiled.targets().keySet())
                .containsExactly("common_verifier");
        assertThat(compiled.tests()).extracting(V03CompiledTestCase::testCaseId)
                .containsExactly("MULTI-TEST-V03-TC-001", "MULTI-TEST-V03-TC-002");
        assertThat(compiled.tests()).allSatisfy(testCase -> {
            assertThat(testCase.setup()).isEmpty();
            assertThat(testCase.execute()).isEmpty();
            assertThat(testCase.verify()).hasSize(1);
            assertThat(testCase.cleanup()).isEmpty();
        });
    }

    @Test
    void buildsExecutionPlanForV03ProtocolTargets() {
        V03ExecutionPlan plan = new V03ExecutionPlanBuilder().build(HTTP_MOCK_SUITE, "local_v03");

        assertThat(plan.suiteId()).isEqualTo("HTTP-MOCK-REST-CLIENT-v0.3");
        assertThat(plan.profile()).isEqualTo("local_v03");
        assertThat(plan.targets().values()).extracting(V03ResolvedTarget::target)
                .containsExactly("payment_mock", "payment_api");
        assertThat(plan.targets().values()).extracting(V03ResolvedTarget::providerContract)
                .containsExactly("http_mock.v0.3", "rest_client.v0.3");
        assertThat(plan.targets().values()).extracting(V03ResolvedTarget::runtimeMode)
                .containsExactly("mock", "mock");
        assertThat(plan.steps()).extracting(V03ExecutionStep::providerContract)
                .contains("http_mock.v0.3", "rest_client.v0.3");
        assertThat(plan.steps()).allSatisfy(step -> assertThat(step.providerInstanceRef()).isBlank());
    }

    @Test
    void recordsProviderOperationsInLifecycleOrder() {
        V03ExecutionPlan plan = new V03ExecutionPlanBuilder().build(HTTP_MOCK_SUITE, "local_v03");

        assertThat(plan.steps()).extracting(V03ExecutionStep::id)
                .containsExactly(
                        "load_payment_stub",
                        "call_payment_api",
                        "payment_response_status",
                        "payment_response_body",
                        "reset_payment_mock");
        assertThat(plan.steps()).extracting(V03ExecutionStep::phase)
                .containsExactly("setup", "execute", "verify", "verify", "cleanup");
        assertThat(plan.steps()).extracting(V03ExecutionStep::operation)
                .containsExactly("load_stubs", "http_request", "equals", "json_match", "reset_mock");
        assertThat(plan.steps()).extracting(V03ExecutionStep::kind)
                .containsExactly(
                        V03ExecutionStepKind.PROVIDER_OPERATION,
                        V03ExecutionStepKind.PROVIDER_OPERATION,
                        V03ExecutionStepKind.ASSERTION,
                        V03ExecutionStepKind.ASSERTION,
                        V03ExecutionStepKind.PROVIDER_OPERATION);
    }

    @Test
    void failsFastWhenV03SuiteIsInvalid() {
        assertThatThrownBy(() -> new V03ExecutionPlanBuilder()
                .build(Path.of("samples/20-provider-capability-p0/http/rest_client_with_wiremock/missing.yaml"), "local_v03"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v0.3 suite is not valid");
    }

    @Test
    void retainsTestCaseWhenAllLifecyclePhasesAreEmpty() throws Exception {
        Path source = Path.of("samples/00-getting-started/golden_e2e");
        Path suiteRoot = tempDir.resolve("empty-test");
        copyDirectory(source, suiteRoot);
        Files.writeString(suiteRoot.resolve("test_cases/golden_success.yaml"), """
                dsl_version: v0.3
                test_case_id: EMPTY-V03-TC-001
                title: Empty lifecycle test
                execute: []
                verify: []
                """);

        V03ExecutionPlan plan = new V03ExecutionPlanBuilder()
                .build(suiteRoot.resolve("suite_manifest.yaml"), "local_v03");

        assertThat(plan.tests()).extracting(V03CompiledTestCase::testCaseId)
                .containsExactly("EMPTY-V03-TC-001");
        assertThat(plan.steps()).isEmpty();
    }

    @Test
    void producesTheSameDigestForRepeatedCompilationOfTheSameSuite() {
        V03ExecutionPlanBuilder builder = new V03ExecutionPlanBuilder();

        String first = builder.build(HTTP_MOCK_SUITE, "local_v03").planDigest();
        String second = builder.build(HTTP_MOCK_SUITE, "local_v03").planDigest();

        assertThat(second).isEqualTo(first);
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }
}

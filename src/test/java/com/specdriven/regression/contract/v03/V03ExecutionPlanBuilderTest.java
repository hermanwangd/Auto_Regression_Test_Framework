package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class V03ExecutionPlanBuilderTest {

    private static final Path HTTP_MOCK_SUITE =
            Path.of("samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml");
    private static final Path MULTI_TEST_SUITE =
            Path.of("samples/20-provider-capability-p0/verification/multi_test_shared_env/suite_manifest.yaml");

    @Test
    void compilesMultiTestSuiteWithSharedTargetsAndPerTestSteps() {
        V03CompiledSuite compiled = new V03ExecutionPlanBuilder().compile(MULTI_TEST_SUITE, "local_v03");

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
        assertThat(plan.targets()).extracting(V03ResolvedTarget::target)
                .containsExactly("payment_mock", "payment_api");
        assertThat(plan.targets()).extracting(V03ResolvedTarget::providerContract)
                .containsExactly("http_mock.v0.3", "rest_client.v0.3");
        assertThat(plan.targets()).extracting(V03ResolvedTarget::runtimeMode)
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
    }

    @Test
    void failsFastWhenV03SuiteIsInvalid() {
        assertThatThrownBy(() -> new V03ExecutionPlanBuilder()
                .build(Path.of("samples/20-provider-capability-p0/http/rest_client_with_wiremock/missing.yaml"), "local_v03"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v0.3 suite is not valid");
    }
}

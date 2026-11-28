package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.contract.ContractBaselineService;
import com.specdriven.regression.summary.SuiteArtifactFinalizer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V03ProviderStepTimingTest {

    @Test
    void recordsElapsedMillisecondsForEachInvokedProviderPhaseIncludingFailures(@TempDir Path output)
            throws Exception {
        var registry = new V03ProviderRuntimeRegistry(List.of(
                new DelayedAdapter("soap_mock"), new DelayedAdapter("rest_client")));
        var service = new V03RuntimeExecutionService(new ContractBaselineService(), registry);
        Path manifest = Path.of("samples/20-provider-capability-p0/rpc/soap_mock/suite_manifest.yaml");
        var run = service.run(manifest, "local_v03", output);
        assertThat(run.status()).isEqualTo("failed");
        new SuiteArtifactFinalizer().finalizeLeaf(manifest, run.resultJson());

        JsonNode result = new ObjectMapper().readTree(run.resultJson().toFile());
        assertThat(result.path("completion_status").asText()).isEqualTo("complete");
        JsonNode steps = result.path("step_results");
        assertThat(steps).isEqualTo(result.path("steps"));
        assertThat(steps.findValuesAsText("phase")).containsExactly("setup", "execute", "verify", "cleanup");
        assertThat(steps.get(2).path("failure_code").asText()).isEqualTo("OPERATION_FAILED");
        for (JsonNode step : steps) {
            JsonNode duration = step.path("duration_ms");
            assertThat(duration.isIntegralNumber()).as("duration_ms for %s", step.path("id")).isTrue();
            assertThat(duration.asLong()).as("elapsed time for %s", step.path("id")).isGreaterThanOrEqualTo(20);
        }
    }

    private record DelayedAdapter(String providerType) implements V03ProviderRuntimeAdapter {
        @Override
        public boolean supports(String contract, String operation) {
            return true;
        }

        @Override
        public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            if (step.kind() == V03ExecutionStepKind.PROVIDER_CHECK) {
                return new V03StepResult(step.id(), "failed", Map.of(), List.of(), "OPERATION_FAILED",
                        "Provider check failed after its delay.");
            }
            Map<String, Object> outputs = "http_request".equals(step.operation())
                    ? Map.of("response.status", 200) : Map.of();
            return new V03StepResult(step.id(), "passed", outputs, List.of(), "", "");
        }
    }
}

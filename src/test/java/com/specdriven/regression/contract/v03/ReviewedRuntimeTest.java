package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;
import com.specdriven.regression.contract.ContractBaselineService;
import com.specdriven.regression.contract.v03.adapter.SampleFakeProviderV03Adapter;
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.producttest.ProductTestHandoffService.InvocationRequirement;
import com.specdriven.regression.sit.*;
import com.specdriven.regression.summary.SuiteExecutionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewedRuntimeTest {
    @TempDir Path temporary;
    private static final Path ROOT = Path.of("samples/41-product-testing/reviewed_local");
    @Test void explicitReviewedEntryRequiresARealPinEvenWithoutPolicy() {
        AtomicInteger calls = new AtomicInteger();
        var real = new SampleFakeProviderV03Adapter();
        var spy = new V03ProviderRuntimeAdapter() {
            public String providerType() { return real.providerType(); }
            public boolean supports(String c,String o) { return real.supports(c,o); }
            public V03StepResult execute(V03ExecutionStep s,V03ExecutionContext c) { calls.incrementAndGet(); return real.execute(s,c); }
        };
        var service = new V03RuntimeExecutionService(new ContractBaselineService(),
                new V03ProviderRuntimeRegistry(List.of(spy)), new SitReadinessService(),
                new InvocationRequirement(null), new LocalFakeSitOwnership());
        for (InvocationRequirement requirement : java.util.Arrays.asList(new InvocationRequirement(null),
                new InvocationRequirement(""), new InvocationRequirement("malformed"), null)) {
            var result = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.runReviewed(
                    Path.of("samples/00-getting-started/golden_e2e/suite_manifest.yaml"), "local_v03",
                    SuiteExecutionContext.standalone("local_v03", temporary, "V03"), requirement));
            assertThat(result.status()).isEqualTo("blocked");
            assertThat(result.providerRuntimeExecuted()).isFalse();
        }
        assertThat(calls).hasValue(0);
    }
    @Test void localReviewedExecutionUsesExistingRunnerAndCanonicalEvidence() throws Exception {
        String digest = Files.readString(ROOT.resolve("handoff/reviewed-tests.json.sha256")).trim();
        var run = new V03RuntimeExecutionService().runReviewed(ROOT.resolve("suite_manifest.yaml"), "local_v03",
                SuiteExecutionContext.standalone("local_v03",temporary,"V03"),new InvocationRequirement(digest));
        assertThat(run.status()).as(run.findings().toString()).isEqualTo("passed");
        assertThat(run.providerRuntimeExecuted()).isTrue();
        assertThat(Files.isRegularFile(run.evidenceDir().resolve("product-test/reviewed-handoff.json"))).isTrue();
        assertThat(Files.readString(run.evidenceDir().resolve("product-test/reviewed-handoff.json")))
                .contains(digest,"not_used","observed_provider_modes","framework_verification_only");
        var validation = new EvidenceHardeningService().validateResult(run.resultJson());
        assertThat(validation.valid()).as(validation.findings().toString()).isTrue();
    }
    @Test void directReviewedRuntimeCannotIgnoreWrongPinOrMissingPolicy() {
        AtomicInteger calls = new AtomicInteger();
        var real = new SampleFakeProviderV03Adapter();
        var spy = new V03ProviderRuntimeAdapter() {
            public String providerType() { return real.providerType(); }
            public boolean supports(String c,String o) { return real.supports(c,o); }
            public V03StepResult execute(V03ExecutionStep s,V03ExecutionContext c) { calls.incrementAndGet();return real.execute(s,c); }
        };
        var service = new V03RuntimeExecutionService(new ContractBaselineService(),
                new V03ProviderRuntimeRegistry(List.of(spy)),new SitReadinessService());
        for (Path suite : List.of(ROOT.resolve("suite_manifest.yaml"),
                Path.of("samples/00-getting-started/golden_e2e/suite_manifest.yaml"))) {
            var run = service.runReviewed(suite,"local_v03",
                    SuiteExecutionContext.standalone("local_v03",temporary,"V03"),new InvocationRequirement("a".repeat(64)));
            assertThat(run.status()).isEqualTo("blocked");
            assertThat(run.providerRuntimeExecuted()).isFalse();
        }
        assertThat(calls).hasValue(0);
    }
}

package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.contract.ContractBaselineService;
import com.specdriven.regression.contract.v03.adapter.SampleFakeProviderV03Adapter;
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.producttest.ProductTestHandoffService;
import com.specdriven.regression.producttest.ProductTestHandoffService.InvocationRequirement;
import com.specdriven.regression.sit.*;
import com.specdriven.regression.summary.SuiteExecutionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

class ReviewedSharedStateRuntimeTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private final Yaml yaml = new Yaml();
    private final LocalFakeSitOwnership store = new LocalFakeSitOwnership();

    @Test void readOnlyAndMissingReadinessAcquireNothingAndInvokeNoProvider() throws Exception {
        for (String variant : List.of("read_only", "missing")) {
            Path root = fixture(variant.equals("read_only") ? "read_only" : "owned", "owned_only", false, false);
            if (variant.equals("read_only")) {
                var p = readYaml(root.resolve("env_profiles/local_v03.yaml"));
                map(map(p.get("product_test")).get("shared_state")).put("cleanup", "none");
                writeYaml(root.resolve("env_profiles/local_v03.yaml"),p);
            } else {
                var p = readYaml(root.resolve("env_profiles/local_v03.yaml"));
                map(((List<?>) map(p.get("sit_readiness")).get("checks")).get(0)).put("config",Map.of("state","MISSING","observed_version","v1"));
                writeYaml(root.resolve("env_profiles/local_v03.yaml"),p);
            }
            prepare(root);
            CountingProvider provider = new CountingProvider(false);
            var result = run(root, provider, new SitReadinessService());
            assertThat(result.status()).isEqualTo("blocked");
            assertThat(result.providerRuntimeExecuted()).isFalse();
            assertThat(provider.calls).isZero();
            if (variant.equals("read_only")) {
                var entries = (List<?>) readYaml(result.evidenceDir().resolve("evidence_index.yaml")).get("entries");
                var policyEntry = entries.stream().map(this::map)
                        .filter(entry -> "product-test/policy.json".equals(entry.get("file_path"))).findFirst().orElseThrow();
                assertThat(policyEntry.get("status")).isEqualTo("blocked");
            }
            assertThat(store.snapshot("fake-env","orders").state()).isEqualTo(LocalFakeSitOwnership.State.UNCLAIMED);
            assertValid(result);
        }
    }

    @Test void ownedCleanupReleasesOnlyTheCurrentClaim() throws Exception {
        Path root = fixture("owned","owned_only",false,false); prepare(root);
        var result = run(root,new CountingProvider(false),new SitReadinessService());
        assertThat(result.status()).as(result.findings().toString()).isEqualTo("passed");
        var state = store.snapshot("fake-env","orders");
        assertThat(state.state()).isEqualTo(LocalFakeSitOwnership.State.RELEASED);
        assertThat(state.resources()).isEmpty();
        assertValid(result);
    }

    @Test void cleanupNoneRetainsResourcesAndBlocksTheNextRun() throws Exception {
        Path root = fixture("owned","none",true,false); prepare(root);
        assertThat(run(root,new CountingProvider(false),new SitReadinessService()).status()).isEqualTo("passed");
        var retained = store.snapshot("fake-env","orders");
        assertThat(retained.state()).isEqualTo(LocalFakeSitOwnership.State.RETAINED);
        assertThat(retained.resources()).hasSize(1);
        CountingProvider next = new CountingProvider(false);
        var result = run(root,next,new SitReadinessService());
        assertThat(result.status()).isEqualTo("blocked");
        assertThat(next.calls).isZero();
        assertThat(store.snapshot("fake-env","orders")).isEqualTo(retained);
        assertValid(result);
    }

    @Test void assertionFailureSurvivesCleanupFailureAndClaimIsRetained() throws Exception {
        Path root = fixture("owned","owned_only",false,true); prepare(root);
        var result = run(root,new CountingProvider(true),new SitReadinessService());
        assertThat(result.status()).as(result.findings().toString()).isEqualTo("failed");
        var document = json.readTree(result.resultJson().toFile());
        assertThat(document.path("failure").path("code").asText()).isEqualTo("ASSERTION_FAILED");
        assertThat(document.path("cleanup_failures").size()).isEqualTo(1);
        assertThat(store.snapshot("fake-env","orders").state()).isEqualTo(LocalFakeSitOwnership.State.RETAINED);
        assertValid(result);
    }

    @Test void timeoutAcquiresNoClaimAndInvokesNoProvider() throws Exception {
        Path root = fixture("owned","owned_only",false,false);
        var p = readYaml(root.resolve("env_profiles/local_v03.yaml")); map(p.get("sit_readiness")).put("timeout_ms",200);
        writeYaml(root.resolve("env_profiles/local_v03.yaml"),p); prepare(root);
        var probe = new SitReadinessAdapter() {
            public String id() { return "fixture"; }
            public String version() { return "1"; }
            public boolean simulated() { return true; }
            public SitReadinessObservation check(SitReadinessRequest request) throws Exception {
                Thread.sleep(1000); return ready();
            }
        };
        var provider = new CountingProvider(false);
        var result = run(root,provider,new SitReadinessService(new SitReadinessRegistry(List.of(probe))));
        assertThat(result.status()).isEqualTo("blocked"); assertThat(provider.calls).isZero();
        assertThat(store.snapshot("fake-env","orders").state()).isEqualTo(LocalFakeSitOwnership.State.UNCLAIMED);
        assertThat(Files.readString(result.evidenceDir().resolve("readiness/sit-readiness.json"))).contains("TIMEOUT");
        assertThat(json.readTree(result.evidenceDir().resolve("product-test/reviewed-handoff.json").toFile())
                .path("observed_dependency_modes").path("orders").asText()).isEqualTo("simulated");
        assertValid(result);
    }

    @ParameterizedTest @ValueSource(strings={"observed_only","both_reports"})
    void blockedResultCannotOmitAnAttemptedDependencyMode(String omission) throws Exception {
        Path root = fixture("owned","owned_only",false,false);
        var p = readYaml(root.resolve("env_profiles/local_v03.yaml"));
        map(((List<?>)map(p.get("sit_readiness")).get("checks")).get(0))
                .put("config",Map.of("state","MISSING","observed_version","v1"));
        writeYaml(root.resolve("env_profiles/local_v03.yaml"),p); prepare(root);
        var result = run(root,new CountingProvider(false),new SitReadinessService());
        assertThat(result.status()).isEqualTo("blocked");
        assertValid(result);
        Path provenance = result.evidenceDir().resolve("product-test/reviewed-handoff.json");
        var document = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(provenance.toFile());
        assertThat(document.path("observed_dependency_modes").path("orders").asText()).isEqualTo("simulated");
        document.putObject("observed_dependency_modes");
        json.writeValue(provenance.toFile(),document);
        if (omission.equals("both_reports")) {
            Path readinessPath = result.evidenceDir().resolve("readiness/sit-readiness.json");
            var readiness = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(readinessPath.toFile());
            readiness.putArray("checks");
            json.writeValue(readinessPath.toFile(),readiness);
        }
        var canonical = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(result.resultJson().toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)canonical.path("product_test")).put("provenance_sha256",
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(provenance))));
        json.writeValue(result.resultJson().toFile(),canonical);
        var checked = new EvidenceHardeningService().validateResult(result.resultJson());
        assertThat(checked.valid()).isFalse();
        assertThat(checked.findings().toString()).contains("reviewed_evidence");
    }

    @Test void readinessMutationOfOriginalInputsCannotChangeTheExecutingSnapshot() throws Exception {
        Path root = fixture("owned","owned_only",false,false); prepare(root);
        var probe = new SitReadinessAdapter() {
            public String id() { return "fixture"; }
            public String version() { return "1"; }
            public boolean simulated() { return true; }
            public SitReadinessObservation check(SitReadinessRequest request) throws Exception {
                Files.writeString(root.resolve("fixtures/input.json"),"unreviewed bytes after snapshot");
                Files.writeString(root.resolve("contracts/sample_fake_provider_v0_3.yaml"),"unreviewed contract after snapshot");
                return ready();
            }
        };
        var result = run(root,new CountingProvider(false),new SitReadinessService(new SitReadinessRegistry(List.of(probe))));
        assertThat(result.status()).as(result.findings().toString()).isEqualTo("passed");
        assertValid(result);
    }

    @Test void lostOwnershipCleanupAndStaleFinalizerPreserveReplacementOwner() throws Exception {
        Path root = fixture("owned","owned_only",false,true); prepare(root);
        var real = new SampleFakeProviderV03Adapter();
        var provider = new SitPolicyAwareProvider() {
            public String providerType() { return real.providerType(); }
            public boolean supports(String c,String o) { return real.supports(c,o); }
            public boolean simulated() { return true; }
            public SitOperationEffect sitEffect(String c,String o) { return real.sitEffect(c,o); }
            public V03StepResult execute(V03ExecutionStep s,V03ExecutionContext c) { return real.execute(s,c); }
            public V03StepResult executeWithSitPolicy(V03ExecutionStep s,V03ExecutionContext c,SitExecutionScope scope) {
                var result = real.executeWithSitPolicy(s,c,scope);
                if ("execute".equals(s.phase())) {
                    // An independently scheduled owner transition between provider phases.
                    store.cleanup(scope.token()); store.finish(scope.token(),false);
                    var replacement = store.claim("fake-env","orders","other-team","other-run");
                    store.create(replacement,"foreign-resource","preserve me");
                }
                return result;
            }
        };
        var result = run(root,provider,new SitReadinessService());
        assertThat(result.status()).isEqualTo("failed");
        var document = json.readTree(result.resultJson().toFile());
        assertThat(document.path("failure").path("code").asText()).isEqualTo("ASSERTION_FAILED");
        assertThat(document.path("cleanup_failures").size()).isGreaterThanOrEqualTo(1);
        var current = store.snapshot("fake-env","orders");
        assertThat(current.state()).isEqualTo(LocalFakeSitOwnership.State.OWNED);
        assertThat(current.owner()).isEqualTo("other-team");
        assertThat(current.resources().get("foreign-resource").data()).isEqualTo("preserve me");
        assertValid(result);
    }

    private SitReadinessObservation ready() {
        return new SitReadinessObservation(SitReadinessState.READY,"v1",Instant.now(),Map.of("fixture","local"),"");
    }
    private void assertValid(V03RuntimeExecutionService.V03RuntimeRunResult result) {
        var validity = new EvidenceHardeningService().validateResult(result.resultJson());
        assertThat(validity.valid()).as(validity.findings().toString()).isTrue();
    }
    private V03RuntimeExecutionService.V03RuntimeRunResult run(Path root,SitPolicyAwareProvider provider,SitReadinessService readiness) throws Exception {
        var pin = new InvocationRequirement(Files.readString(root.resolve("handoff/reviewed-tests.json.sha256")).trim());
        var service = new V03RuntimeExecutionService(new ContractBaselineService(),new V03ProviderRuntimeRegistry(List.of(provider)),
                readiness,pin,store);
        return service.run(root.resolve("suite_manifest.yaml"),"local_v03",
                SuiteExecutionContext.standalone("local_v03",temporary.toRealPath().resolve("runs"),"V03"));
    }
    private Path fixture(String access,String cleanup,boolean removeCleanup,boolean assertionFail) throws Exception {
        Path source = Path.of("samples/41-product-testing/reviewed_local");
        Path root = Files.createTempDirectory(temporary.toRealPath(),"suite-");
        try (var walk = Files.walk(source)) { for (Path path : walk.toList()) {
            Path target=root.resolve(source.relativize(path));
            if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path,target);
        }}
        var p=readYaml(root.resolve("env_profiles/local_v03.yaml"));
        map(p.get("product_test")).put("shared_state",new LinkedHashMap<>(Map.of("environment_id","fake-env","namespace","orders", "owner","team", "data_access",access,"cleanup",cleanup)));
        map(p.get("product_test")).put("dependency_modes",Map.of("orders","simulated"));
        p.put("sit_readiness",new LinkedHashMap<>(Map.of("timeout_ms",1000,"checks",List.of(new LinkedHashMap<>(Map.of("id","orders","kind","test_data","adapter","fixture","expected_version","v1","config",Map.of("state","READY","observed_version","v1")))))));
        writeYaml(root.resolve("env_profiles/local_v03.yaml"),p);
        var test=readYaml(root.resolve("test_cases/golden_success.yaml"));
        if (assertionFail) map(map(((List<?>)test.get("verify")).get(0)).get("assert")).put("expected","NOT_OK");
        if (removeCleanup) {
            test.remove("cleanup");
            var review=json.readValue(root.resolve("review-input.json").toFile(),Map.class);
            var files=new ArrayList<Map<String,Object>>((List<Map<String,Object>>)review.get("reviewed_files"));
            files.removeIf(f -> "fixtures/cleanup_fixture.yaml".equals(f.get("path")));
            review.put("reviewed_files",files); json.writeValue(root.resolve("review-input.json").toFile(),review);
        }
        writeYaml(root.resolve("test_cases/golden_success.yaml"),test);
        return root;
    }
    private void prepare(Path root) {
        new ProductTestHandoffService().prepare(root.resolve("suite_manifest.yaml"),"local_v03",root.resolve("review-input.json"),null,root.resolve("handoff/reviewed-tests.json"));
    }
    private Map<String,Object> readYaml(Path path) throws Exception { return map(yaml.load(Files.readString(path))); }
    private void writeYaml(Path path,Map<String,Object> value) throws Exception { Files.writeString(path,yaml.dump(value)); }
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object value) { return (Map<String,Object>) value; }

    private static final class CountingProvider implements SitPolicyAwareProvider {
        private final SampleFakeProviderV03Adapter delegate=new SampleFakeProviderV03Adapter();
        private final boolean failCleanup;
        int calls;
        CountingProvider(boolean failCleanup) { this.failCleanup=failCleanup; }
        public String providerType() { return delegate.providerType(); }
        public boolean supports(String c,String o) { return delegate.supports(c,o); }
        public boolean simulated() { return true; }
        public SitOperationEffect sitEffect(String c,String o) { return delegate.sitEffect(c,o); }
        public V03StepResult execute(V03ExecutionStep s,V03ExecutionContext c) { calls++; return delegate.execute(s,c); }
        public V03StepResult executeWithSitPolicy(V03ExecutionStep s,V03ExecutionContext c,SitExecutionScope scope) {
            calls++;
            if (failCleanup && "cleanup".equals(s.phase())) return new V03StepResult(s.id(),"failed",Map.of(),List.of(),"CLEANUP_FAILED","synthetic cleanup failure");
            return delegate.executeWithSitPolicy(s,c,scope);
        }
    }
}

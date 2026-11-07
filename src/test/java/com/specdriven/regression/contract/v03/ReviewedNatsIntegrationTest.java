package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.*;
import com.specdriven.regression.producttest.ProductTestHandoffService.InvocationRequirement;
import com.specdriven.regression.summary.SuiteExecutionContext;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Handoff, mode and input closure checks; protocol execution is tested by NatsNativeProtocolTest. */
class ReviewedNatsIntegrationTest {
    @TempDir Path temporary;
    final ReviewedLoopbackRestTest helper=new ReviewedLoopbackRestTest();

    @Test void selectedNatsContractAndPayloadAreIncludedInSnapshot() throws Exception {
        Path suite=fixture();helper.prepare(suite);
        try(var snapshot=new com.specdriven.regression.producttest.ProductTestHandoffService().snapshot(suite.resolve("suite_manifest.yaml"),"local_native",pin(suite))) {
            assertThat(snapshot.policy().nativeExecution()).isTrue();
            assertThat(snapshot.suiteManifest().getParent().resolve("fixtures/payload.json")).hasContent("{\"id\":\"reviewed\"}");
        }
    }
    @Test void changedPayloadAfterReviewBlocksBeforeProvider() throws Exception {
        Path suite=fixture();helper.prepare(suite);var pin=pin(suite);
        Files.writeString(suite.resolve("fixtures/payload.json"),"{\"id\":\"changed\"}");
        var result=new V03RuntimeExecutionService().runReviewed(suite.resolve("suite_manifest.yaml"),"local_native",SuiteExecutionContext.standalone("local_native",temporary.toRealPath().resolve("runs"),"V03"),pin);
        assertThat(result.status()).isEqualTo("blocked");assertThat(result.providerRuntimeExecuted()).isFalse();
        assertThat(result.findings().toString()).contains("file_digest_mismatch");
    }
    @Test void missingListenerProducesValidatedNativeFailedEvidence() throws Exception {
        var result=helper.run(fixture());assertThat(result.status()).as(result.findings().toString()).isEqualTo("failed");helper.assertValid(result);
        var json=helper.json.readTree(result.resultJson().toFile());
        assertThat(json.path("failure").path("code").asText()).isEqualTo("NATS_CONNECTION_FAILED");
        assertThat(json.path("provider_results").get(0).path("runtime_mode").asText()).isEqualTo("native");
        var provenance=helper.json.readTree(result.evidenceDir().resolve("product-test/reviewed-handoff.json").toFile());
        assertThat(provenance.path("observed_provider_modes").path("todo_api").asText()).isEqualTo("real");
    }
    @ParameterizedTest @ValueSource(strings={"remote","wildcard","extra_binding","override_subject","other_payload","missing_payload_review","shared"})
    void rejectedScopeNeverReachesNativeProvider(String variant) throws Exception {
        Path suite=fixture();var profile=helper.read(suite.resolve("env_profiles/local_native.yaml"));
        var binding=helper.map(helper.map(helper.map(profile.get("targets")).get("todo_api")).get("bindings"));
        var test=helper.read(suite.resolve("test_cases/items.yaml"));var input=helper.map(helper.map(((List<?>)test.get("execute")).get(0)).get("with"));
        switch(variant) {
            case "remote"->binding.put("connection.uri","nats://192.0.2.1:4222");
            case "wildcard"->binding.put("subject","sit.*.echo");
            case "extra_binding"->binding.put("connection.secret_ref","env://NATS_CONNECTION");
            case "override_subject"->input.put("subject","unowned");
            case "other_payload"->input.put("payload_ref","artifact://fixtures/other.json");
            case "shared"->helper.map(profile.get("product_test")).put("shared_state",Map.of("environment_id","SIT","namespace","test","owner","test"));
            case "missing_payload_review"->{ var review=helper.json.readValue(suite.resolve("review-input.json").toFile(),Map.class);((List<?>)review.get("reviewed_files")).removeIf(raw->helper.map(raw).get("path").equals("fixtures/payload.json"));helper.json.writeValue(suite.resolve("review-input.json").toFile(),review); }
        }
        helper.write(suite.resolve("env_profiles/local_native.yaml"),profile);helper.write(suite.resolve("test_cases/items.yaml"),test);
        assertThatThrownBy(()->helper.prepare(suite)).isInstanceOf(IllegalArgumentException.class);
    }
    InvocationRequirement pin(Path suite)throws Exception {return new InvocationRequirement(Files.readString(suite.resolve("handoff/reviewed.json.sha256")).trim());}
    Path fixture()throws Exception {
        helper.temporary=temporary;Path suite=helper.fixture("http://127.0.0.1:43199","GET",false);
        var manifest=helper.read(suite.resolve("suite_manifest.yaml"));helper.map(helper.map(manifest.get("targets")).get("todo_api")).put("provider_contract","nats.v0.3");
        helper.map(manifest.get("artifact_roots")).put("fixtures","fixtures/");helper.write(suite.resolve("suite_manifest.yaml"),manifest);
        var profile=helper.read(suite.resolve("env_profiles/local_native.yaml"));helper.map(helper.map(profile.get("targets")).get("todo_api")).put("bindings",Map.of("connection.uri","nats://127.0.0.1:43199","subject","sit."+"b".repeat(32)+".echo"));helper.write(suite.resolve("env_profiles/local_native.yaml"),profile);
        Files.createDirectory(suite.resolve("fixtures"));Files.writeString(suite.resolve("fixtures/payload.json"),"{\"id\":\"reviewed\"}");
        helper.write(suite.resolve("test_cases/items.yaml"),Map.of("dsl_version","v0.3","test_case_id","TC-HTTP","title","Reviewed NATS native publish","execute",List.of(Map.of("id","publish","target","todo_api","op","nats_publish","with",Map.of("payload_ref","artifact://fixtures/payload.json","timeout","PT0.2S"))),"verify",List.of(Map.of("id","published","type","assertion","assert",Map.of("actual","step://publish/published","operator","equals","expected",true)))));
        Files.copy(Path.of("docs/02-architecture/contracts/provider-contracts/nats_v0_3.yaml"),suite.resolve("contracts/nats.yaml"));
        var review=helper.json.readValue(suite.resolve("review-input.json").toFile(),Map.class);
        review.put("reviewed_contracts",List.of(Map.of("path","contracts/nats.yaml","provider_contract","nats.v0.3")));
        ((List<Object>)review.get("reviewed_files")).add(Map.of("path","fixtures/payload.json","role","fixture"));
        helper.map(((List<?>)review.get("requirement_mapping")).get(0)).put("oracle_refs",List.of("test_cases/items.yaml#published"));helper.json.writeValue(suite.resolve("review-input.json").toFile(),review);return suite;
    }
}

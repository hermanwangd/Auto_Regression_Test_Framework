package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.producttest.ProductTestHandoffService.InvocationRequirement;
import com.specdriven.regression.summary.SuiteExecutionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ReviewedEvidenceValidationTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    @Test void failedResultCannotOmitAnActuallyInvokedProviderMode() throws Exception {
        Path source = Path.of("samples/41-product-testing/assertion_failure");
        String pin = Files.readString(source.resolve("handoff/reviewed-tests.json.sha256")).trim();
        var run = new V03RuntimeExecutionService().runReviewed(source.resolve("suite_manifest.yaml"),"local_v03",
                SuiteExecutionContext.standalone("local_v03",temporary,"V03"),new InvocationRequirement(pin));
        assertThat(run.status()).isEqualTo("failed");
        Path evidence = run.evidenceDir().resolve("product-test/reviewed-handoff.json");
        ObjectNode document = (ObjectNode)json.readTree(evidence.toFile());
        document.putObject("observed_provider_modes");
        json.writeValue(evidence.toFile(),document);
        ObjectNode result = (ObjectNode)json.readTree(run.resultJson().toFile());
        ((ObjectNode)result.path("product_test")).put("provenance_sha256", java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(evidence))));
        json.writeValue(run.resultJson().toFile(),result);
        assertThat(new EvidenceHardeningService().validateResult(run.resultJson()).valid()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings={"missing_modes","wrong_modes","wrong_profile","wrong_pin","knowledge_downgrade","fake_release_claim","result_release_claim"})
    void directEvidenceValidationRejectsReviewedSemanticTampering(String alteration) throws Exception {
        Path source = Path.of("samples/41-product-testing/reviewed_local");
        String pin = Files.readString(source.resolve("handoff/reviewed-tests.json.sha256")).trim();
        var run = new V03RuntimeExecutionService().runReviewed(source.resolve("suite_manifest.yaml"),"local_v03",
                SuiteExecutionContext.standalone("local_v03",temporary,"V03"),new InvocationRequirement(pin));
        assertThat(run.status()).isEqualTo("passed");
        assertThat(new EvidenceHardeningService().validateResult(run.resultJson()).valid()).isTrue();
        Path evidence = run.evidenceDir().resolve("product-test/reviewed-handoff.json");
        ObjectNode document = (ObjectNode) json.readTree(evidence.toFile());
        switch(alteration) {
            case "missing_modes" -> document.remove("observed_provider_modes");
            case "wrong_modes" -> document.putObject("observed_provider_modes").put("sample_runtime","real");
            case "wrong_profile" -> document.put("profile","different-profile");
            case "wrong_pin" -> document.put("handoff_sha256","b".repeat(64));
            case "knowledge_downgrade" -> document.put("knowledge_mode","required");
            case "fake_release_claim" -> {
                document.put("evidence_classification","release_candidate");
                document.put("downstream_release_evidence",true);
            }
            case "result_release_claim" -> {
                ObjectNode result = (ObjectNode) json.readTree(run.resultJson().toFile());
                ((ObjectNode)result.path("labels")).put("evidence_classification","release_candidate")
                        .put("downstream_release_evidence",true);
                json.writeValue(run.resultJson().toFile(),result);
            }
        }
        json.writeValue(evidence.toFile(),document);
        // Even a self-consistent replacement of the local payload digest must fail the semantic checks.
        ObjectNode result = (ObjectNode) json.readTree(run.resultJson().toFile());
        ((ObjectNode)result.path("product_test")).put("provenance_sha256", java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(evidence))));
        json.writeValue(run.resultJson().toFile(),result);
        var checked = new EvidenceHardeningService().validateResult(run.resultJson());
        assertThat(checked.valid()).as(alteration).isFalse();
        assertThat(checked.findings().toString()).contains("reviewed_evidence");
    }
}

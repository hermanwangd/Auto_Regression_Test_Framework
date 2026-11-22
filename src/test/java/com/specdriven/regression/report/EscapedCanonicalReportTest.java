package com.specdriven.regression.report;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specdriven.regression.cli.RegressionCommand;
import com.specdriven.regression.contract.ContractBaselineService;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/** Result-parser regressions; real TCP/provider evidence is retained separately by the fixture owner. */
class EscapedCanonicalReportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;
    private Path fixture(boolean escaped) throws Exception {
        Path source = Path.of("samples/40-evidence-reporting/evidence_hardening"), target = root.resolve("source");
        try (var files = Files.walk(source)) { for (Path file : files.toList()) {
            Path to = target.resolve(source.relativize(file));
            if (Files.isDirectory(file)) Files.createDirectories(to); else Files.copy(file, to);
        }}
        Path result = target.resolve("valid_result.json");
        if (escaped) {
            ObjectNode data = (ObjectNode)JSON.readTree(result.toFile());
            String body = JSON.writeValueAsString(Map.of("message", "\"".repeat(500000)));
            assertThat(body.getBytes(StandardCharsets.UTF_8).length).isLessThan(1024 * 1024);
            ((ObjectNode)data.path("steps").get(0)).putObject("outputs").put("response.message", body);
            data.set("step_results", data.path("steps").deepCopy());
            ((ObjectNode)data.path("provider_results").get(0).path("resolved_operation_result")).putObject("outputs").put("response.message", body);
            JSON.writeValue(result.toFile(), data);
            assertThat(Files.size(result)).isGreaterThan(4 * 1024 * 1024).isLessThan(8 * 1024 * 1024);
        }
        return result;
    }
    private record Run(int exit, JsonNode report, String error) {}
    private Run report(Path result, boolean typed) throws Exception {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        String[] args = typed ? new String[]{"report", "--result", result.toString(), "--format", "json", "--coverage-dir", root.resolve("coverage").toString()}
                : new String[]{"report", "--result", result.toString(), "--format", "json"};
        int exit = new RegressionCommand(new ProductRepoService(), new ReleasePackageService()).execute(args, new PrintStream(out), new PrintStream(err));
        return new Run(exit, JSON.readTree(out.toByteArray()), err.toString());
    }
    @Test void legalEscapedResultIsValidatedAndProjectedWithoutYamlBudget() throws Exception {
        Path result = fixture(true);
        var baseline = new ContractBaselineService().report(result);
        assertThat(baseline.valid()).as(baseline.findings().toString()).isTrue();
        var evidence = new EvidenceHardeningService();
        assertThat(evidence.hasEvidenceIndexReference(result)).isTrue();
        assertThat(evidence.validateResult(result).valid()).isTrue();
        Run run = report(result, true); assertThat(run.exit).as(run.report + run.error).isZero();
        assertThat(run.report.path("report_status").asText()).isEqualTo("review_ready");
        assertThat(run.report.path("masking_status").asText()).isEqualTo("passed");
        JsonNode coverage = JSON.readTree(root.resolve("coverage/coverage.json").toFile());
        assertThat(coverage.at("/risk_inventory/state").asText()).isEqualTo("UNKNOWN");
        assertThat(coverage.at("/cases/0/evidence_state").asText()).isEqualTo("VALID");
    }
    @Test void largeResultCannotHideMissingIndexedEvidence() throws Exception {
        Path result = fixture(true); Files.delete(result.getParent().resolve("evidence/provider_evidence.json"));
        var evidence = new EvidenceHardeningService();
        assertThat(evidence.hasEvidenceIndexReference(result)).isTrue();
        var validation = evidence.validateResult(result);
        assertThat(validation.valid()).isFalse(); assertThat(validation.missingEvidenceCount()).isGreaterThan(0);
        Run run = report(result, true); assertThat(run.exit).isEqualTo(1);
        assertThat(run.report.path("report_status").asText()).isEqualTo("failed");
        assertThat(run.report.path("failure_code").asText()).isEqualTo("EVIDENCE_VALIDATION_FAILED");
    }
    @Test void detectorParseFailureIsNotAnAbsentEvidenceReference() throws Exception {
        Path result = root.resolve("malformed-result.json"); Files.writeString(result, "{\"dsl_version\":\"v0.3\", broken");
        assertThatThrownBy(() -> new EvidenceHardeningService().hasEvidenceIndexReference(result))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("evidence");
    }
    @Test void duplicateModernJsonCannotDowngradeThroughLegacyYaml() throws Exception {
        Path result = fixture(false); String text = Files.readString(result);
        Files.writeString(result, text.replaceFirst("\\{", "{\"dsl_version\":\"v0.3\","));
        assertThat(new ContractBaselineService().report(result).valid()).isFalse();
        assertThat(new EvidenceHardeningService().validateResult(result).valid()).isFalse();
        Run run = report(result, false); assertThat(run.exit).isEqualTo(1);
    }
    @Test void knownLegacyBlockYamlStillUsesItsExistingCompatibilityPath() throws Exception {
        Path result = fixture(false);
        Map<?,?> data = JSON.readValue(result.toFile(), Map.class);
        Files.writeString(result, new Yaml().dump(data));
        assertThat(new ContractBaselineService().report(result).valid()).isTrue();
        assertThat(new EvidenceHardeningService().validateResult(result).valid()).isTrue();
        assertThat(report(result, false).exit).isZero();
    }
    @Test void escapedResultDoesNotSilentlyLoseSummaryFields() throws Exception {
        Path result=fixture(true);ObjectNode data=(ObjectNode)JSON.readTree(result.toFile());
        data.put("result_contract_version","v0.3").put("suite_summary_ref","suite_summary.json");JSON.writeValue(result.toFile(),data);
        JSON.writeValue(result.getParent().resolve("suite_summary.json").toFile(),Map.of("suite_summary_version","v0.3","completion_status","complete","duration_ms",1000));
        Map<String,Object> report=new java.util.LinkedHashMap<>();
        var method=RegressionCommand.class.getDeclaredMethod("addSuiteSummaryReportFields",Map.class,Path.class);method.setAccessible(true);
        method.invoke(new RegressionCommand(new ProductRepoService(),new ReleasePackageService()),report,result);
        assertThat(report).containsEntry("completion_status","complete").containsEntry("suite_summary_version","v0.3");
    }
    @Test void resultJsonFileAndProviderStringLimitsRemainBounded() throws Exception {
        Path result=fixture(false);ObjectNode data=(ObjectNode)JSON.readTree(result.toFile());data.put("unexpected_payload","q".repeat(1024*1024+1));JSON.writeValue(result.toFile(),data);
        assertThat(new ContractBaselineService().report(result).valid()).isFalse();
        Files.writeString(result," ".repeat(8*1024*1024+1)+"{}");
        assertThat(new ContractBaselineService().report(result).valid()).isFalse();
    }

}

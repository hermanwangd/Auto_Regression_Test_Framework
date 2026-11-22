package com.specdriven.regression.report;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** No risk authority was supplied; requirement inventory cannot supply one implicitly. */
class CoverageRiskInventoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test void absentRiskAuthorityIsExplicitInMachineAndHumanArtifacts() throws Exception {
        Path result = Path.of("samples/40-evidence-reporting/evidence_hardening/valid_result.json");
        var projected = new CoverageReportService().projectCanonical(result, root.resolve("report"), null, null);
        var risk = projected.document().path("risk_inventory");
        assertThat(risk.path("state").asText()).isEqualTo("UNKNOWN");
        for (String field : List.of("authority", "revision", "denominator")) assertThat(risk.path(field).isNull()).as(field).isTrue();
        assertThat(risk.path("reason").asText()).isEqualTo("risk_inventory_missing");
        assertThat(projected.document().path("gaps").toString()).contains("risk_inventory_missing");
        assertThat(Files.readString(projected.markdown())).contains("risks", "UNKNOWN", "risk_inventory_missing");
    }

    @Test void knownEmptyRequirementsDoNotCreateAKnownEmptyRiskInventory() throws Exception {
        Path source = Path.of("samples/40-evidence-reporting/evidence_hardening"), target = root.resolve("source");
        try (var files = Files.walk(source)) { for (Path file : files.toList()) {
            Path to = target.resolve(source.relativize(file));
            if (Files.isDirectory(file)) Files.createDirectories(to); else Files.copy(file, to);
        }}
        Path result = target.resolve("valid_result.json");
        ObjectNode data = (ObjectNode)JSON.readTree(result.toFile());
        data.put("project_ref", "project").put("workspace_ref", "workspace"); data.putObject("repository_revisions").put("framework", "revision"); JSON.writeValue(result.toFile(), data);
        ObjectNode catalog = JSON.createObjectNode().put("schema_version", "coverage-catalog.v1").put("catalog_revision", "reviewed-test-fixture");
        catalog.set("scope", JSON.valueToTree(Map.of("domain", "product", "project_ref", "project", "workspace_ref", "workspace", "repository_revisions", Map.of("framework", "revision"))));
        catalog.set("authority", JSON.valueToTree(Map.of("kind", "reviewed_inventory", "source_ref", "unit-fixture", "source_sha256", "a".repeat(64), "review_ref", "unit-fixture", "approved_by", "fixture-owner", "approved_at", "2026-06-29T00:00:00Z")));
        catalog.putArray("requirements"); catalog.putArray("acceptance_criteria"); Path catalogPath = root.resolve("catalog.json"); JSON.writeValue(catalogPath.toFile(), catalog);
        var report = new CoverageReportService().projectCanonical(result, root.resolve("report"), catalogPath, null).document();
        assertThat(report.at("/inventory/state").asText()).isEqualTo("KNOWN_EMPTY");
        assertThat(report.at("/metrics/requirements/denominator").asInt()).isZero();
        assertThat(report.at("/risk_inventory/state").asText()).isEqualTo("UNKNOWN");
        assertThat(report.at("/risk_inventory/denominator").isNull()).isTrue();
        assertThat(report.path("report_status").asText()).isEqualTo("INCOMPLETE");
    }
}

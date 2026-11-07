package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Consumes the framework-owned external projection after canonical validation. */
final class CoverageArtifacts {
    private CoverageArtifacts() {}

    static Map<String, ProductTestReceipt.Artifact> verify(
            Path directory, Path frameworkReport, Path canonicalResult, ProductTestRequest request) throws Exception {
        Path root = LocalFiles.real(directory);
        Path canonical = LocalFiles.real(canonicalResult);
        require(!root.startsWith(canonical.getParent()), "Coverage output is inside canonical directory");
        JsonNode report = Json.read(frameworkReport);
        Path json = pairPath(root, Json.required(report, "coverage_json"));
        Path markdown = pairPath(root, Json.required(report, "coverage_md"));
        require(json.equals(root.resolve("coverage.json")) && markdown.equals(root.resolve("coverage.md")),
                "Unexpected coverage pair filenames");
        byte[] jsonBytes = LocalFiles.read(json, 8 * 1024 * 1024);
        byte[] markdownBytes = LocalFiles.read(markdown, 8 * 1024 * 1024);
        String jsonDigest = LocalFiles.sha256(jsonBytes), markdownDigest = LocalFiles.sha256(markdownBytes);
        require(jsonDigest.equals(Json.required(report, "coverage_json_sha256"))
                && markdownDigest.equals(Json.required(report, "coverage_md_sha256")), "Coverage artifact digest mismatch");
        JsonNode coverage = Json.MAPPER.readTree(jsonBytes), result = Json.readCanonical(canonical, request);
        require("coverage_report.v1".equals(Json.required(coverage, "schema_version")), "Unsupported coverage schema");
        require(Set.of("VALID", "INCOMPLETE").contains(Json.required(coverage, "report_status"))
                && Json.required(coverage, "report_status").equals(Json.required(report, "coverage_report_status")),
                "Invalid coverage report status");
        require("GENERATED".equals(Json.required(coverage, "generation_status"))
                && "PUBLISHED".equals(Json.required(coverage, "publication_status")), "Coverage pair publication incomplete");
        require("framework_verification_only".equals(Json.required(coverage, "evidence_classification"))
                && coverage.path("downstream_release_evidence").isBoolean()
                && !coverage.path("downstream_release_evidence").booleanValue(), "Coverage cannot become release evidence");
        JsonNode subject = coverage.path("subject");
        for (String field : List.of("suite_id", "profile", "run_id", "batch_id"))
            require(Json.required(result, field).equals(Json.required(subject, field)), "Coverage identity mismatch: " + field);
        require(request.projectRef().equals(Json.required(subject, "project_ref"))
                && request.workspaceRef().equals(Json.required(subject, "workspace_ref"))
                && Json.MAPPER.valueToTree(request.repositoryRevisions()).equals(subject.path("repository_revisions")),
                "Coverage request/revision identity mismatch");
        require(outcome(Json.required(result, "status")).equals(Json.required(coverage, "canonical_outcome")),
                "Coverage canonical outcome mismatch");
        boolean sourceFound = false;
        require(coverage.path("sources").isArray() && !coverage.path("sources").isEmpty(), "Coverage sources missing");
        for (JsonNode source : coverage.path("sources")) {
            Path path = LocalFiles.contained(canonical.getParent(), Path.of(Json.required(source, "path")));
            require(path.equals(canonical) && "SELECTED".equals(Json.required(source, "selection_state"))
                    && LocalFiles.sha256(path).equals(Json.required(source, "sha256")), "Coverage source digest/identity mismatch");
            require(!sourceFound, "Duplicate coverage canonical source");
            sourceFound = true;
        }
        require(sourceFound, "Coverage canonical source missing");
        boolean canonicalLinked = false;
        require(coverage.path("evidence_links").isArray(), "Coverage evidence references missing");
        for (JsonNode link : coverage.path("evidence_links")) {
            Path path = LocalFiles.contained(canonical.getParent(), Path.of(Json.required(link, "path")));
            require(LocalFiles.sha256(path).equals(Json.required(link, "sha256")), "Coverage referenced artifact digest mismatch");
            canonicalLinked |= path.equals(canonical);
        }
        require(canonicalLinked, "Coverage result evidence reference missing");
        Map<String, String> cases = new LinkedHashMap<>();
        for (JsonNode test : result.path("test_results"))
            require(cases.put(Json.required(test, "test_case_id"), outcome(Json.required(test, "status"))) == null,
                    "Duplicate canonical test identity");
        require(coverage.path("cases").isArray() && coverage.path("cases").size() == cases.size(), "Coverage case count mismatch");
        for (JsonNode test : coverage.path("cases")) {
            String expected = cases.remove(Json.required(test, "test_case_id"));
            require(expected != null && expected.equals(Json.required(test, "canonical_outcome"))
                    && canonical.toString().equals(Json.required(test, "source_result")), "Coverage case outcome/source identity mismatch");
        }
        require(cases.isEmpty(), "Coverage canonical cases missing");
        ReviewedCoverageProjection.verify(coverage,canonical,result,request);
        if ("UNKNOWN".equals(coverage.path("inventory").path("state").asText()))
            for (String metric : List.of("requirements", "acceptance_criteria"))
                require(coverage.path("metrics").path(metric).path("denominator").isNull()
                        && coverage.path("metrics").path(metric).path("satisfied_percent").isNull(),
                        "UNKNOWN inventory cannot have a denominator or coverage percent");
        require(jsonDigest.equals(LocalFiles.sha256(json)) && markdownDigest.equals(LocalFiles.sha256(markdown)),
                "Coverage pair changed during validation");
        return Map.of("coverage_json", new ProductTestReceipt.Artifact(json.toString(), jsonDigest),
                "coverage_md", new ProductTestReceipt.Artifact(markdown.toString(), markdownDigest));
    }

    private static Path pairPath(Path root, String path) throws java.io.IOException {
        try { return LocalFiles.contained(root, Path.of(path)); }
        catch (java.nio.file.NoSuchFileException e) { throw new java.io.IOException("Missing regular file in coverage pair: " + path, e); }
    }

    private static String outcome(String value) {
        return switch (value) { case "passed" -> "PASS"; case "failed" -> "FAIL";
            case "blocked" -> "BLOCKED"; case "skipped" -> "SKIPPED"; default -> "NOT_RUN"; };
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

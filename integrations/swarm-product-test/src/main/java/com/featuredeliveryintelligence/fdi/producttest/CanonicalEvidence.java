package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class CanonicalEvidence {
    private final Path result;
    private final JsonNode document;
    private final Map<String, ProductTestReceipt.Artifact> artifacts;
    private final Map<String, String> treeDigests;

    private CanonicalEvidence(Path result, JsonNode document, Map<String, ProductTestReceipt.Artifact> artifacts, Map<String, String> treeDigests) {
        this.result = result; this.document = document; this.artifacts = Map.copyOf(artifacts); this.treeDigests = Map.copyOf(treeDigests);
    }

    static CanonicalEvidence inspect(ProductTestRequest request, Path output, String stdout, Instant started) throws Exception {
        Map<String, String> lines = outputFields(stdout);
        require(lines.containsKey("result_json"), "Missing canonical result location");
        Path result = LocalFiles.contained(output, Path.of(lines.get("result_json")));
        require(result.getFileName().toString().equals("result.json"), "Unexpected canonical result filename");
        var document = Json.read(result);
        for (String field : List.of("suite_id", "profile", "batch_id", "run_id"))
            require(Json.required(document, field).equals(lines.get(field)), "CLI/result identity mismatch: " + field);
        require(request.suiteId().equals(Json.required(document, "suite_id"))
                && request.profile().equals(Json.required(document, "profile")), "Request/result identity mismatch");
        String runId = Json.required(document, "run_id");
        require(runId.matches("RUN-(?:[A-Za-z0-9]+-)?[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"), "Non-randomized run identity");
        String batch = Json.required(document, "batch_id");
        require(batch.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"), "Invalid batch identity");
        Path run = output.resolve("target/regression").resolve(request.suiteId()).resolve(batch).resolve(runId);
        require(result.equals(run.resolve("result.json")), "Canonical result escaped its expected fresh run directory");
        require("v0.3".equals(Json.required(document, "dsl_version"))
                && "v0.3".equals(Json.required(document, "result_contract_version")), "Unsupported canonical result version");
        require(Set.of("passed", "failed", "blocked", "skipped").contains(Json.required(document, "status")), "Unknown framework verdict");
        Instant start = Instant.parse(Json.required(document, "start_time"));
        Instant end = Instant.parse(Json.required(document, "end_time"));
        require(!start.isBefore(started.minusMillis(1000)) && !end.isBefore(start) && !end.isAfter(Instant.now().plusSeconds(1)),
                "Canonical result does not belong to this fresh invocation");
        require("framework_verification_only".equals(Json.required(document.path("labels"), "evidence_classification"))
                && explicitFalse(document.path("labels").path("downstream_release_evidence")), "Local result cannot become release evidence");
        rejectReleaseClaims(document);
        Map<String, String> tree = tree(run);
        validateReferences(document, run);
        Path index = relative(run, Json.required(document, "evidence_index_ref"));
        Path summary = relative(run, Json.required(document, "suite_summary_ref"));
        var summaryDocument = Json.read(summary);
        matchIdentity(document, summaryDocument, true);
        require(!summaryDocument.path("children").isArray() || summaryDocument.path("children").isEmpty(), "Only leaf suites are supported");
        validateReferences(summaryDocument, run);
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(2 * 1024 * 1024); options.setNestingDepthLimit(50);
        Object indexObject = new Yaml(new SafeConstructor(options)).load(new String(LocalFiles.read(index, 2 * 1024 * 1024), StandardCharsets.UTF_8));
        var indexDocument = Json.MAPPER.valueToTree(indexObject);
        matchIdentity(document, indexDocument, false);
        require(indexDocument.path("entries").isArray() && !indexDocument.path("entries").isEmpty(), "Missing evidence entries");
        for (JsonNode entry : indexDocument.path("entries")) {
            require(runId.equals(Json.required(entry, "run_id")) && batch.equals(Json.required(entry, "batch_id")), "Evidence entry identity mismatch");
            relative(run, Json.required(entry, "file_path"));
        }
        Path handoffEvidence = relative(run, "product-test/reviewed-handoff.json");
        require(contains(document.path("evidence_refs"), "product-test/reviewed-handoff.json")
                && containsFile(indexDocument.path("entries"), "product-test/reviewed-handoff.json"), "Reviewed handoff evidence is not indexed");
        var provenance = Json.read(handoffEvidence);
        matchIdentity(document, provenance, false);
        require(Json.required(document, "plan_digest").equals(Json.required(provenance, "plan_digest")), "Reviewed plan identity mismatch");
        require(request.expectedHandoffSha256().equals(Json.required(provenance, "handoff_sha256")), "Reviewed handoff digest mismatch");
        require(request.knowledgeMode().equals(Json.required(provenance, "knowledge_mode")), "Reviewed knowledge mode mismatch");
        require(request.testLevel().equals(Json.required(provenance, "test_level"))
                && request.testType().equals(Json.required(provenance, "test_type"))
                && request.environmentStage().equals(Json.required(provenance, "environment_stage")), "Reviewed capability tuple mismatch");
        require(request.workspaceRef().equals(Json.required(provenance, "workspace_ref"))
                && request.projectRef().equals(Json.required(provenance, "project_ref")), "Reviewed evidence scope mismatch");
        require(Json.MAPPER.valueToTree(request.repositoryRevisions()).equals(provenance.path("repository_revisions")), "Repository revision identity mismatch");
        require("framework_verification_only".equals(Json.required(provenance, "evidence_classification"))
                && explicitFalse(provenance.path("downstream_release_evidence")), "Reviewed evidence cannot become release evidence");
        boolean passed = "passed".equals(Json.required(document, "status"));
        boolean nativeExecution = "integration".equals(request.testLevel());
        validateModes(provenance, "provider_modes", "observed_provider_modes", true, passed, nativeExecution ? "real" : "simulated");
        validateModes(provenance, "dependency_modes", "observed_dependency_modes", false, passed, "simulated");
        if (nativeExecution) {
            byte[] reviewedBytes = LocalFiles.read(Path.of(request.reviewedHandoff()), 2 * 1024 * 1024);
            require(request.expectedHandoffSha256().equals(LocalFiles.sha256(reviewedBytes)), "Reviewed handoff digest mismatch");
            var contracts = Json.MAPPER.readTree(reviewedBytes).path("reviewed_contracts");
            require(contracts.isArray() && contracts.size() == 1 && contracts.equals(provenance.path("reviewed_contracts")),
                    "Native reviewed contract differs from pinned handoff");
            String contract = Json.required(contracts.get(0), "provider_contract");
            require(Set.of("rest_client.v0.3", "cli_process.v0.3").contains(contract), "Unsupported native provider contract");
            String type = contract.equals("cli_process.v0.3") ? "cli_process" : "rest_client";
            String operation = type.equals("cli_process") ? "run_command" : "http_request";
            require("not_used".equals(request.knowledgeMode()) && provenance.path("provider_modes").size() == 1
                    && provenance.path("dependency_modes").isEmpty(), "Unsupported native capability metadata");
            require(document.path("provider_results").isArray() && document.path("step_results").isArray()
                    && document.path("provider_results").size() <= 1 && document.path("step_results").size() <= 1
                    && (!passed || document.path("provider_results").size() == 1 && document.path("step_results").size() == 1),
                    "Native evidence must describe one selected operation");
            for (var provider : document.path("provider_results")) require(type.equals(provider.path("provider_type").asText())
                    && contract.equals(provider.path("provider_contract").asText())
                    && "native".equals(provider.path("runtime_mode").asText()), "Native provider metadata mismatch");
            for (var step : document.path("step_results")) require("execute".equals(step.path("phase").asText())
                    && operation.equals(step.path("operation").asText()), "Native operation metadata mismatch");
            for (var provider : document.path("provider_results")) {
                require(operation.equals(provider.path("operation").asText())
                        && Json.MAPPER.valueToTree(List.of(operation)).equals(provider.path("operations")),
                        "Native provider operation metadata mismatch");
                var resolved = provider.path("resolved_operation_results");
                require(resolved.isArray() && resolved.size() <= 1 && (!passed || resolved.size() == 1),
                        "Native resolved operation count mismatch");
                for (var observed : resolved) require(operation.equals(observed.path("operation").asText()),
                        "Native resolved operation metadata mismatch");
            }
        }
        var artifacts = new LinkedHashMap<String, ProductTestReceipt.Artifact>();
        for (var entry : tree.entrySet()) artifacts.put("evidence:" + entry.getKey(), new ProductTestReceipt.Artifact(run.resolve(entry.getKey()).toString(), entry.getValue()));
        for (var entry : Map.of("result", result, "suite_summary", summary, "evidence_index", index, "reviewed_handoff", handoffEvidence).entrySet())
            artifacts.put(entry.getKey(), new ProductTestReceipt.Artifact(entry.getValue().toString(), LocalFiles.sha256(entry.getValue())));
        return new CanonicalEvidence(result, document, artifacts, tree);
    }
    Path result() { return result; }
    String verdict() { return Json.required(document, "status"); }
    String runId() { return Json.required(document, "run_id"); }
    Map<String, ProductTestReceipt.Artifact> artifacts() { return artifacts; }
    void verifyReport(Path report) throws Exception {
        var reported = Json.read(report);
        matchIdentity(document, reported, true);
        require(explicitFalse(reported.path("release_evidence_eligible")), "Local framework report cannot become release evidence");
        require(Set.of("review_ready", "review_ready_with_failures").contains(Json.required(reported, "report_status")), "Framework report is not reviewable");
        require("passed".equals(Json.required(reported, "masking_status")), "Framework report masking failed");
    }
    void verifyUnchanged() throws Exception {
        require(treeDigests.equals(tree(result.getParent())), "Canonical evidence changed during validation");
    }
    private static Map<String, String> outputFields(String stdout) {
        var fields = new LinkedHashMap<String, String>();
        for (String line : stdout.split("\\R")) {
            int colon = line.indexOf(": "); if (colon < 0) continue;
            String key = line.substring(0, colon);
            if (Set.of("result_json", "suite_id", "profile", "batch_id", "run_id").contains(key))
                require(fields.putIfAbsent(key, line.substring(colon + 2)) == null, "Ambiguous CLI identity: " + key);
        }
        return fields;
    }
    private static void matchIdentity(JsonNode expected, JsonNode actual, boolean status) {
        for (String key : List.of("suite_id", "profile", "batch_id", "run_id"))
            require(Json.required(expected, key).equals(Json.required(actual, key)), "Canonical evidence identity mismatch: " + key);
        if (status) require(Json.required(expected, "status").equals(Json.required(actual, "status")), "Canonical verdict mismatch");
    }
    private static void validateModes(JsonNode provenance, String declaredKey, String observedKey, boolean nonempty, boolean requireComplete, String mode) {
        var declared = provenance.path(declaredKey); var observed = provenance.path(observedKey);
        require(declared.isObject() && observed.isObject() && (!nonempty || !declared.isEmpty()), "Missing declared/observed mode maps");
        if (requireComplete) require(declared.equals(observed), "Declared/observed mode mismatch");
        observed.fields().forEachRemaining(entry -> require(entry.getValue().equals(declared.path(entry.getKey())), "Observed mode does not match declaration"));
        declared.elements().forEachRemaining(value -> require(value.isTextual() && mode.equals(value.asText()), "Unsupported local provider/dependency mode"));
    }
    private static boolean explicitFalse(JsonNode node) { return node.isBoolean() && !node.booleanValue(); }
    private static void rejectReleaseClaims(JsonNode node) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            if (Set.of("release_evidence_eligible", "downstream_release_evidence").contains(entry.getKey()))
                require(explicitFalse(entry.getValue()), "Local fixture cannot become release evidence");
            rejectReleaseClaims(entry.getValue());
        });
        else if (node.isArray()) node.forEach(CanonicalEvidence::rejectReleaseClaims);
    }
    private static Map<String, String> tree(Path root) throws IOException {
        var result = new LinkedHashMap<String, String>(); long bytes = 0; int count = 0;
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                require(++count <= 4096, "Evidence tree exceeds file limit");
                if (Files.isSymbolicLink(path)) throw new IOException("Symlink evidence is forbidden");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Special evidence file is forbidden");
                long size = Files.size(path); bytes += size;
                require(size <= 4 * 1024 * 1024 && bytes <= 64L * 1024 * 1024, "Evidence exceeds byte limit");
                result.put(root.relativize(path).toString(), LocalFiles.sha256(path));
            }
        }
        return result;
    }
    private static Path relative(Path root, String value) throws IOException {
        Path ref = Path.of(value);
        require(!ref.isAbsolute() && ref.getNameCount() > 0 && ref.normalize().equals(ref)
                && !value.contains("\\") && !value.contains(":") && !value.startsWith("..") && !value.isBlank(), "Evidence reference must be a normalized contained relative path");
        Path file = LocalFiles.contained(root, root.resolve(ref));
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "Missing regular evidence file");
        return file;
    }
    private static void validateReferences(JsonNode node, Path root) throws IOException {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (Set.of("evidence_index_ref", "suite_summary_ref", "result_ref", "file_path").contains(entry.getKey()))
                    relative(root, entry.getValue().asText());
                else if (Set.of("evidence_refs", "provider_evidence_refs").contains(entry.getKey())) {
                    require(entry.getValue().isArray(), "Evidence references must be arrays");
                    for (JsonNode ref : entry.getValue()) { require(ref.isTextual(), "Evidence reference must be textual"); relative(root, ref.asText()); }
                } else validateReferences(entry.getValue(), root);
            }
        } else if (node.isArray()) for (JsonNode child : node) validateReferences(child, root);
    }
    private static boolean contains(JsonNode values, String expected) {
        for (JsonNode value : values) if (value.isTextual() && value.asText().equals(expected)) return true;
        return false;
    }
    private static boolean containsFile(JsonNode entries, String expected) {
        for (JsonNode entry : entries) if (entry.path("file_path").asText().equals(expected)) return true;
        return false;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}

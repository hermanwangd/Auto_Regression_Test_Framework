package com.featuredeliveryintelligence.fdi.producttest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class DefaultProductTestingModule implements ProductTestingModule {
    private final FrameworkExecutionPort framework;
    public DefaultProductTestingModule(FrameworkExecutionPort framework) { this.framework = Objects.requireNonNull(framework); }
    @Override public String id() { return "product-test"; }
    @Override public String version() { return "1"; }
    @Override public ProductTestReceipt execute(ProductTestRequest request) {
        try { validateStructure(request); }
        catch (Exception e) { return Receipts.failure(request, ProductTestReceipt.Outcome.REJECTED, e.getMessage()); }
        return framework.execute(request);
    }

    static void validateStructure(ProductTestRequest request) {
        Objects.requireNonNull(request, "Request is required");
        require("product-test-request.v1".equals(request.contractVersion()), "Unsupported request contract");
        require("product-test".equals(request.moduleId()) && "1".equals(request.moduleVersion()), "Unknown module ID or incompatible version");
        require(List.of("component", "integration").contains(request.testLevel()) && "regression".equals(request.testType())
                && "local".equals(request.environmentStage()), "Unsupported capability; supported tuples are component or integration/regression/local");
        require(List.of("required", "not_used").contains(request.knowledgeMode()), "Unsupported knowledge mode");
        require(!"integration".equals(request.testLevel()) || "not_used".equals(request.knowledgeMode()),
                "Native local integration requires not_used knowledge mode");
        for (String value : List.of(request.missionRef(), request.requestRef(), request.workspaceRef(), request.projectRef(), request.executionRevision(),
                request.suiteId(), request.profile()))
            require(value != null && !value.isBlank() && value.length() <= 256 && value.chars().noneMatch(Character::isISOControl), "Invalid correlation or suite identity");
        require(request.suiteId().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                && request.profile().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"), "Invalid suite/profile identifier");
        require(!request.repositoryRevisions().isEmpty() && request.repositoryRevisions().size() <= 64, "Repository revisions are required");
        for (var entry : request.repositoryRevisions().entrySet())
            require(!entry.getKey().isBlank() && entry.getKey().length() <= 256
                    && entry.getValue().matches("[a-f0-9]{40}|[a-f0-9]{64}"), "Repository revisions must be exact commit identities");
        require(!request.requirementTestMapping().isEmpty() && request.requirementTestMapping().size() <= 512, "Requirement-to-test mapping is required");
        for (var entry : request.requirementTestMapping().entrySet())
            require(!entry.getKey().isBlank() && entry.getKey().length() <= 256 && !entry.getValue().isEmpty()
                    && entry.getValue().size() <= 512 && entry.getValue().stream().allMatch(v -> !v.isBlank() && v.length() <= 256)
                    && entry.getValue().stream().distinct().count() == entry.getValue().size(), "Invalid requirement-to-test mapping");
        require(request.expectedHandoffSha256() != null && request.expectedHandoffSha256().matches("[a-f0-9]{64}"), "Invalid handoff digest pin");
        absolute(request.artifactRoot()); absolute(request.suiteManifest()); absolute(request.reviewedHandoff());
    }

    /** Only called after the production transport checks its independent pin and approved directory. */
    static void validateReviewedInputs(ProductTestRequest request) throws Exception {
        Path root = LocalFiles.real(absolute(request.artifactRoot()));
        Path suite = LocalFiles.contained(root, absolute(request.suiteManifest()));
        Path handoff = LocalFiles.contained(suite.getParent(), absolute(request.reviewedHandoff()));
        byte[] reviewedBytes = LocalFiles.read(handoff, 2 * 1024 * 1024);
        require(LocalFiles.sha256(reviewedBytes).equals(request.expectedHandoffSha256()), "Reviewed handoff digest mismatch");
        var document = Json.MAPPER.readTree(reviewedBytes);
        require(request.suiteId().equals(Json.required(document, "suite_id"))
                && request.profile().equals(Json.required(document, "profile")), "Reviewed suite/profile identity mismatch");
        require(request.knowledgeMode().equals(Json.required(document, "knowledge_mode")), "Reviewed knowledge mode mismatch");
        require(request.workspaceRef().equals(document.path("workspace_ref").asText())
                && request.projectRef().equals(document.path("project_ref").asText()), "Reviewed workspace/project scope mismatch");
        require(Json.MAPPER.valueToTree(request.repositoryRevisions()).equals(document.path("repository_revisions")), "Reviewed repository revision mismatch");
        var reviewedMapping = new java.util.LinkedHashMap<String, List<String>>();
        require(document.path("requirement_mapping").isArray(), "Reviewed requirement mapping is missing");
        for (var entry : document.path("requirement_mapping")) {
            String requirement = Json.required(entry, "requirement_ref");
            require(entry.path("test_case_ids").isArray(), "Reviewed test case mapping is missing");
            var ids = new java.util.ArrayList<String>();
            for (var id : entry.path("test_case_ids")) { require(id.isTextual(), "Invalid reviewed test case identity"); ids.add(id.asText()); }
            require(reviewedMapping.putIfAbsent(requirement, List.copyOf(ids)) == null, "Duplicate reviewed requirement mapping");
        }
        require(request.requirementTestMapping().equals(reviewedMapping), "Reviewed requirement-to-test mapping mismatch");
        require(document.path("reviewed_files").isArray(), "Reviewed profile binding is missing");
        var profiles = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (var entry : document.path("reviewed_files")) if ("profile".equals(entry.path("role").asText())) profiles.add(entry);
        require(profiles.size() == 1, "Exactly one reviewed profile is required");
        var profileEntry = profiles.get(0);
        Path profileRef = Path.of(Json.required(profileEntry, "path"));
        require(!profileRef.isAbsolute() && profileRef.normalize().equals(profileRef) && !profileRef.startsWith(".."),
                "Reviewed profile must be a contained relative path");
        byte[] profileBytes = LocalFiles.read(LocalFiles.contained(suite.getParent(), suite.getParent().resolve(profileRef)), 2 * 1024 * 1024);
        require(LocalFiles.sha256(profileBytes).equals(Json.required(profileEntry, "sha256")), "Reviewed profile digest mismatch");
        var yamlOptions = new org.yaml.snakeyaml.LoaderOptions();
        yamlOptions.setAllowDuplicateKeys(false); yamlOptions.setMaxAliasesForCollections(0);
        yamlOptions.setCodePointLimit(2 * 1024 * 1024); yamlOptions.setNestingDepthLimit(50);
        var profileDocument = Json.MAPPER.valueToTree(new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(yamlOptions))
                .load(new String(profileBytes, java.nio.charset.StandardCharsets.UTF_8)));
        var policy = profileDocument.path("product_test");
        require(request.profile().equals(Json.required(profileDocument, "profile_id"))
                && request.testLevel().equals(Json.required(policy, "test_level"))
                && request.testType().equals(Json.required(policy, "test_type"))
                && request.environmentStage().equals(Json.required(policy, "environment_stage"))
                && request.knowledgeMode().equals(Json.required(policy, "knowledge_mode")), "Request tuple differs from reviewed profile");
        if (document.has("valid_until"))
            require(Instant.parse(Json.required(document, "valid_until")).isAfter(Instant.now()), "Reviewed handoff has expired");
        if ("required".equals(request.knowledgeMode()))
            require(document.path("selection_receipt").isObject() && !document.path("selection_receipt").isEmpty(), "Required governed knowledge receipt is unavailable");
        else require(!document.has("selection_receipt"), "not_used cannot claim a governed knowledge receipt");
        // Detailed governance, reviewed-file coverage and snapshot validation belong to the existing framework.
    }

    private static Path absolute(String value) {
        Path path = Path.of(Objects.requireNonNull(value, "Artifact path is required"));
        require(path.isAbsolute() && path.normalize().equals(path), "Artifact paths must be absolute and normalized");
        return path;
    }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}

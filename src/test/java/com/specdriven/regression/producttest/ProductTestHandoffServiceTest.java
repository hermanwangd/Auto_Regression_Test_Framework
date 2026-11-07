package com.specdriven.regression.producttest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductTestHandoffServiceTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final ProductTestHandoffService service = new ProductTestHandoffService(
            Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC));

    @Test void snapshotsVerifiedBytesAndDeletesOnlyItsOwnTemporaryTree() throws Exception {
        Path root = fixture("reviewed_local");
        byte[] original = Files.readAllBytes(root.resolve("fixtures/input.json"));
        ProductTestHandoffService.Snapshot snapshot = assertDoesNotThrow(() -> snapshot(root));
        Path privateRoot = snapshot.suiteManifest().getParent();
        assertNotEquals(root.toAbsolutePath(), privateRoot);
        Files.writeString(root.resolve("fixtures/input.json"), "changed after verification");
        Files.writeString(root.resolve("contracts/sample_fake_provider_v0_3.yaml"), "changed contract");
        assertArrayEquals(original, Files.readAllBytes(privateRoot.resolve("fixtures/input.json")));
        assertTrue(Files.readString(snapshot.contractDirectory().resolve("sample_fake_provider_v0_3.yaml"))
                .contains("provider_contract: sample_fake_provider.v0.3"));
        assertEquals("not_used", snapshot.policy().knowledgeMode());
        assertEquals("synthetic-workspace", snapshot.provenance().get("workspace_ref"));
        assertEquals("synthetic-consumer", snapshot.provenance().get("project_ref"));
        assertEquals(digest(root.resolve("handoff/reviewed-tests.json")), snapshot.provenance().get("handoff_sha256"));
        assertEquals(Map.of("synthetic-product", "1".repeat(40)), snapshot.provenance().get("repository_revisions"));
        assertFalse(snapshot.provenance().toString().contains("Synthetic demonstration only"));
        assertFalse(Files.exists(privateRoot.resolve("handoff/reviewed-tests.json")));
        snapshot.close(); snapshot.close();
        assertFalse(Files.exists(privateRoot));
        assertTrue(Files.exists(root.resolve("suite_manifest.yaml")));
    }

    @Test void consumesOnlyExactGovernedDev204ShapeAndRetainsSeparateDigests() throws Exception {
        Path root = fixture("reviewed_required");
        try (var snapshot = assertDoesNotThrow(() -> snapshot(root))) {
            Map<String,Object> provenance = snapshot.provenance();
            assertEquals("required", provenance.get("knowledge_mode"));
            assertEquals("UNVERIFIED_BY_HTTP_CLIENT", provenance.get("provider_version"));
            assertEquals("synthetic-workspace", provenance.get("workspace_ref"));
            assertEquals("synthetic-consumer", provenance.get("project_ref"));
            assertEquals("synthetic-knowledge", provenance.get("knowledge_project_ref"));
            assertTrue(provenance.containsKey("selection_receipt_sha256"));
            assertTrue(provenance.containsKey("source_snapshot_sha256"));
            assertTrue(provenance.containsKey("candidate_search_sha256"));
            assertFalse(provenance.containsKey("query"));
        }
    }

    @Test void notUsedRequiresNoCogneeSourcePinAndEmitsNoCogneeAuthority() throws Exception {
        Path root = fixture("reviewed_local");
        mutateHandoff(root, h -> h.remove("cognee_source"));
        try (var snapshot = assertDoesNotThrow(() -> snapshot(root))) {
            assertEquals("not_used", snapshot.provenance().get("knowledge_mode"));
            assertFalse(snapshot.provenance().containsKey("cognee_source"));
            assertFalse(snapshot.provenance().containsKey("knowledge_sources"));
            assertFalse(snapshot.provenance().containsKey("selection_receipt_sha256"));
        }
    }

    @Test void invocationPinMustBeIndependentlyPresentExactAndWellFormed() throws Exception {
        Path root = fixture("reviewed_local");
        for (String pin : new String[]{null, "", " ", "wrong", "0".repeat(64)}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> service.snapshot(root.resolve("suite_manifest.yaml"), "local_v03",
                            new ProductTestHandoffService.InvocationRequirement(pin)));
            assertTrue(error.getMessage().contains("handoff_digest"), error.getMessage());
        }
        assertFalse(ProductTestHandoffService.InvocationRequirement.fromEnvironment(Map.of()).required());
        assertTrue(ProductTestHandoffService.InvocationRequirement.fromEnvironment(
                Map.of("REGRESS_REVIEWED_HANDOFF_SHA256", "")).required());
    }

    @Test void rejectsChangedReviewedFixtureOracleContractAndManifest() throws Exception {
        for (String path : List.of("fixtures/input.json", "expected_results/expected_output.json",
                "contracts/sample_fake_provider_v0_3.yaml", "test_cases/golden_success.yaml")) {
            Path root = fixture("reviewed_local");
            Files.writeString(root.resolve(path), "tampered");
            blocked(root, "file_digest_mismatch");
        }
        Path root = fixture("reviewed_local");
        Files.writeString(root.resolve("suite_manifest.yaml"), Files.readString(root.resolve("suite_manifest.yaml"))
                .replace("PRODUCT-REVIEWED-LOCAL-v0.3", "ALTERED"));
        blocked(root, "file_digest_mismatch");
    }

    @Test void rejectsWrongProfileKnowledgeModeAndCapabilityBeforeSnapshot() throws Exception {
        Path root = fixture("reviewed_local");
        var wrong = assertThrows(IllegalArgumentException.class, () -> service.snapshot(root.resolve("suite_manifest.yaml"),
                "other", new ProductTestHandoffService.InvocationRequirement(digest(root.resolve("handoff/reviewed-tests.json")))));
        assertTrue(wrong.getMessage().contains("profile"));
        mutateHandoff(root, h -> h.put("knowledge_mode", "required"));
        blocked(root, "knowledge_mode");
        Map<String,Object> policy = policy(); policy.put("environment_stage", "sit");
        assertThrows(IllegalArgumentException.class, () -> ProductTestPolicy.parse(policy));
    }

    @Test void rejectsNullUnknownAndMistypedPolicyAndConflictingSharedPolicy() {
        assertThrows(IllegalArgumentException.class, () -> ProductTestPolicy.parse(null));
        for (Map<String,Object> invalid : List.of(with(policy(), "unknown", true),
                with(policy(), "provider_modes", Map.of("sample_runtime", 1)),
                with(policy(), "knowledge_mode", "optional"), with(policy(), "test_level", "integration"))) {
            assertThrows(IllegalArgumentException.class, () -> ProductTestPolicy.parse(invalid));
        }
        Map<String,Object> nullMap = policy(); nullMap.put("dependency_modes", null);
        assertThrows(IllegalArgumentException.class, () -> ProductTestPolicy.parse(nullMap));
        var shared = Map.of("environment_id", "local", "namespace", "demo", "owner", "team",
                "data_access", "read_only", "cleanup", "owned_only");
        assertThrows(IllegalArgumentException.class, () -> ProductTestPolicy.parse(with(policy(), "shared_state", shared)));
        ProductTestPolicy parsed = assertDoesNotThrow(() -> ProductTestPolicy.parse(with(policy(), "shared_state",
                Map.of("environment_id", "local", "namespace", "demo", "owner", "team"))));
        assertEquals("read_only", parsed.sharedState().dataAccess());
        assertEquals("none", parsed.sharedState().cleanup());
    }

    @Test void rejectsDuplicateJsonFieldsTrailingDocumentsAndOversizedHandoff() throws Exception {
        Path root = fixture("reviewed_local"); Path handoff = root.resolve("handoff/reviewed-tests.json");
        String valid = Files.readString(handoff);
        Files.writeString(handoff, valid.replaceFirst("\\{", "{\"profile\":\"duplicate\","));
        blocked(root, "invalid_json");
        Files.writeString(handoff, valid + "{}"); blocked(root, "invalid_json");
        Files.writeString(handoff, " ".repeat(2 * 1024 * 1024 + 1)); blocked(root, "file_size_limit");
    }

    @Test void rejectsExplicitNullsUnknownFieldsDuplicatesAndEmptyTests() throws Exception {
        for (Consumer<Map<String,Object>> mutate : List.<Consumer<Map<String,Object>>>of(
                h -> h.put("reviewed_by", null), h -> h.put("unknown", true),
                h -> list(h.get("reviewed_files")).add(list(h.get("reviewed_files")).get(0)),
                h -> list(h.get("reviewed_contracts")).add(list(h.get("reviewed_contracts")).get(0)))) {
            Path root = fixture("reviewed_local"); mutateHandoff(root, mutate);
            assertThrows(IllegalArgumentException.class, () -> snapshot(root));
        }
        Path root = fixture("reviewed_local");
        changeReviewed(root, "suite_manifest.yaml", text -> text.replace("tests:\n  - ref: test_cases/golden_success.yaml", "tests: []"));
        blocked(root, "tests");
    }

    @Test void requiresCompleteFileAndContractAndOracleCoverage() throws Exception {
        for (String path : List.of("fixtures/input.json", "expected_results/expected_output.json", "test_cases/golden_success.yaml")) {
            Path root = fixture("reviewed_local");
            mutateHandoff(root, h -> list(h.get("reviewed_files")).removeIf(v -> map(v).get("path").equals(path)));
            blocked(root, "reviewed_file_coverage");
        }
        Path root = fixture("reviewed_local");
        mutateHandoff(root, h -> h.put("reviewed_contracts", List.of())); blocked(root, "reviewed_contract");
        Path root2 = fixture("reviewed_local");
        mutateHandoff(root2, h -> map(list(h.get("requirement_mapping")).get(0)).put("oracle_refs",
                List.of("expected_results/expected_output.json")));
        blocked(root2, "oracle_coverage");
        Path root3 = fixture("reviewed_local");
        mutateHandoff(root3, h -> map(list(h.get("requirement_mapping")).get(0)).put("test_case_ids", List.of("dangling")));
        blocked(root3, "test_case");
    }

    @Test void rejectsUnsafePathsLinksAndHardlinks() throws Exception {
        for (String path : List.of("../escape", "/etc/passwd", "fixtures/../input.json", "fixtures/%2e%2e/input", "fixtures\\input.json")) {
            Path root = fixture("reviewed_local");
            mutateHandoff(root, h -> map(list(h.get("reviewed_files")).get(3)).put("path", path));
            blocked(root, "unsafe_path");
        }
        Path root = fixture("reviewed_local"); Path input = root.resolve("fixtures/input.json");
        Path outside = temp.resolve("outside.json"); Files.copy(input, outside); Files.delete(input);
        Files.createSymbolicLink(input, outside); blocked(root, "symlink");
        Files.delete(input); Files.createLink(input, outside); blocked(root, "hardlink");
        Path root2 = fixture("reviewed_local"); Path moved = temp.resolve("moved-fixtures");
        Files.move(root2.resolve("fixtures"), moved); Files.createSymbolicLink(root2.resolve("fixtures"), moved);
        blocked(root2, "symlink");
    }

    @Test void rejectsDynamicReferencesAndAlternateCatalogResolution() throws Exception {
        for (String ref : List.of("env://INPUT", "generated://fixture/input", "artifact://fixtures/${INPUT}",
                "https://example.invalid/input", "step://produce_sample_output/actual_json")) {
            Path root = fixture("reviewed_local");
            changeReviewed(root, "test_cases/golden_success.yaml", t -> t.replace("artifact://fixtures/input.json", ref));
            blocked(root, "unsupported_reference");
        }
        Path root = fixture("reviewed_local");
        changeReviewed(root, "suite_manifest.yaml", t -> t + "\nprovider_contract_resolution:\n  mode: suite_local\n");
        blocked(root, "provider_contract_resolution");
    }

    @Test void enforcesFiniteProviderBindingAndModeCoverage() throws Exception {
        for (String replacement : List.of("sample_runtime: real", "wrong_target: simulated")) {
            Path root = fixture("reviewed_local");
            changeReviewed(root, "env_profiles/local_v03.yaml", t -> t.replace("sample_runtime: simulated", replacement));
            blocked(root, "provider_mode");
        }
        Path root = fixture("reviewed_local");
        changeReviewed(root, "env_profiles/local_v03.yaml", t -> t.replace("fixed://2026-07-10T00:00:00Z", "env://CLOCK"));
        blocked(root, "unsupported_reference");
    }

    @Test void rejectsReceiptByteTamperMissingSelectedAndLegacyAliases() throws Exception {
        Path root = fixture("reviewed_required");
        Files.writeString(root.resolve("handoff/selection-receipt.json"), "tampered"); blocked(root, "receipt_digest_mismatch");
        Path root2 = fixture("reviewed_required");
        mutateReceipt(root2, r -> map(r.get("context")).put("selected", List.of())); blocked(root2, "selected");
        Path root3 = fixture("reviewed_required");
        mutateReceipt(root3, r -> { var c = map(r.get("context")); c.put("selectedRecords", c.remove("selected")); });
        blocked(root3, "selected");
    }

    @Test void rejectsExcludedAmbiguousOrCandidateOnlyKnowledge() throws Exception {
        for (Consumer<Map<String,Object>> mutate : List.<Consumer<Map<String,Object>>>of(
                r -> map(r.get("context")).put("excluded", List.of(Map.of("recordKey", "synthetic-source-001", "knowledgeRef", "synthetic://knowledge/source-001"))),
                r -> list(map(r.get("context")).get("selected")).add(selected(r)),
                r -> list(map(map(r.get("cogneeSelection")).get("candidateSearch")).get("candidates")).add(candidate(r)),
                r -> selected(r).put("recordKey", "different"))) {
            Path root = fixture("reviewed_required"); mutateReceipt(root, mutate);
            assertThrows(IllegalArgumentException.class, () -> snapshot(root));
        }
    }

    @Test void bindsReviewedWorkspaceAndConsumerToRequiredReceipt() throws Exception {
        for (String field : List.of("workspace_ref", "project_ref")) {
            Path root = fixture("reviewed_required");
            mutateHandoff(root, h -> h.put(field, "wrong-scope"));
            blocked(root, "reviewed_" + field);
        }
    }

    @Test void rejectsReceiptRevisionScopeDatasetAndRepositoryMismatch() throws Exception {
        for (Consumer<Map<String,Object>> mutate : List.<Consumer<Map<String,Object>>>of(
                r -> candidate(r).put("sourceRevision", 2), r -> candidate(r).put("providerRevision", "other"),
                r -> candidate(r).put("workspaceRef", "other"), r -> candidate(r).put("projectRef", "other"),
                r -> candidate(r).put("datasetId", "22222222-2222-4222-8222-222222222222"),
                r -> selected(r).put("repositoryRevisions", Map.of("other", "2".repeat(40))),
                r -> map(r.get("cogneeSelection")).put("searchStatus", "UNAVAILABLE"),
                r -> selected(r).put("validUntil", "2026-11-07T00:00:00Z"))) {
            Path root = fixture("reviewed_required"); mutateReceipt(root, mutate);
            assertThrows(IllegalArgumentException.class, () -> snapshot(root));
        }
    }

    @Test void rejectsExpiredAndFutureReviewAndKnowledgeModeDowngrade() throws Exception {
        Path root = fixture("reviewed_local");
        mutateHandoff(root, h -> h.put("valid_until", "2026-11-07T00:00:00Z")); blocked(root, "expired_review");
        Path root2 = fixture("reviewed_local");
        mutateHandoff(root2, h -> h.put("reviewed_at", "2026-11-08T00:00:00Z")); blocked(root2, "reviewed_at");
        Path root3 = fixture("reviewed_local");
        mutateHandoff(root3, h -> h.put("selection_receipt", Map.of("path", "receipt", "sha256", "1".repeat(64))));
        blocked(root3, "not_used");
    }

    @Test void preparationHashesOnlySuppliedReviewedMetadataWithoutApprovalOrExecution() throws Exception {
        Path root = fixture("reviewed_local"); Path out = root.resolve("handoff/reviewed-tests.json");
        Files.delete(out);
        Map<String,Object> result = assertDoesNotThrow(() -> service.prepare(root.resolve("suite_manifest.yaml"), "local_v03",
                root.resolve("review-input.json"), null, out));
        assertEquals(digest(out), result.get("sha256"));
        assertEquals("prepared_not_approved", result.get("status"));
        assertTrue(Files.isRegularFile(Path.of(String.valueOf(result.get("sha256_path")))));
        assertFalse(Files.exists(root.resolve("runs")));
        try (var snapshot = snapshot(root)) { assertEquals("component", snapshot.policy().testLevel()); }
    }

    @Test void rejectsCountBeforeReadingTheFirstOverBudgetContract() throws Exception {
        Path root = fixture("reviewed_local");
        Map<String,Object> handoff = read(root.resolve("handoff/reviewed-tests.json"));
        List<Object> entries = list(handoff.get("reviewed_files"));
        for (int i = entries.size(); i < 128; i++) {
            String name = "fixtures/extra-" + i + ".txt";
            Files.writeString(root.resolve(name), "static");
            entries.add(Map.of("path", name, "role", "fixture", "sha256", digest(root.resolve(name))));
        }
        map(list(handoff.get("reviewed_contracts")).get(0)).put("path", "contracts/absent.yaml");
        write(root.resolve("handoff/reviewed-tests.json"), handoff);
        blocked(root, "file_count_limit");
    }

    @Test void rejectsAggregateBytesBeforeReadingAnyLaterInput() throws Exception {
        Path root = fixture("reviewed_local");
        Map<String,Object> handoff = read(root.resolve("handoff/reviewed-tests.json"));
        List<Object> entries = list(handoff.get("reviewed_files"));
        byte[] large = new byte[2 * 1024 * 1024]; java.util.Arrays.fill(large, (byte) 'x');
        for (int i = 0; i < 8; i++) {
            String name = "fixtures/large-" + i + ".txt";
            Files.write(root.resolve(name), large);
            entries.add(Map.of("path", name, "role", "fixture", "sha256", digest(root.resolve(name))));
        }
        entries.add(Map.of("path", "fixtures/absent.txt", "role", "fixture", "sha256", "0".repeat(64)));
        write(root.resolve("handoff/reviewed-tests.json"), handoff);
        blocked(root, "total_size_limit");
    }

    @Test void rejectsExcessiveJsonNestingBeforeBindingUnknownFields() throws Exception {
        Path root = fixture("reviewed_local");
        Files.writeString(root.resolve("handoff/reviewed-tests.json"), "{\"value\":" + "[".repeat(80) + "0" + "]".repeat(80) + "}");
        blocked(root, "invalid_json");
    }

    @Test void discoveryAllowsOrdinaryAliasesButReviewedSnapshotStillRejectsLinkedParents() throws Exception {
        Path root = fixture("reviewed_local");
        Path alias = temp.toRealPath().resolve("suite-alias"); Files.createSymbolicLink(alias, root);
        assertTrue(assertDoesNotThrow(() -> ProductTestHandoffService.configured(alias.resolve("suite_manifest.yaml"), "local_v03")));
        var blocked = assertThrows(IllegalArgumentException.class, () -> snapshot(alias));
        assertTrue(blocked.getMessage().contains("symlink"));
        Path profile = root.resolve("env_profiles/local_v03.yaml");
        String content = Files.readString(profile); Files.writeString(profile, content.substring(0, content.indexOf("product_test:")));
        assertFalse(assertDoesNotThrow(() -> ProductTestHandoffService.configured(alias.resolve("suite_manifest.yaml"), "local_v03")));
    }

    @Test void configuredRecognizesPresentNullPolicyWithoutSilentDowngrade() throws Exception {
        Path root = fixture("reviewed_local");
        assertTrue(ProductTestHandoffService.configured(root.resolve("suite_manifest.yaml"), "local_v03"));
        Files.writeString(root.resolve("env_profiles/local_v03.yaml"), "profile_id: local_v03\nproduct_test: null\n");
        assertTrue(ProductTestHandoffService.configured(root.resolve("suite_manifest.yaml"), "local_v03"));
        blocked(root, "product_test");
    }

    private ProductTestHandoffService.Snapshot snapshot(Path root) throws Exception {
        return service.snapshot(root.resolve("suite_manifest.yaml"), "local_v03",
                new ProductTestHandoffService.InvocationRequirement(digest(root.resolve("handoff/reviewed-tests.json"))));
    }
    private void blocked(Path root, String reason) {
        var error = assertThrows(IllegalArgumentException.class, () -> snapshot(root));
        assertTrue(error.getMessage().contains(reason), error.getMessage());
    }
    private Path fixture(String name) throws IOException {
        Path source = Path.of("samples/41-product-testing", name); Path target = Files.createTempDirectory(temp, "suite-").toRealPath();
        try (var paths = Files.walk(source)) { for (Path p : paths.toList()) {
            Path dest = target.resolve(source.relativize(p).toString());
            if (Files.isDirectory(p)) Files.createDirectories(dest); else Files.copy(p, dest);
        }} return target;
    }
    private void mutateHandoff(Path root, Consumer<Map<String,Object>> mutate) throws Exception {
        Path file = root.resolve("handoff/reviewed-tests.json"); Map<String,Object> h = read(file); mutate.accept(h); write(file, h);
    }
    private void mutateReceipt(Path root, Consumer<Map<String,Object>> mutate) throws Exception {
        Path file = root.resolve("handoff/selection-receipt.json"); Map<String,Object> r = read(file); mutate.accept(r); write(file, r);
        mutateHandoff(root, h -> map(h.get("selection_receipt")).put("sha256", uncheckedDigest(file)));
    }
    private void changeReviewed(Path root, String path, java.util.function.UnaryOperator<String> change) throws Exception {
        Path file = root.resolve(path); Files.writeString(file, change.apply(Files.readString(file)));
        mutateHandoff(root, h -> list(h.get("reviewed_files")).stream().map(ProductTestHandoffServiceTest::map)
                .filter(f -> path.equals(f.get("path"))).forEach(f -> f.put("sha256", uncheckedDigest(file))));
    }
    private Map<String,Object> read(Path file) throws IOException { return json.readValue(Files.readAllBytes(file), Map.class); }
    private void write(Path file, Object value) throws IOException { Files.write(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)); }
    private static String digest(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private static String uncheckedDigest(Path file) { try { return digest(file); } catch (Exception e) { throw new RuntimeException(e); } }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object o) { return (Map<String,Object>) o; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object o) { return (List<Object>) o; }
    private static Map<String,Object> selected(Map<String,Object> r) { return map(list(map(r.get("context")).get("selected")).get(0)); }
    private static Map<String,Object> candidate(Map<String,Object> r) { return map(list(map(map(r.get("cogneeSelection")).get("candidateSearch")).get("candidates")).get(0)); }
    private static Map<String,Object> with(Map<String,Object> m, String key, Object value) { m.put(key, value); return m; }
    private static Map<String,Object> policy() {
        return new LinkedHashMap<>(Map.of("contract_version", "product-test.v1", "test_level", "component", "test_type", "regression",
                "environment_stage", "local", "knowledge_mode", "not_used", "reviewed_handoff_ref", "handoff/reviewed-tests.json",
                "provider_modes", Map.of("sample_runtime", "simulated")));
    }
}

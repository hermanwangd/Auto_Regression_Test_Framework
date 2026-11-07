package com.featuredeliveryintelligence.fdi.producttest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

class ProductTestingModuleTest {
    @TempDir Path root;
    @BeforeEach void canonicalRoot() throws Exception { root = root.toRealPath(); }

    @Test void unsupportedEnvironmentBlocksBeforeDispatch() throws Exception {
        var calls = new AtomicInteger();
        var module = new DefaultProductTestingModule(request -> { calls.incrementAndGet(); return null; });
        var receipt = module.execute(request("sit", "not_used", "{}"));
        assertThat(calls.get()).isZero();
        assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
        assertThat(receipt.frameworkVerdict()).isEmpty();
    }

    @Test void changedReviewedBytesBlockBeforeDispatch() throws Exception {
        var request = request("local", "not_used", "{}");
        Files.writeString(Path.of(request.reviewedHandoff()), "{\"changed\":true}");
        var receipt = executeBeforeLaunch(request);
        assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
        assertThat(receipt.diagnostic()).contains("digest");
    }

    @Test void requiredMissingKnowledgeBlocksBeforeDispatch() throws Exception {
        var receipt = executeBeforeLaunch(request("local", "required", validHandoff().replace("not_used", "required")));
        assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
    }

    @Test void mismatchedRepositoryRevisionBlocksBeforeDispatch() throws Exception {
        var receipt = executeBeforeLaunch(request("local", "not_used", validHandoff().replace("a".repeat(40), "b".repeat(40))));
        assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
    }

    @Test void mismatchedRequirementMappingBlocksBeforeDispatch() throws Exception {
        var receipt = executeBeforeLaunch(request("local", "not_used", validHandoff().replace("TC-1", "TC-OTHER")));
        assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
    }

    @Test void reviewedLocalTestWithoutKnowledgeInvokesPortOnceAndPreservesVerdict() throws Exception {
        var calls = new AtomicInteger(); var request = request("local", "not_used", validHandoff());
        var failed = new ProductTestReceipt("product-test-receipt.v1", "product-test", "1", "invocation", request,
                ProductTestReceipt.Outcome.COMPLETED, "failed", "validated", 1, "run", "output", Map.of(), false,
                "framework_verification_only", "");
        var module = new DefaultProductTestingModule(value -> { calls.incrementAndGet(); assertThat(value).isEqualTo(request); return failed; });
        assertThat(module.execute(request)).isSameAs(failed); assertThat(calls.get()).isEqualTo(1);
    }

    @Test void nativeLocalTupleIsStructurallySupported() throws Exception {
        var value = (com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(request("local","not_used",validHandoff()));
        value.put("test_level","integration");
        var calls = new AtomicInteger();
        new DefaultProductTestingModule(request -> { calls.incrementAndGet(); return null; })
                .execute(Json.MAPPER.treeToValue(value,ProductTestRequest.class));
        assertThat(calls).hasValue(1);
    }

    @Test void nativeRequestCannotRelabelAnApprovedFakeProfileBeforeLaunch() throws Exception {
        var value = (com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(request("local","not_used",validHandoff()));
        value.put("test_level","integration");
        var receipt = executeBeforeLaunch(Json.MAPPER.treeToValue(value,ProductTestRequest.class));
        assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
        assertThat(receipt.diagnostic()).contains("tuple");
    }

    @Test void profileMutationCannotReplaceTheIndependentlyApprovedTuple() throws Exception {
        var request = request("local","not_used",validHandoff());
        Files.writeString(root.resolve("profile.yaml"),Files.readString(root.resolve("profile.yaml")).replace("component","integration"));
        var receipt = executeBeforeLaunch(request);
        assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
        assertThat(receipt.diagnostic()).contains("profile digest");
    }

    static String validHandoff() {
        return """
                {"suite_id":"SUITE","profile":"local","knowledge_mode":"not_used",
                 "workspace_ref":"workspace:local","project_ref":"project:local",
                 "repository_revisions":{"example":"%s"},
                 "requirement_mapping":[{"requirement_ref":"REQ-1","test_case_ids":["TC-1"]}]}
                """.formatted("a".repeat(40));
    }

    @Test void requiredKnowledgeRequestCannotRelabelWorkspace() throws Exception {
        var selected = root.resolve("selection.json");
        Files.writeString(selected, """
                {"context":{"request":{"workspaceRef":"workspace:local","consumerProjectRef":"project:local","knowledgeProjectRef":"project:knowledge"},
                            "readWorkspaceRef":"workspace:local","readProjectRef":"project:knowledge"}}
                """);
        var handoff = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.readTree(validHandoff());
        handoff.put("knowledge_mode", "required");
        handoff.putObject("selection_receipt").put("path", "selection.json").put("sha256", hash(selected));
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(request("local", "required", Json.MAPPER.writeValueAsString(handoff)));
        changed.put("workspace_ref", "workspace:other");
        var receipt = executeBeforeLaunch(Json.MAPPER.treeToValue(changed, ProductTestRequest.class));
        assertThat(receipt.diagnostic()).contains("scope");
    }

    @Test void noKnowledgeRequestCannotRelabelProject() throws Exception {
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(request("local", "not_used", validHandoff()));
        changed.put("project_ref", "project:other");
        var receipt = executeBeforeLaunch(Json.MAPPER.treeToValue(changed, ProductTestRequest.class));
        assertThat(receipt.diagnostic()).contains("scope");
    }

    @Test void namedPipeHandoffIsRejectedWithoutReadingOrBlocking() throws Exception {
        var request = request("local", "not_used", validHandoff());
        Path handoff = Path.of(request.reviewedHandoff()); Files.delete(handoff);
        var fifo = new ProcessBuilder("/usr/bin/mkfifo", handoff.toString()).start();
        assertThat(fifo.waitFor()).isZero();
        assertTimeoutPreemptively(java.time.Duration.ofMillis(500), () -> {
            var receipt = executeBeforeLaunch(request);
            assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
        });
    }

    private ProductTestReceipt executeBeforeLaunch(ProductTestRequest request) throws Exception {
        Path jar = root.resolve("unused-framework.jar"); Files.writeString(jar, "Never launched for rejected input");
        Path outputs = Files.createDirectories(root.resolve("outputs"));
        var binding = new FrameworkBinding(Path.of(System.getProperty("java.home"), "bin", "java"), jar, hash(jar),
                request.expectedHandoffSha256(), root, outputs, java.time.Duration.ofSeconds(2), 4096);
        var receipt = new DefaultProductTestingModule(new FrameworkCliTransport(binding)).execute(request);
        try (var entries = Files.list(outputs)) { assertThat(entries.toList()).isEmpty(); }
        return receipt;
    }

    static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    static String withReviewedProfile(Path root, String handoff) throws Exception {
        var document = (com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.readTree(handoff);
        String mode = document.path("knowledge_mode").asText("not_used");
        Path profile = root.resolve("profile.yaml");
        Files.writeString(profile,"profile_id: local\nproduct_test:\n  test_level: component\n  test_type: regression\n  environment_stage: local\n  knowledge_mode: " + mode + "\n");
        document.putArray("reviewed_files").addObject().put("path","profile.yaml").put("role","profile").put("sha256",hash(profile));
        return Json.MAPPER.writeValueAsString(document);
    }

    ProductTestRequest request(String stage, String mode, String handoff) throws Exception {
        Files.writeString(root.resolve("suite_manifest.yaml"), "suite_id: SUITE\n");
        Files.writeString(root.resolve("handoff.json"), withReviewedProfile(root,handoff));
        return new ProductTestRequest("product-test-request.v1", "product-test", "1",
                "mission:1", "request:1", "rev-17", "workspace:local", "project:local",
                Map.of("example", "a".repeat(40)), Map.of("REQ-1", List.of("TC-1")),
                "component", "regression", stage, mode, root.toString(), root.resolve("suite_manifest.yaml").toString(),
                "SUITE", "local", root.resolve("handoff.json").toString(), hash(root.resolve("handoff.json")));
    }
}

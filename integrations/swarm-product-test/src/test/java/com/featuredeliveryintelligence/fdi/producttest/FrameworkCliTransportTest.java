package com.featuredeliveryintelligence.fdi.producttest;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

class FrameworkCliTransportTest {
    @TempDir Path root;
    @BeforeEach void canonicalRoot() throws Exception { root = root.toRealPath(); }

    @Test void zeroExitWithoutCanonicalEvidenceNeverBecomesPass() throws Exception {
        var receipt = transport("System.out.println(\"run_status: passed\");", Duration.ofSeconds(4)).execute(request());
        assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.INVALID_EVIDENCE);
        assertThat(receipt.frameworkExitCode()).isZero();
        assertThat(receipt.frameworkVerdict()).isEmpty();
        assertThat(receipt.evidenceValidation()).isEqualTo("not_validated");
    }

    @Test void zeroExitWithMalformedCanonicalJsonNeverBecomesPass() throws Exception {
        var receipt = transport("""
                var output = java.nio.file.Path.of(args[6]);
                var result = output.resolve("result.json");
                java.nio.file.Files.writeString(result, "{malformed");
                System.out.println("run_status: passed");
                System.out.println("result_json: " + result);
                """, Duration.ofSeconds(4)).execute(request());
        assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.INVALID_EVIDENCE);
        assertThat(receipt.frameworkExitCode()).isZero();
        assertThat(receipt.frameworkVerdict()).isEmpty();
    }

    @Test void missingJavaExecutableIsALaunchFailure() throws Exception {
        transport("System.out.println(\"unused\");", Duration.ofSeconds(4));
        Path jar = root.resolve("fixture.jar");
        var binding = new FrameworkBinding(root.resolve("missing-java"), jar, ProductTestingModuleTest.hash(jar), request().expectedHandoffSha256(),
                root.resolve("artifacts"), root.resolve("outputs"), Duration.ofSeconds(4), 4096);
        var receipt = new FrameworkCliTransport(binding).execute(request());
        assertThat(receipt.outcome()).as(receipt.diagnostic()).isEqualTo(ProductTestReceipt.Outcome.FAILED_TO_LAUNCH);
    }

    @Test void childProcessDeadlineNeverBecomesPass() throws Exception {
        long start = System.nanoTime();
        var receipt = transport("""
                java.nio.file.Files.writeString(java.nio.file.Path.of(args[6]).resolve("child.pid"), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(15000);
                """, Duration.ofMillis(600)).execute(request());
        assertThat(receipt.outcome()).as(receipt.diagnostic()).isEqualTo(ProductTestReceipt.Outcome.TIMED_OUT);
        assertThat(receipt.diagnostic()).contains("termination confirmed");
        long pid = Long.parseLong(Files.readString(Path.of(receipt.outputDirectory()).resolve("child.pid")));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(receipt.frameworkVerdict()).isEmpty();
        String terminalReceipt = Json.MAPPER.writeValueAsString(receipt);
        Path late = Files.createDirectories(Path.of(receipt.outputDirectory()).resolve("target/regression/SUITE/BATCH-LATE/RUN-V03-" + java.util.UUID.randomUUID()));
        Files.writeString(late.resolve("result.json"), "{\"status\":\"passed\"}");
        assertThat(Json.MAPPER.writeValueAsString(receipt)).isEqualTo(terminalReceipt);
        assertThat(receipt.evidenceValidation()).isEqualTo("not_validated");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test void childOutputFloodIsBounded() throws Exception {
        var receipt = transport("while(true) System.out.println(\"x\".repeat(1000));", Duration.ofSeconds(4)).execute(request());
        assertThat(receipt.outcome()).as(receipt.diagnostic()).isEqualTo(ProductTestReceipt.Outcome.OUTPUT_LIMIT);
        assertThat(receipt.frameworkVerdict()).isEmpty();
    }
    @Test void frameworkTemporaryDirectoryIsBoundToPrivateOutput() throws Exception {
        var receipt = transport("System.out.println(\"java_tmpdir: \" + System.getProperty(\"java.io.tmpdir\"));", Duration.ofSeconds(4)).execute(request());
        var stdout = Files.readString(Path.of(receipt.outputDirectory()).resolve("run.stdout.log"));
        assertThat(stdout).contains("java_tmpdir: " + receipt.outputDirectory());
    }

    @Test void changedConfiguredFrameworkJarBlocksBeforeLaunch() throws Exception {
        var transport = transport("throw new AssertionError(\"must not launch\");", Duration.ofSeconds(4));
        Files.writeString(root.resolve("fixture.jar"), "changed");
        var receipt = transport.execute(request());
        assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
        assertThat(receipt.diagnostic()).contains("framework digest");
    }

    @Test void suiteSymlinkOutsideApprovedRootBlocksBeforeLaunch() throws Exception {
        var transport = transport("throw new AssertionError(\"must not launch\");", Duration.ofSeconds(4));
        var request = request();
        Files.delete(Path.of(request.suiteManifest()));
        Files.createSymbolicLink(Path.of(request.suiteManifest()), root.resolve("FixtureMain.java"));
        var receipt = transport.execute(request);
        assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
        assertThat(receipt.frameworkVerdict()).isEmpty();
    }

    @Test void editedHandoffAndRecomputedRequestPinCannotReplaceIndependentBindingPin() throws Exception {
        var transport = transport("System.out.println(\"run_status: passed\");", Duration.ofSeconds(4));
        var request = request();
        Files.writeString(Path.of(request.reviewedHandoff()), "{\"suite_id\":\"SUITE\",\"profile\":\"local\",\"changed\":true}");
        var changed = Json.MAPPER.valueToTree(request);
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("expected_handoff_sha256", ProductTestingModuleTest.hash(Path.of(request.reviewedHandoff())));
        var receipt = transport.execute(Json.MAPPER.treeToValue(changed, ProductTestRequest.class));
        assertThat(receipt.outcome()).as(receipt.diagnostic()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
        assertThat(receipt.diagnostic()).contains("independent");
        assertThat(Files.list(root.resolve("outputs")).toList()).isEmpty();
    }

    @Test void directPortCannotBypassSupportedCapabilityTuple() throws Exception {
        FrameworkExecutionPort port = transport("System.out.println(\"run_status: passed\");", Duration.ofSeconds(4));
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(request());
        changed.put("environment_stage", "sit");
        var receipt = port.execute(Json.MAPPER.treeToValue(changed, ProductTestRequest.class));
        assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
        assertThat(Files.list(root.resolve("outputs")).toList()).isEmpty();
    }

    @Test void configuredArtifactBoundaryIsCheckedBeforeReadingUnapprovedHandoff() throws Exception {
        var transport = transport("System.out.println(\"unused\");", Duration.ofSeconds(4));
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(request());
        Path outside = Files.createDirectories(root.resolve("outside-approved-root"));
        Files.writeString(outside.resolve("suite_manifest.yaml"), "suite_id: SUITE\n");
        Files.writeString(outside.resolve("handoff.json"), "not approved bytes");
        changed.put("artifact_root", outside.toString()).put("suite_manifest", outside.resolve("suite_manifest.yaml").toString())
                .put("reviewed_handoff", outside.resolve("handoff.json").toString());
        var receipt = transport.execute(Json.MAPPER.treeToValue(changed, ProductTestRequest.class));
        assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
        assertThat(receipt.diagnostic()).contains("approved");
        var viaModule = new DefaultProductTestingModule(transport).execute(Json.MAPPER.treeToValue(changed, ProductTestRequest.class));
        assertThat(viaModule.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);
        assertThat(viaModule.diagnostic()).contains("approved");
    }

    private FrameworkCliTransport transport(String body, Duration timeout) throws Exception {
        Path source = root.resolve("FixtureMain.java");
        Files.writeString(source, "public class FixtureMain { public static void main(String[] args) throws Exception { " + body + " } }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString())).isZero();
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "FixtureMain");
        Path jar = root.resolve("fixture.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("FixtureMain.class"));
            out.write(Files.readAllBytes(root.resolve("FixtureMain.class"))); out.closeEntry();
        }
        Files.createDirectories(root.resolve("outputs")); Files.createDirectories(root.resolve("artifacts"));
        String handoffPin = request().expectedHandoffSha256();
        return new FrameworkCliTransport(new FrameworkBinding(
                Path.of(System.getProperty("java.home"), "bin", "java"), jar, ProductTestingModuleTest.hash(jar),
                handoffPin,
                root.resolve("artifacts"), root.resolve("outputs"), timeout, 4096));
    }

    private ProductTestRequest request() throws Exception {
        Path artifacts = root.resolve("artifacts"); Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("suite_manifest.yaml"), "suite_id: SUITE\n");
        Files.writeString(artifacts.resolve("handoff.json"), ProductTestingModuleTest.withReviewedProfile(artifacts,ProductTestingModuleTest.validHandoff()));
        return new ProductTestRequest("product-test-request.v1", "product-test", "1", "mission:1", "request:1", "rev-17",
                "workspace:local", "project:local", Map.of("example", "a".repeat(40)), Map.of("REQ-1", List.of("TC-1")),
                "component", "regression", "local", "not_used", artifacts.toString(), artifacts.resolve("suite_manifest.yaml").toString(),
                "SUITE", "local", artifacts.resolve("handoff.json").toString(), ProductTestingModuleTest.hash(artifacts.resolve("handoff.json")));
    }
}

package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.producttest.ProductTestHandoffService.InvocationRequirement;
import com.specdriven.regression.summary.SuiteExecutionContext;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real child-process execution through reviewed handoff, existing assertions and finalizer. */
class ReviewedCliIntegrationTest {
    @TempDir Path temporary;
    final ReviewedLoopbackRestTest helper = new ReviewedLoopbackRestTest();

    @Test void directArgvHasCanonicalCliEvidenceAndLiteralOutput() throws Exception {
        String value = "literal ; $(echo should-not-run) | > file";
        Path suite = fixture("/usr/bin/printf", List.of("%s", value), 0, value);
        var result = helper.run(suite);
        assertThat(result.status()).as(result.findings().toString()).isEqualTo("passed");
        helper.assertValid(result);
        var json = helper.json.readTree(result.resultJson().toFile());
        assertThat(json.path("provider_results").get(0).path("provider_type").asText()).isEqualTo("cli_process");
        assertThat(json.path("step_results").get(0).path("outputs").path("stdout").asText()).isEqualTo(value);
        assertThat(Files.readString(result.resultJson().getParent().resolve(json.path("evidence_index_ref").asText()))).contains("cli_process");
    }

    @Test void expectedNonzeroExitPassesWithObservedExitCode() throws Exception {
        var result = helper.run(fixture("/usr/bin/false", List.of(), 1, ""));
        assertThat(result.status()).as(result.findings().toString()).isEqualTo("passed");
        helper.assertValid(result);
    }

    @Test void unexpectedNonzeroIsAnAssertionFailureAndRecoveryPasses() throws Exception {
        var result = helper.run(fixture("/usr/bin/false", List.of(), 0, ""));
        assertThat(result.status()).isEqualTo("failed");
        assertThat(helper.json.readTree(result.resultJson().toFile()).path("failure").path("code").asText()).isEqualTo("ASSERTION_FAILED");
        helper.assertValid(result);
        var recovery = helper.run(fixture("/usr/bin/true", List.of(), 0, ""));
        assertThat(recovery.status()).isEqualTo("passed");
        helper.assertValid(recovery);
    }

    @Test void changedArgvAfterReviewBlocksBeforeLaunch() throws Exception {
        Path suite = fixture("/usr/bin/printf", List.of("%s", "reviewed"), 0, "reviewed");
        assertDoesNotThrow(() -> helper.prepare(suite));
        var pin = new InvocationRequirement(Files.readString(suite.resolve("handoff/reviewed.json.sha256")).trim());
        var test = helper.read(suite.resolve("test_cases/items.yaml"));
        helper.map(helper.map(((List<?>) test.get("execute")).get(0)).get("with")).put("process.argv", List.of("%s", "changed"));
        helper.write(suite.resolve("test_cases/items.yaml"), test);
        var result = new V03RuntimeExecutionService().runReviewed(suite.resolve("suite_manifest.yaml"), "local_native",
                SuiteExecutionContext.standalone("local_native", temporary.toRealPath().resolve("runs"), "V03"), pin);
        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.findings().toString()).contains("file_digest_mismatch");
    }

    @ParameterizedTest @ValueSource(strings={"shared", "shell_string", "dynamic", "cwd_escape", "extra_binding", "wrong_contract"})
    void invalidReviewedCliPolicyRejectsBeforeExecution(String variant) throws Exception {
        Path suite = fixture("/usr/bin/true", List.of(), 0, "");
        var profile = helper.read(suite.resolve("env_profiles/local_native.yaml"));
        var test = helper.read(suite.resolve("test_cases/items.yaml"));
        var inputs = helper.map(helper.map(((List<?>) test.get("execute")).get(0)).get("with"));
        switch (variant) {
            case "shared" -> helper.map(profile.get("product_test")).put("shared_state", Map.of("environment_id", "SIT", "namespace", "demo", "owner", "test"));
            case "shell_string" -> inputs.put("process.argv", "echo unsafe");
            case "dynamic" -> inputs.put("process.argv", List.of("env://UNREVIEWED"));
            case "cwd_escape" -> inputs.put("process.cwd", "../outside");
            case "extra_binding" -> helper.map(helper.map(helper.map(profile.get("targets")).get("todo_api")).get("bindings")).put("env", Map.of("PATH", "/bin"));
            case "wrong_contract" -> {
                var manifest = helper.read(suite.resolve("suite_manifest.yaml"));
                helper.map(helper.map(manifest.get("targets")).get("todo_api")).put("provider_contract", "rest_client.v0.3");
                helper.write(suite.resolve("suite_manifest.yaml"), manifest);
            }
        }
        helper.write(suite.resolve("env_profiles/local_native.yaml"), profile);
        helper.write(suite.resolve("test_cases/items.yaml"), test);
        assertThatThrownBy(() -> helper.prepare(suite)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void cliEvidenceCannotBeRelabelledAsHttp() throws Exception {
        var result = helper.run(fixture("/usr/bin/true", List.of(), 0, ""));
        helper.assertValid(result);
        var json = helper.json.readTree(result.resultJson().toFile());
        var provider = (com.fasterxml.jackson.databind.node.ObjectNode) json.path("provider_results").get(0);
        provider.put("provider_type", "rest_client").put("provider_contract", "rest_client.v0.3");
        helper.json.writeValue(result.resultJson().toFile(), json);
        assertThat(new EvidenceHardeningService().validateResult(result.resultJson()).valid()).isFalse();
    }

    Path fixture(String executable, List<String> argv, int expectedExit, String expectedOutput) throws Exception {
        helper.temporary = temporary;
        Path suite = helper.fixture("http://127.0.0.1:43199", "GET", false);
        var manifest = helper.read(suite.resolve("suite_manifest.yaml"));
        helper.map(helper.map(manifest.get("targets")).get("todo_api")).put("provider_contract", "cli_process.v0.3");
        helper.write(suite.resolve("suite_manifest.yaml"), manifest);
        Path binary = Path.of(executable).toRealPath();
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary)));
        var profile = helper.read(suite.resolve("env_profiles/local_native.yaml"));
        helper.map(helper.map(profile.get("targets")).get("todo_api")).put("bindings", Map.of("executable", binary.toString(), "executable_sha256", digest));
        helper.write(suite.resolve("env_profiles/local_native.yaml"), profile);
        helper.write(suite.resolve("test_cases/items.yaml"), Map.of("dsl_version", "v0.3", "test_case_id", "TC-HTTP", "title", "Reviewed CLI",
                "execute", List.of(Map.of("id", "invoke", "target", "todo_api", "op", "run_command", "with", Map.of("process.argv", argv))),
                "verify", List.of(
                    Map.of("id", "status_ok", "type", "assertion", "assert", Map.of("actual", "step://invoke/exit_code", "operator", "equals", "expected", expectedExit)),
                    Map.of("id", "stdout_ok", "type", "assertion", "assert", Map.of("actual", "step://invoke/stdout", "operator", "equals", "expected", expectedOutput)))));
        var contract = helper.read(Path.of("docs/02-architecture/contracts/provider-contracts/cli_process_v0_3.yaml"));
        helper.write(suite.resolve("contracts/cli_process_v0_3.yaml"), contract);
        var review = helper.json.readValue(suite.resolve("review-input.json").toFile(), Map.class);
        review.put("reviewed_contracts", List.of(Map.of("path", "contracts/cli_process_v0_3.yaml", "provider_contract", "cli_process.v0.3")));
        helper.map(((List<?>) review.get("requirement_mapping")).get(0)).put("oracle_refs", List.of("test_cases/items.yaml#status_ok", "test_cases/items.yaml#stdout_ok"));
        helper.json.writeValue(suite.resolve("review-input.json").toFile(), review);
        return suite;
    }
}

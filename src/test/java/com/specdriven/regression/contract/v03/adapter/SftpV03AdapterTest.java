package com.specdriven.regression.contract.v03.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ResolvedTarget;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.contract.v03.ref.V03ReferenceResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpV03AdapterTest {
    @TempDir Path suite;
    private V03ExecutionContext context;
    private final SftpV03Adapter adapter = new SftpV03Adapter();

    @BeforeEach void setup() throws IOException {
        Files.createDirectories(suite.resolve("fixtures"));
        Files.writeString(suite.resolve("fixtures/orders"), "private order material\n");
        context = contextWith(SftpRunnerConnection.BINDINGS);
    }

    @Test void cleanupWithoutAnOwnershipReceiptDoesNotContactTheServer() throws IOException {
        var result = run("sftp_cleanup_batch", Map.of());
        assertThat(result.passed()).isTrue();
        assertThat(result.outputs()).containsEntry("deleted", false);
        assertThat(Files.readString(context.runDir().resolve(result.evidenceRefs().get(0))))
                .contains("sftp_cleanup_batch").doesNotContain("invalid-private-key", "private order material");
    }

    @Test void rejectsTraversalAndOversizeFixturesBeforeStartingAnUpload() throws IOException {
        for (String path : List.of("../another-run", "/absolute", "a*", ".upload-part", "file\\name")) {
            assertThat(run("sftp_upload_batch", Map.of("name", path, "payload_ref", "artifact://fixtures/orders")).failureCode())
                    .isEqualTo("SFTP_INVALID_CONFIGURATION");
        }
        Files.write(suite.resolve("fixtures/orders"), new byte[SftpV03Adapter.MAX_BYTES + 1]);
        assertThat(run("sftp_upload_batch", Map.of("name", "orders.csv", "payload_ref", "artifact://fixtures/orders")).failureCode())
                .isEqualTo("SFTP_FILE_TOO_LARGE");
    }

    @Test void rejectsAlteredBindingsAndMasksTheRunnerKeyFromErrorsAndEvidence() throws IOException {
        Map<String, Object> upload = Map.of("name", "orders.csv", "payload_ref", "artifact://fixtures/orders");
        var result = run("sftp_upload_batch", upload);
        assertThat(result.failureCode()).isEqualTo("SFTP_REQUEST_FAILED");
        assertThat(result.toString()).doesNotContain("invalid-private-key", "runner-internal.example");
        assertThat(Files.readString(context.runDir().resolve(result.evidenceRefs().get(0))))
                .doesNotContain("invalid-private-key", "runner-internal.example", "private order material");
        var bindings = new HashMap<>(SftpRunnerConnection.BINDINGS);
        bindings.put("private_key_ref", "env://UNRELATED");
        context = contextWith(bindings);
        assertThat(run("sftp_upload_batch", upload).failureCode()).isEqualTo("SFTP_INVALID_CONFIGURATION");
    }

    @Test void rejectsInvalidWaitsCountsAndDigestsAndDoesNotAdoptMissingBatches() {
        var inputs = new HashMap<String, Object>(Map.of("name", "return.csv", "expected_records", 2,
                "expected_sha256", "ab".repeat(32), "timeout_ms", 300));
        assertThat(run("sftp_verify_return", inputs).failureCode()).isEqualTo("SFTP_BATCH_MISSING");
        for (Object timeout : List.of(0, 30_001, 100.5)) {
            inputs.put("timeout_ms", timeout);
            assertThat(run("sftp_verify_return", inputs).failureCode()).isEqualTo("SFTP_INVALID_CONFIGURATION");
        }
        inputs.put("timeout_ms", 300);
        inputs.put("expected_records", -1);
        assertThat(run("sftp_verify_return", inputs).failureCode()).isEqualTo("SFTP_INVALID_CONFIGURATION");
        inputs.put("expected_records", 2);
        inputs.put("expected_sha256", "invalid");
        assertThat(run("sftp_verify_return", inputs).failureCode()).isEqualTo("SFTP_INVALID_CONFIGURATION");
    }

    private V03ExecutionContext contextWith(Map<String, Object> bindings) {
        var target = new V03ResolvedTarget("exchange", "sftp.v0.3", "sftp", "external_sftp", "native", bindings);
        return new V03ExecutionContext(suite, suite.resolve("run"), "external_sftp", Map.of("exchange", target),
                Map.of("fixtures", suite.resolve("fixtures")), new HashMap<>(), new HashMap<>(), List.of(), Map.of(),
                Map.of("REGRESS_SFTP_ENDPOINT", "sftp://runner-internal.example", "REGRESS_SFTP_USERNAME", "test",
                        "REGRESS_SFTP_PRIVATE_KEY", "invalid-private-key", "REGRESS_SFTP_KNOWN_HOSTS", "invalid-host-key",
                        "REGRESS_SFTP_ROOT", "/exchange"), new V03ReferenceResolver(), Instant.EPOCH);
    }

    private V03StepResult run(String operation, Map<String, Object> inputs) {
        String phase = switch (operation) {
            case "sftp_verify_return" -> "verify";
            case "sftp_cleanup_batch" -> "cleanup";
            default -> "execute";
        };
        return adapter.execute(new V03ExecutionStep("CASE", phase, "id", "exchange", "sftp.v0.3", "sftp",
                "external_sftp", "native", operation, inputs, ""), context);
    }
}

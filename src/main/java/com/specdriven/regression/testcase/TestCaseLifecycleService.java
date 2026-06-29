package com.specdriven.regression.testcase;

import com.specdriven.regression.readiness.AcReadinessItem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TestCaseLifecycleService {

    private final Clock clock;

    public TestCaseLifecycleService() {
        this(Clock.systemUTC());
    }

    TestCaseLifecycleService(Clock clock) {
        this.clock = clock;
    }

    public TestCaseDraftResult generateDraft(
            Path rpRoot,
            AcReadinessItem ac,
            ExecutionContextReadiness executionContext) {
        if (!ac.executableDraftAllowed()) {
            return new TestCaseDraftResult("none", null, List.of("AC is not ready for generation"));
        }

        String testCaseId = testCaseId(ac.acId());
        Path approvedPath = rpRoot.resolve("tests/approved/" + testCaseId + ".yaml");
        boolean approvedExists = Files.exists(approvedPath);
        String artifactStatus = executionContext.ready() ? "draft_executable_test_case" : "draft_test_skeleton";
        String generatedType = approvedExists ? "update_proposal" : artifactStatus;
        Path draftPath = rpRoot.resolve("tests/draft/" + testCaseId + "-" + generatedType + ".yaml");

        String content = switch (generatedType) {
            case "update_proposal" -> updateProposalContent(
                    ac,
                    testCaseId,
                    Path.of("tests/approved/" + testCaseId + ".yaml"),
                    executionContext);
            case "draft_executable_test_case" -> executableDraftContent(ac, testCaseId, executionContext);
            default -> skeletonDraftContent(ac, testCaseId, executionContext);
        };

        writeNewFile(draftPath, content);
        return new TestCaseDraftResult(generatedType, draftPath, executionContext.gaps());
    }

    private String executableDraftContent(
            AcReadinessItem ac,
            String testCaseId,
            ExecutionContextReadiness executionContext) {
        return """
                dsl_version: v0.2
                test_case_id: %s
                status: draft_executable
                revision: 1
                labels:
                  package: %s
                  runtime_unit: %s
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                source_fingerprint: %s
                scenario:
                  type: integration
                  scope: rp
                  capabilities: %s
                targets:
                  %s:
                    type: %s
                    runner: %s
                    execution_mode: %s
                    environment: %s
                %s
                execute:
                  - id: execute_rp_behavior
                    target: %s
                    operation: %s
                    with:
                      primary_input: ${setup.fixtures.primary_input}
                    outputs:
                      actual_output:
                        ref: actual/output.txt
                      execution_log:
                        ref: logs/execution.log
                expected_results:
                  owner_approved_expected:
                    type: expected_result_artifact
                    status: pending_owner_review
                    ref: pending_owner_approved_expected_result
                    owner_action: Approve expected result before regression execution.
                verify:
                  - id: verify_expected_output
                    type: file_diff
                    actual: ${execute.execute_rp_behavior.outputs.actual_output}
                    expected: ${expected_results.owner_approved_expected.ref}
                evidence:
                  required:
                    - ${execute.execute_rp_behavior.outputs.execution_log}
                    - ${execute.execute_rp_behavior.outputs.actual_output}
                    - ${verify.verify_expected_output.result}
                runtime:
                  timeout: PT10M
                  retry:
                    max_attempts: 0
                  cleanup_required: %s
                  destructive_actions_allowed: false
                """.formatted(
                testCaseId,
                ac.rpId(),
                executionContext.ruId(),
                ac.acId(),
                fingerprint(ac),
                yamlList(executionContext.capabilities()),
                executionContext.ruId(),
                targetType(executionContext.capabilities()),
                executionContext.adapter(),
                executionContext.executionMode(),
                executionContext.environmentRef(),
                fixtureYaml(executionContext.capabilities()),
                executionContext.ruId(),
                operation(executionContext.capabilities()),
                requiresCleanup(executionContext.capabilities()));
    }

    private String inputBindAs(List<String> capabilities) {
        if (capabilities.contains("dataset_input")) {
            return "dataset";
        }
        if (capabilities.contains("file_input")) {
            return "input_file";
        }
        if (capabilities.contains("api_payload")) {
            return "api_payload";
        }
        if (capabilities.contains("message_event")) {
            return "message_event";
        }
        if (capabilities.contains("db_seed")) {
            return "db_seed";
        }
        if (capabilities.contains("config_input")) {
            return "config_file";
        }
        if (capabilities.contains("env_var")) {
            return "env_var";
        }
        return "existing_state";
    }

    private String inputLifecycle(List<String> capabilities) {
        return requiresCleanup(capabilities) ? "state_mutating" : "read_only";
    }

    private String targetType(List<String> capabilities) {
        if (capabilities.contains("batch_execution")) {
            return "batch_runner";
        }
        if (capabilities.contains("api_payload")) {
            return "application";
        }
        if (capabilities.contains("message_event")) {
            return "event_bus";
        }
        return "application";
    }

    private String operation(List<String> capabilities) {
        if (capabilities.contains("batch_execution")) {
            return "run_batch";
        }
        if (capabilities.contains("api_payload")) {
            return "call_api";
        }
        if (capabilities.contains("message_event")) {
            return "publish_message";
        }
        return "run_application";
    }

    private boolean requiresCleanup(List<String> capabilities) {
        return capabilities.contains("db_seed")
                || capabilities.contains("message_event")
                || capabilities.contains("config_input")
                || capabilities.contains("env_var");
    }

    private String fixtureYaml(List<String> capabilities) {
        if (!requiresCleanup(capabilities)) {
            return """
                    setup:
                      fixtures:
                        primary_input:
                          type: %s
                          lifecycle: %s
                          ref: pending_owner_selected_input
                          owner_action: Select checked-in or cataloged logical input data for this AC.
                      cleanup: []
                    """.formatted(inputBindAs(capabilities), inputLifecycle(capabilities));
        }
        return """
                setup:
                  fixtures:
                    primary_input:
                      type: %s
                      lifecycle: %s
                      ref: pending_owner_selected_input
                      owner_action: Select checked-in or cataloged logical input data for this AC.
                      cleanup_ref: pending_owner_cleanup_ref
                  cleanup:
                    - id: cleanup_primary_input
                      action: cleanup_bound_input
                      input: ${setup.fixtures.primary_input}
                """.formatted(inputBindAs(capabilities), inputLifecycle(capabilities));
    }

    private String skeletonDraftContent(
            AcReadinessItem ac,
            String testCaseId,
            ExecutionContextReadiness executionContext) {
        return """
                dsl_version: v0.2
                test_case_id: %s
                status: draft_skeleton
                revision: 1
                labels:
                  package: %s
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                source_fingerprint: %s
                readiness_gaps:
                %s
                """.formatted(
                testCaseId,
                ac.rpId(),
                ac.acId(),
                fingerprint(ac),
                readinessGapsYaml(executionContext.gaps()));
    }

    private String updateProposalContent(
            AcReadinessItem ac,
            String testCaseId,
            Path approvedPath,
            ExecutionContextReadiness executionContext) {
        List<String> gaps = new ArrayList<>(executionContext.gaps());
        if (gaps.isEmpty()) {
            gaps.add("approved test exists");
        }
        return """
                proposal_type: test_case_update
                dsl_version: v0.2
                test_case_id: %s
                status: needs_update
                revision: 1
                labels:
                  package: %s
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                replaces: %s
                source_fingerprint: %s
                readiness_gaps:
                %s
                """.formatted(
                testCaseId,
                ac.rpId(),
                ac.acId(),
                approvedPath.toString(),
                fingerprint(ac),
                readinessGapsYaml(gaps));
    }

    private String testCaseId(String acId) {
        return acId.replace("-AC-", "-TC-");
    }

    private String fingerprint(AcReadinessItem ac) {
        return Integer.toHexString((ac.acId() + ac.ownerAuthoredTitle() + Instant.now(clock)).hashCode());
    }

    private String yamlList(List<String> values) {
        if (values.isEmpty()) {
            return "[]";
        }
        return "[" + String.join(", ", values) + "]";
    }

    private String readinessGapsYaml(List<String> gaps) {
        if (gaps.isEmpty()) {
            return "  []";
        }
        StringBuilder builder = new StringBuilder();
        for (String gap : gaps) {
            boolean approvedExists = "approved test exists".equals(gap);
            builder.append("  - field_path: ")
                    .append(approvedExists ? "tests/approved" : gap)
                    .append("\n");
            builder.append("    reason: ")
                    .append(approvedExists ? "approved_test_exists" : "execution_context_incomplete")
                    .append("\n");
            builder.append("    gap: ").append(gap).append("\n");
            builder.append("    owner_action: ")
                    .append(approvedExists
                            ? "Review the generated update proposal instead of overwriting the approved test."
                            : "Complete RP/RU mapping execution context before executable test generation.")
                    .append("\n");
        }
        return builder.toString().stripTrailing();
    }

    private void writeNewFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write test case draft: " + path, e);
        }
    }
}

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
                dsl_version: v1
                test_case_id: %s
                rp_id: %s
                ac_id: %s
                artifact_status: draft_executable_test_case
                revision: 1
                owner: product_developer
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                source_fingerprint: %s
                scenario:
                  type: integration
                  scope: rp
                  capabilities: %s
                execution_target:
                  ru_id: %s
                  adapter: %s
                  execution_mode: %s
                  environment_ref: %s
                expected:
                  status: pending_owner_review
                oracles:
                  owner_approved_expected:
                    type: pending_owner_approved_rule
                    status: pending_owner_review
                    owner_action: Approve expected result or replace with reviewed oracle before regression execution.
                package_inputs:
                  inputs:
                    primary_input:
                      bind_as: %s
                      lifecycle: %s
                      ref: pending_owner_selected_input
                      owner_action: Select checked-in or cataloged logical input data for this AC.
                %s
                steps:
                  - id: execute_rp_behavior
                    action: call_ru
                    target_ru_id: %s
                    input: ${package_inputs.inputs.primary_input}
                assertions:
                  - type: pending_owner_approved_rule
                    oracle: ${oracles.owner_approved_expected}
                evidence_required:
                  - execution_log
                  - assertion_result
                policy:
                  cleanup_required: %s
                  destructive_actions_allowed: false
                """.formatted(
                testCaseId,
                ac.rpId(),
                ac.acId(),
                ac.acId(),
                fingerprint(ac),
                yamlList(executionContext.capabilities()),
                executionContext.ruId(),
                executionContext.adapter(),
                executionContext.executionMode(),
                executionContext.environmentRef(),
                inputBindAs(executionContext.capabilities()),
                inputLifecycle(executionContext.capabilities()),
                fixtureYaml(executionContext.capabilities()),
                executionContext.ruId(),
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

    private boolean requiresCleanup(List<String> capabilities) {
        return capabilities.contains("db_seed")
                || capabilities.contains("message_event")
                || capabilities.contains("config_input")
                || capabilities.contains("env_var");
    }

    private String fixtureYaml(List<String> capabilities) {
        if (!requiresCleanup(capabilities)) {
            return """
                    fixture:
                      setup: []
                      cleanup: []""";
        }
        return """
                fixture:
                  setup: []
                  cleanup:
                    - id: cleanup_primary_input
                      action: cleanup_bound_input
                      input: ${package_inputs.inputs.primary_input}""";
    }

    private String skeletonDraftContent(
            AcReadinessItem ac,
            String testCaseId,
            ExecutionContextReadiness executionContext) {
        return """
                dsl_version: v1
                test_case_id: %s
                rp_id: %s
                ac_id: %s
                artifact_status: draft_test_skeleton
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                source_fingerprint: %s
                readiness_gaps:
                %s
                """.formatted(
                testCaseId,
                ac.rpId(),
                ac.acId(),
                ac.acId(),
                fingerprint(ac),
                readinessGapsYaml(executionContext.gaps()));
    }

    private String updateProposalContent(
            AcReadinessItem ac,
            Path approvedPath,
            ExecutionContextReadiness executionContext) {
        List<String> gaps = new ArrayList<>(executionContext.gaps());
        if (gaps.isEmpty()) {
            gaps.add("approved test exists");
        }
        return """
                proposal_type: test_case_update
                rp_id: %s
                ac_id: %s
                artifact_status: needs_update
                replaces: %s
                source_fingerprint: %s
                readiness_gaps:
                %s
                """.formatted(
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

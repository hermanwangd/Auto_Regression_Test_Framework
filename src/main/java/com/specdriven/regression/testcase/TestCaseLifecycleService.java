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
                  status: pending
                steps:
                  - id: execute_rp_behavior
                    action: call_ru
                    target_ru_id: %s
                assertions:
                  - type: pending_owner_approved_rule
                    oracle: pending
                evidence_required:
                  - execution_log
                  - assertion_result
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
                executionContext.ruId());
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
                indentedList(executionContext.gaps(), "  - "));
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
                indentedList(gaps, "  - "));
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

    private String indentedList(List<String> values, String prefix) {
        if (values.isEmpty()) {
            return prefix + "none\n";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            builder.append(prefix).append(value).append('\n');
        }
        return builder.toString();
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

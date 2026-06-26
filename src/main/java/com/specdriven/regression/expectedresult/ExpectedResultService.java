package com.specdriven.regression.expectedresult;

import com.specdriven.regression.readiness.AcReadinessItem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class ExpectedResultService {

    public ExpectedResultDraftResult draftExpectedResult(
            Path rpRoot,
            AcReadinessItem ac,
            List<String> inputRefs,
            String outputRef) {
        String expectedResultId = expectedResultId(ac.acId());
        Path path = rpRoot.resolve("expected-results/draft/" + expectedResultId + ".yaml");
        String status = ac.executableDraftAllowed() ? "draft" : "blocked";
        String content = ac.executableDraftAllowed()
                ? draftContent(expectedResultId, ac, inputRefs, outputRef)
                : blockedContent(expectedResultId, ac, inputRefs, outputRef);
        write(path, content);
        return new ExpectedResultDraftResult(status, path);
    }

    public ExpectedResultEligibilityReport checkEligibility(Path rpRoot, String acId) {
        String expectedResultId = expectedResultId(acId);
        Path approvedPath = rpRoot.resolve("expected-results/approved/" + expectedResultId + ".yaml");
        Path draftPath = rpRoot.resolve("expected-results/draft/" + expectedResultId + ".yaml");

        if (Files.isRegularFile(approvedPath)) {
            Map<String, Object> artifact = readYamlMap(approvedPath);
            List<ExpectedResultGap> gaps = approvalGaps(artifact);
            boolean eligible = gaps.isEmpty();
            return new ExpectedResultEligibilityReport(
                    eligible,
                    stringValue(artifact.get("status")),
                    approvedPath,
                    List.copyOf(gaps));
        }

        if (Files.isRegularFile(draftPath)) {
            Map<String, Object> artifact = readYamlMap(draftPath);
            return new ExpectedResultEligibilityReport(
                    false,
                    stringValue(artifact.get("status")),
                    draftPath,
                    List.of(new ExpectedResultGap(
                            "status",
                            "Approve expected result before using it as regression truth.")));
        }

        return new ExpectedResultEligibilityReport(
                false,
                "missing",
                null,
                List.of(new ExpectedResultGap(
                        "expected-results.approved",
                        "Create and approve expected-result artifact for AC `" + acId + "`.")));
    }

    private List<ExpectedResultGap> approvalGaps(Map<String, Object> artifact) {
        List<ExpectedResultGap> gaps = new ArrayList<>();
        String status = stringValue(artifact.get("status"));
        if (!"approved_for_regression".equals(status)) {
            gaps.add(new ExpectedResultGap(
                    "status",
                    "Approve expected result before using it as regression truth."));
        }
        for (String field : List.of("source_refs", "approved_by", "approved_at", "approval_ref")) {
            if (isMissing(artifact.get(field))) {
                gaps.add(new ExpectedResultGap(
                        field,
                        "Add required approval/source field `" + field + "` before regression execution."));
            }
        }
        return gaps;
    }

    private String draftContent(
            String expectedResultId,
            AcReadinessItem ac,
            List<String> inputRefs,
            String outputRef) {
        return """
                expected_result_id: %s
                rp_id: %s
                ac_id: %s
                status: draft
                source_refs:
                  - rp_feature_spec.md
                  - acceptance_criteria.md#%s
                input_refs:
                %s
                expected_outputs:
                  output_ref: %s
                assumptions: []
                unresolved_gaps: []
                approved_by: null
                approved_at: null
                approval_ref: null
                blocked_reason: null
                """.formatted(
                expectedResultId,
                ac.rpId(),
                ac.acId(),
                ac.acId(),
                indentedList(inputRefs, "  - "),
                outputRef);
    }

    private String blockedContent(
            String expectedResultId,
            AcReadinessItem ac,
            List<String> inputRefs,
            String outputRef) {
        List<String> gaps = ac.gaps().stream().map(gap -> gap.fieldPath() + ": " + gap.ownerAction()).toList();
        return """
                expected_result_id: %s
                rp_id: %s
                ac_id: %s
                status: blocked
                source_refs:
                  - acceptance_criteria.md#%s
                input_refs:
                %s
                expected_outputs:
                  output_ref: %s
                assumptions: []
                unresolved_gaps:
                %s
                approved_by: null
                approved_at: null
                approval_ref: null
                blocked_reason: AC is not ready for expected-result drafting
                """.formatted(
                expectedResultId,
                ac.rpId(),
                ac.acId(),
                ac.acId(),
                indentedList(inputRefs, "  - "),
                outputRef,
                indentedList(gaps, "  - "));
    }

    private String expectedResultId(String acId) {
        return acId.replace("-AC-", "-ER-");
    }

    private String indentedList(List<String> values, String prefix) {
        if (values.isEmpty()) {
            return "  []";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            builder.append(prefix).append(value).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write expected-result artifact: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlMap(Path path) {
        try {
            Object loaded = new Yaml().load(Files.readString(path));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read expected-result artifact: " + path, e);
        }
    }

    private boolean isMissing(Object value) {
        return value == null
                || value instanceof String text && (text.isBlank() || "null".equals(text))
                || value instanceof List<?> list && list.isEmpty();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}

package com.specdriven.regression.summary;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteSummaryValidatorTest {

    @TempDir Path root;
    private final SuiteSummaryCalculator calculator = new SuiteSummaryCalculator();
    private final SuiteSummaryValidator validator = new SuiteSummaryValidator();

    @Test
    void acceptsACompleteLeafAndWriterUsesOneCanonicalModel() throws Exception {
        writeRequiredArtifacts(root);
        SuiteSummaryDocument document = leafDocument();

        assertThat(validator.validate(document, root)).isEmpty();

        SuiteSummaryWriter writer = new SuiteSummaryWriter();
        Path json = writer.write(root, document, true);
        assertThat(json).hasFileName("suite_summary.json");
        Map<?, ?> jsonValue = new ObjectMapper().readValue(json.toFile(), Map.class);
        Map<?, ?> yamlValue = new org.yaml.snakeyaml.Yaml().load(Files.readString(root.resolve("suite_summary.yaml")));
        assertThat(yamlValue).isEqualTo(jsonValue);
        assertThat(jsonValue.get("termination_reason")).isNull();
    }

    @Test
    void reportsArithmeticFailureAndPartialImplicationWithOwnerAction() throws Exception {
        writeRequiredArtifacts(root);
        SuiteSummaryDocument valid = leafDocument();
        SuiteSummaryDocument.Counts bad = new SuiteSummaryDocument.Counts(
                "partial", false, 3, 1, 1, 0, 0, 0, null, null);
        SuiteSummaryDocument invalid = copy(valid, "passed", "complete", "timeout", bad, bad,
                valid.childAggregateSummary(), valid.failureSummary(), valid.aggregationErrors(), valid.children());

        assertThat(validator.validate(invalid, root))
                .extracting(finding -> finding.fieldPath())
                .contains("total_summary.test_case_count", "total_summary.unknown_test_case_count",
                        "termination_reason");
        assertThat(validator.validate(invalid, root))
                .allSatisfy(finding -> assertThat(finding.ownerAction()).isNotBlank());
    }

    @Test
    void validatesFailureTotalsTimingAndDigestWithoutThrowing() throws Exception {
        writeRequiredArtifacts(root);
        SuiteSummaryDocument valid = leafDocument();
        SuiteSummaryDocument.FailureSummary failure = new SuiteSummaryDocument.FailureSummary(
                2, 0, 0, 1, Map.of("VERIFICATION_FAILED", 1), List.of(), List.of());
        SuiteSummaryDocument invalid = new SuiteSummaryDocument(
                valid.suiteSummaryVersion(), valid.suiteId(), valid.batchId(), valid.runId(), valid.profile(),
                valid.status(), valid.completionStatus(), valid.terminationReason(), "not-a-time",
                "2026-07-11T08:59:00Z", valid.durationMs(), "2026-07-11T08:58:00Z",
                valid.frameworkVersion(), valid.dslVersion(), valid.suiteManifestRef(), "sha256:BAD",
                valid.selfSummary(), valid.childAggregateSummary(), valid.totalSummary(), failure,
                valid.evidenceSummary(), valid.aggregationErrors(), valid.children());

        assertThat(validator.validate(invalid, root))
                .extracting(finding -> finding.fieldPath())
                .contains("start_time", "suite_manifest_digest", "failure_summary.test_failure_count",
                        "failure_summary.total_issue_count");
    }

    @Test
    void requiresSharedBatchUniqueRunsAndAuthoritativeSnapshotEquality() throws Exception {
        writeRequiredArtifacts(root);
        Path childDir = Files.createDirectories(root.resolve("children/ONE"));
        writeRequiredArtifacts(childDir);
        SuiteSummaryDocument childDocument = leafDocument("ONE", "BATCH", "RUN-SAME", "passed");
        new SuiteSummaryWriter().write(childDir, childDocument, false);

        SuiteSummaryDocument.Counts wrongSnapshot = calculator.counts(List.of("failed"), "complete");
        SuiteSummaryDocument.ChildEntry first = new SuiteSummaryDocument.ChildEntry(
                "ONE", "children/ONE/suite_manifest.yaml", "OTHER-BATCH", "RUN-SAME", "passed",
                childDocument.startTime(), childDocument.endTime(), childDocument.durationMs(),
                "children/ONE/suite_summary.json", wrongSnapshot);
        SuiteSummaryDocument.ChildEntry second = new SuiteSummaryDocument.ChildEntry(
                "TWO", "children/ONE/suite_manifest.yaml", "BATCH", "RUN-SAME", "passed",
                childDocument.startTime(), childDocument.endTime(), childDocument.durationMs(),
                "children/ONE/suite_summary.json", childDocument.totalSummary());
        SuiteSummaryDocument parent = aggregationDocument(List.of(first, second));

        assertThat(validator.validate(parent, root))
                .extracting(finding -> finding.fieldPath())
                .contains("children[0].batch_id", "children[1].run_id", "children[0].total_summary",
                        "children[1].child_suite_id");
    }

    @Test
    void rejectsAbsoluteTraversalHomeAndSymlinkEscapeAsFindings() throws Exception {
        writeRequiredArtifacts(root);
        Path outside = Files.createTempFile("outside-summary", ".json");
        Files.createSymbolicLink(root.resolve("escaped.json"), outside);

        for (String ref : List.of(outside.toString(), "../outside.json", "~/summary.json", "escaped.json")) {
            SuiteSummaryDocument valid = leafDocument();
            SuiteSummaryDocument invalid = new SuiteSummaryDocument(
                    valid.suiteSummaryVersion(), valid.suiteId(), valid.batchId(), valid.runId(), valid.profile(),
                    valid.status(), valid.completionStatus(), valid.terminationReason(), valid.startTime(), valid.endTime(),
                    valid.durationMs(), valid.generatedAt(), valid.frameworkVersion(), valid.dslVersion(), ref,
                    valid.suiteManifestDigest(), valid.selfSummary(), valid.childAggregateSummary(), valid.totalSummary(),
                    valid.failureSummary(), valid.evidenceSummary(), valid.aggregationErrors(), valid.children());
            assertThat(validator.validate(invalid, root))
                    .extracting(finding -> finding.fieldPath()).contains("suite_manifest_ref");
        }
    }

    @Test
    void missingRequiredSectionsReturnFindingsWithoutThrowing() throws Exception {
        writeRequiredArtifacts(root);
        SuiteSummaryDocument valid = leafDocument();
        List<SuiteSummaryDocument> invalid = List.of(
                withSections(valid, null, valid.childAggregateSummary(), valid.totalSummary(),
                        valid.failureSummary(), valid.evidenceSummary()),
                withSections(valid, valid.selfSummary(), null, valid.totalSummary(),
                        valid.failureSummary(), valid.evidenceSummary()),
                withSections(valid, valid.selfSummary(), valid.childAggregateSummary(), null,
                        valid.failureSummary(), valid.evidenceSummary()),
                withSections(valid, valid.selfSummary(), valid.childAggregateSummary(), valid.totalSummary(),
                        null, valid.evidenceSummary()),
                withSections(valid, valid.selfSummary(), valid.childAggregateSummary(), valid.totalSummary(),
                        valid.failureSummary(), null));

        assertThat(validator.validate(invalid.get(0), root)).extracting(f -> f.fieldPath()).contains("self_summary");
        assertThat(validator.validate(invalid.get(1), root)).extracting(f -> f.fieldPath()).contains("child_aggregate_summary");
        assertThat(validator.validate(invalid.get(2), root)).extracting(f -> f.fieldPath()).contains("total_summary");
        assertThat(validator.validate(invalid.get(3), root)).extracting(f -> f.fieldPath()).contains("failure_summary");
        assertThat(validator.validate(invalid.get(4), root)).extracting(f -> f.fieldPath()).contains("evidence_summary");
        invalid.forEach(document -> assertThat(validator.validate(document, root))
                .allSatisfy(finding -> assertThat(finding.ownerAction()).isNotBlank()));
    }

    @Test
    void rejectsUnsafeChildManifestRefAndUnknownChildStatus() throws Exception {
        writeRequiredArtifacts(root);
        SuiteSummaryDocument.Counts childTotal = calculator.counts(List.of("passed"), "complete");
        SuiteSummaryDocument.ChildEntry child = new SuiteSummaryDocument.ChildEntry(
                "ONE", "../suite_manifest.yaml", "BATCH", "RUN-ONE", "unknown",
                "2026-07-11T09:00:00Z", "2026-07-11T09:00:01Z", 1000,
                "missing-summary.json", childTotal);

        assertThat(validator.validate(aggregationDocument(List.of(child)), root))
                .extracting(f -> f.fieldPath())
                .contains("children[0].ref", "children[0].status");
    }

    @Test
    void validatesEvidenceUrisAgainstIndexAndRejectsUnsafeEvidencePaths() throws Exception {
        writeRequiredArtifacts(root);
        Files.writeString(root.resolve("evidence/evidence_index.yaml"),
                "entries:\n  - evidence_id: EV-1\n    masking_applied: true\n");
        Files.writeString(root.resolve("result.json"), "{}\n");
        SuiteSummaryDocument valid = leafDocument();
        SuiteSummaryDocument.FailedTestRef failed = new SuiteSummaryDocument.FailedTestRef(
                "TC-1", "failed", "ASSERT", "VERIFICATION_FAILED", "result.json",
                List.of("evidence://MISSING", "../secret.txt"));
        SuiteSummaryDocument.FailureSummary failure = new SuiteSummaryDocument.FailureSummary(
                0, 0, 0, 0, Map.of(), List.of(failed), List.of());
        SuiteSummaryDocument invalid = withFailureAndEvidence(valid, failure,
                new SuiteSummaryDocument.EvidenceSummary(1, true, "evidence/evidence_index.yaml"));

        assertThat(validator.validate(invalid, root))
                .extracting(f -> f.fieldPath())
                .contains("failure_summary.failed_test_refs[0].evidence_refs[0]",
                        "failure_summary.failed_test_refs[0].evidence_refs[1]");
    }

    @Test
    void recursivelyRejectsSchemaInvalidReferencedChildAndStopsCycles() throws Exception {
        writeRequiredArtifacts(root);
        Path childDir = Files.createDirectories(root.resolve("children/ONE"));
        writeRequiredArtifacts(childDir);
        SuiteSummaryDocument child = leafDocument("ONE", "BATCH", "RUN-ONE", "passed");
        SuiteSummaryDocument invalidChild = new SuiteSummaryDocument(
                child.suiteSummaryVersion(), child.suiteId(), child.batchId(), child.runId(), child.profile(),
                child.status(), child.completionStatus(), child.terminationReason(), child.startTime(), child.endTime(),
                child.durationMs(), child.generatedAt(), child.frameworkVersion(), child.dslVersion(),
                child.suiteManifestRef(), "bad-digest", child.selfSummary(), child.childAggregateSummary(),
                child.totalSummary(), child.failureSummary(), child.evidenceSummary(), List.of(), List.of());
        new SuiteSummaryWriter().write(childDir, invalidChild, false);
        SuiteSummaryDocument.ChildEntry entry = childEntry(child, "children/ONE/suite_summary.json");

        assertThat(validator.validate(aggregationDocument(List.of(entry)), root))
                .extracting(f -> f.fieldPath()).contains("children[0].summary.suite_manifest_digest");

        SuiteSummaryDocument.Counts zero = calculator.counts(List.of(), "complete");
        SuiteSummaryDocument.ChildEntry selfEntry = new SuiteSummaryDocument.ChildEntry(
                "ONE", "suite_manifest.yaml", "BATCH", "RUN-ONE", "skipped", child.startTime(), child.endTime(),
                child.durationMs(), "suite_summary.json", zero);
        SuiteSummaryDocument cyclic = aggregationDocument(List.of(selfEntry));
        new SuiteSummaryWriter().write(root, cyclic, false);
        assertThat(validator.validate(cyclic, root))
                .extracting(f -> f.fieldPath()).contains("children[0].summary_ref");
    }

    @Test
    void enforcesLeafAndAggregationFailureNavigationShapes() throws Exception {
        writeRequiredArtifacts(root);
        SuiteSummaryDocument leaf = leafDocument();
        SuiteSummaryDocument.FailedChildRef childFailure =
                new SuiteSummaryDocument.FailedChildRef("ONE", "failed", "missing.json");
        SuiteSummaryDocument invalidLeaf = withFailureAndEvidence(leaf,
                new SuiteSummaryDocument.FailureSummary(0, 0, 0, 0, Map.of(), List.of(), List.of(childFailure)),
                leaf.evidenceSummary());
        SuiteSummaryDocument.Counts nonZeroSelf = calculator.counts(List.of("passed"), "complete");
        SuiteSummaryDocument child = leafDocument("ONE", "BATCH", "RUN-ONE", "passed");
        SuiteSummaryDocument aggregation = aggregationDocument(List.of(childEntry(child, "missing-summary.json")));
        SuiteSummaryDocument.FailedTestRef testFailure = new SuiteSummaryDocument.FailedTestRef(
                "TC", "failed", "ASSERT", "VERIFICATION_FAILED", "missing.json", List.of());
        SuiteSummaryDocument invalidAggregation = copy(aggregation, aggregation.status(), aggregation.completionStatus(),
                null, nonZeroSelf, calculator.total(nonZeroSelf, aggregation.childAggregateSummary()),
                aggregation.childAggregateSummary(), new SuiteSummaryDocument.FailureSummary(
                        0, 0, 0, 0, Map.of(), List.of(testFailure), List.of()), List.of(), List.of());

        assertThat(validator.validate(invalidLeaf, root)).extracting(f -> f.fieldPath())
                .contains("failure_summary.failed_child_refs");
        assertThat(validator.validate(invalidAggregation, root)).extracting(f -> f.fieldPath())
                .contains("self_summary", "failure_summary.failed_test_refs");
    }

    @Test
    void malformedChildEndTimeNeverProducesGeneratedAtFinding() throws Exception {
        writeRequiredArtifacts(root);
        SuiteSummaryDocument child = leafDocument("ONE", "BATCH", "RUN-ONE", "passed");
        SuiteSummaryDocument.ChildEntry malformed = new SuiteSummaryDocument.ChildEntry(
                child.suiteId(), "suite_manifest.yaml", child.batchId(), child.runId(), child.status(),
                child.startTime(), "not-a-time", child.durationMs(), "missing-summary.json", child.totalSummary());

        List<String> fields = validator.validate(aggregationDocument(List.of(malformed)), root).stream()
                .map(finding -> finding.fieldPath())
                .toList();

        assertThat(fields).contains("children[0].end_time");
        assertThat(fields).noneMatch(field -> field.equals("children[0].generated_at"));
    }

    @Test
    void leafRequiresOneUniqueFailureRefPerFailedOrBlockedTest() throws Exception {
        writeRequiredArtifacts(root);
        SuiteSummaryDocument base = leafDocument();
        SuiteSummaryDocument.Counts oneFailure = calculator.counts(List.of("failed"), "complete");
        SuiteSummaryDocument missingRef = copy(base, "failed", "complete", null, oneFailure, oneFailure,
                base.childAggregateSummary(), new SuiteSummaryDocument.FailureSummary(
                        1, 0, 0, 1, Map.of("VERIFICATION_FAILED", 1), List.of(), List.of()),
                List.of(), List.of());

        assertThat(validator.validate(missingRef, root)).extracting(finding -> finding.fieldPath())
                .contains("failure_summary.failed_test_refs");

        Files.writeString(root.resolve("result.json"), "{}\n");
        SuiteSummaryDocument.FailedTestRef duplicate = new SuiteSummaryDocument.FailedTestRef(
                "TC-1", "failed", "ASSERT", "VERIFICATION_FAILED", "result.json", List.of());
        SuiteSummaryDocument.Counts twoFailures = calculator.counts(List.of("failed", "failed"), "complete");
        SuiteSummaryDocument duplicateRefs = copy(base, "failed", "complete", null, twoFailures, twoFailures,
                base.childAggregateSummary(), new SuiteSummaryDocument.FailureSummary(
                        2, 0, 0, 2, Map.of("VERIFICATION_FAILED", 2), List.of(duplicate, duplicate), List.of()),
                List.of(), List.of());

        assertThat(validator.validate(duplicateRefs, root)).extracting(finding -> finding.fieldPath())
                .contains("failure_summary.failed_test_refs[1].test_case_id");
    }

    @Test
    void rejectsEvidenceIndexWithNonMapRootOrNonListEntries() throws Exception {
        writeRequiredArtifacts(root);
        SuiteSummaryDocument document = leafDocument();

        Files.writeString(root.resolve("evidence/evidence_index.yaml"), "not-a-map\n");
        assertThat(validator.validate(document, root))
                .filteredOn(finding -> finding.fieldPath().equals("evidence_summary.evidence_index_ref"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.ownerAction()).isNotBlank());

        Files.writeString(root.resolve("evidence/evidence_index.yaml"), "entries: not-a-list\n");
        assertThat(validator.validate(document, root))
                .filteredOn(finding -> finding.fieldPath().equals("evidence_summary.evidence_index_ref"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.ownerAction()).isNotBlank());
    }

    @Test
    void attributesEachNonNullPartialRateToItsOwnField() throws Exception {
        writeRequiredArtifacts(root);
        SuiteSummaryDocument base = leafDocument();
        SuiteSummaryDocument.Counts partialWithRates = new SuiteSummaryDocument.Counts(
                "partial", true, 1, 1, 1, 0, 0, 0,
                new BigDecimal("100.0"), new BigDecimal("100.0"));
        SuiteSummaryDocument invalid = copy(base, "passed", "complete", null,
                partialWithRates, partialWithRates, base.childAggregateSummary(), base.failureSummary(),
                List.of(), List.of());

        assertThat(validator.validate(invalid, root)).extracting(finding -> finding.fieldPath())
                .contains("self_summary.pass_rate_percent", "self_summary.completion_rate_percent",
                        "total_summary.pass_rate_percent", "total_summary.completion_rate_percent");
    }

    private SuiteSummaryDocument leafDocument() {
        return leafDocument("LEAF", "BATCH", "RUN-LEAF", "passed");
    }

    private SuiteSummaryDocument leafDocument(String id, String batchId, String runId, String status) {
        SuiteSummaryDocument.Counts self = calculator.counts(List.of(status), "complete");
        SuiteSummaryDocument.ChildCounts children = calculator.childCounts(List.of(), 0);
        SuiteSummaryDocument.Counts total = calculator.total(self, children);
        return new SuiteSummaryDocument(
                "v0.3", id, batchId, runId, "local_v03", status, "complete", null,
                "2026-07-11T09:00:00Z", "2026-07-11T09:00:01Z", 1000, "2026-07-11T09:00:01Z",
                "0.3.0", "v0.3", "suite_manifest.yaml", "sha256:" + "a".repeat(64),
                self, children, total, new SuiteSummaryDocument.FailureSummary(0, 0, 0, 0, Map.of(), List.of(), List.of()),
                new SuiteSummaryDocument.EvidenceSummary(0, true, "evidence/evidence_index.yaml"), List.of(), List.of());
    }

    private SuiteSummaryDocument aggregationDocument(List<SuiteSummaryDocument.ChildEntry> children) {
        SuiteSummaryDocument base = leafDocument("PARENT", "BATCH", "RUN-PARENT", "passed");
        SuiteSummaryDocument.Counts zero = calculator.counts(List.of(), "complete");
        SuiteSummaryDocument.ChildCounts aggregate = calculator.childCounts(children, 0);
        return copy(base, "passed", "complete", null, zero, calculator.total(zero, aggregate), aggregate,
                base.failureSummary(), List.of(), children);
    }

    private SuiteSummaryDocument.ChildEntry childEntry(SuiteSummaryDocument child, String summaryRef) {
        return new SuiteSummaryDocument.ChildEntry(child.suiteId(), "children/ONE/suite_manifest.yaml",
                child.batchId(), child.runId(), child.status(), child.startTime(), child.endTime(), child.durationMs(),
                summaryRef, child.totalSummary());
    }

    private SuiteSummaryDocument withSections(SuiteSummaryDocument base, SuiteSummaryDocument.Counts self,
            SuiteSummaryDocument.ChildCounts child, SuiteSummaryDocument.Counts total,
            SuiteSummaryDocument.FailureSummary failure, SuiteSummaryDocument.EvidenceSummary evidence) {
        return new SuiteSummaryDocument(base.suiteSummaryVersion(), base.suiteId(), base.batchId(), base.runId(),
                base.profile(), base.status(), base.completionStatus(), base.terminationReason(), base.startTime(),
                base.endTime(), base.durationMs(), base.generatedAt(), base.frameworkVersion(), base.dslVersion(),
                base.suiteManifestRef(), base.suiteManifestDigest(), self, child, total, failure, evidence,
                base.aggregationErrors(), base.children());
    }

    private SuiteSummaryDocument withFailureAndEvidence(SuiteSummaryDocument base,
            SuiteSummaryDocument.FailureSummary failure, SuiteSummaryDocument.EvidenceSummary evidence) {
        return withSections(base, base.selfSummary(), base.childAggregateSummary(), base.totalSummary(), failure, evidence);
    }

    private SuiteSummaryDocument copy(
            SuiteSummaryDocument base, String status, String completion, String reason,
            SuiteSummaryDocument.Counts self, SuiteSummaryDocument.Counts total,
            SuiteSummaryDocument.ChildCounts childAggregate, SuiteSummaryDocument.FailureSummary failure,
            List<SuiteSummaryDocument.AggregationError> errors, List<SuiteSummaryDocument.ChildEntry> children) {
        return new SuiteSummaryDocument(
                base.suiteSummaryVersion(), base.suiteId(), base.batchId(), base.runId(), base.profile(), status,
                completion, reason, base.startTime(), base.endTime(), base.durationMs(), base.generatedAt(),
                base.frameworkVersion(), base.dslVersion(), base.suiteManifestRef(), base.suiteManifestDigest(),
                self, childAggregate, total, failure, base.evidenceSummary(), errors, children);
    }

    private void writeRequiredArtifacts(Path directory) throws Exception {
        Files.createDirectories(directory.resolve("evidence"));
        Files.writeString(directory.resolve("suite_manifest.yaml"), "manifest_version: v0.3\n");
        Files.writeString(directory.resolve("evidence/evidence_index.yaml"), "entries: []\n");
    }
}

package com.specdriven.regression.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcIntakeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void preservesOwnerAuthoredAcTextAndClassifiesReadyAc() throws Exception {
        Path acFile = tempDir.resolve("acceptance_criteria.md");
        String ownerText = "Valid input produces approved output";
        Files.writeString(acFile, """
                acceptance_criteria:
                  - ac_id: RP-AR-M1-data-pipeline-AC-001
                    rp_id: RP-AR-M1-data-pipeline
                    title: %s
                    owner: product_owner
                    classification: automatable
                    input: fixture/input/orders.csv
                    behavior: transform valid orders using the RP feature rules
                    expected_output: expected/output/orders_normalized.csv
                    allowed_side_effects:
                      - execution_log
                    pass_fail_rule: actual output matches approved expected output
                    status: ready_for_generation
                    linked_product_context:
                      - docs/00-intake-scope/01_project_scope_capability_baseline.md#e2e
                """.formatted(ownerText));

        AcIntakeReport report = new AcIntakeService().intake(acFile);

        assertThat(report.items()).hasSize(1);
        AcReadinessItem item = report.items().get(0);
        assertThat(item.acId()).isEqualTo("RP-AR-M1-data-pipeline-AC-001");
        assertThat(item.ownerAuthoredTitle()).isEqualTo(ownerText);
        assertThat(item.readiness()).isEqualTo("ready_for_generation");
        assertThat(item.classification()).isEqualTo("automatable");
        assertThat(item.linkedProductContext()).containsExactly(
                "docs/00-intake-scope/01_project_scope_capability_baseline.md#e2e");
        assertThat(item.gaps()).isEmpty();
        assertThat(item.ownerAuthoredTruthPreserved()).isTrue();
    }

    @Test
    void marksIncompleteAcNotReadyWithoutInventingBehavior() throws Exception {
        Path acFile = tempDir.resolve("acceptance_criteria.md");
        Files.writeString(acFile, """
                acceptance_criteria:
                  - ac_id: RP-AR-M1-data-pipeline-AC-002
                    rp_id: RP-AR-M1-data-pipeline
                    title: Missing behavior and rule
                    owner: product_owner
                    classification: automatable
                    input: fixture/input/orders.csv
                    expected_output: expected/output/orders_normalized.csv
                    status: ready_for_generation
                """);

        AcIntakeReport report = new AcIntakeService().intake(acFile);

        AcReadinessItem item = report.items().get(0);
        assertThat(item.readiness()).isEqualTo("not_ready_for_generation");
        assertThat(item.gaps()).extracting(AcReadinessGap::fieldPath)
                .contains("behavior", "pass_fail_rule");
        assertThat(item.gaps()).extracting(AcReadinessGap::ownerAction)
                .allMatch(action -> action.contains("Clarify owner-authored AC"));
        assertThat(item.inventedBehavior()).isFalse();
        assertThat(item.executableDraftAllowed()).isFalse();
    }

    @Test
    void returnsEmptyReportWhenAcceptanceCriteriaSectionIsMissingOrNotAList() throws Exception {
        Path scalarFile = tempDir.resolve("scalar-ac.md");
        Path malformedSectionFile = tempDir.resolve("malformed-ac.md");
        Files.writeString(scalarFile, "owner notes only\n");
        Files.writeString(malformedSectionFile, "acceptance_criteria: not-a-list\n");

        AcIntakeService service = new AcIntakeService();

        assertThat(service.intake(scalarFile).items()).isEmpty();
        assertThat(service.intake(malformedSectionFile).items()).isEmpty();
    }

    @Test
    void ignoresNonMappingAcceptanceCriteriaRows() throws Exception {
        Path acFile = tempDir.resolve("acceptance_criteria.md");
        Files.writeString(acFile, """
                acceptance_criteria:
                  - free-form reminder
                  - ac_id: RP-001-AC-001
                    rp_id: RP-001
                    title: Valid AC row
                    owner: product_owner
                    classification: automatable
                    input: fixture/input/orders.csv
                    behavior: transform valid orders
                    expected_output: expected/output/orders.csv
                    pass_fail_rule: output matches expectation
                    status: ready_for_generation
                """);

        AcIntakeReport report = new AcIntakeService().intake(acFile);

        assertThat(report.items()).hasSize(1);
        assertThat(report.items().get(0).acId()).isEqualTo("RP-001-AC-001");
    }

    @Test
    void preservesExplicitNotReadyStatusEvenWhenAllRequiredFieldsExist() throws Exception {
        Path acFile = tempDir.resolve("acceptance_criteria.md");
        Files.writeString(acFile, """
                acceptance_criteria:
                  - ac_id: RP-001-AC-002
                    rp_id: RP-001
                    title: Owner keeps AC gated
                    owner: product_owner
                    classification: automatable
                    input: fixture/input/orders.csv
                    behavior: transform valid orders
                    expected_output: expected/output/orders.csv
                    pass_fail_rule: output matches expectation
                    status: not_ready_for_generation
                """);

        AcReadinessItem item = new AcIntakeService().intake(acFile).items().get(0);

        assertThat(item.readiness()).isEqualTo("not_ready_for_generation");
        assertThat(item.gaps()).isEmpty();
        assertThat(item.executableDraftAllowed()).isFalse();
        assertThat(item.ownerAuthoredTruthPreserved()).isTrue();
    }

    @Test
    void treatsBlankRequiredFieldsAsReadinessGaps() throws Exception {
        Path acFile = tempDir.resolve("acceptance_criteria.md");
        Files.writeString(acFile, """
                acceptance_criteria:
                  - ac_id: RP-001-AC-003
                    rp_id: RP-001
                    title: Blank owner
                    owner: " "
                    classification: automatable
                    input: fixture/input/orders.csv
                    behavior: transform valid orders
                    expected_output: expected/output/orders.csv
                    pass_fail_rule: output matches expectation
                    status: ready_for_generation
                """);

        AcReadinessItem item = new AcIntakeService().intake(acFile).items().get(0);

        assertThat(item.readiness()).isEqualTo("not_ready_for_generation");
        assertThat(item.gaps()).extracting(AcReadinessGap::fieldPath).containsExactly("owner");
        assertThat(item.executableDraftAllowed()).isFalse();
    }

    @Test
    void throwsUncheckedIoExceptionWhenAcceptanceCriteriaFileCannotBeRead() {
        Path missingFile = tempDir.resolve("missing-acceptance-criteria.md");

        assertThatThrownBy(() -> new AcIntakeService().intake(missingFile))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to read acceptance criteria");
    }
}

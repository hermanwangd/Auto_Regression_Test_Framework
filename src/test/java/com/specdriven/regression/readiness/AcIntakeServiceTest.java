package com.specdriven.regression.readiness;

import static org.assertj.core.api.Assertions.assertThat;

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
}

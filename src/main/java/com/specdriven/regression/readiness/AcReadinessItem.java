package com.specdriven.regression.readiness;

import java.util.List;

public record AcReadinessItem(
        String acId,
        String rpId,
        String ownerAuthoredTitle,
        String classification,
        String readiness,
        List<String> linkedProductContext,
        List<AcReadinessGap> gaps,
        boolean ownerAuthoredTruthPreserved,
        boolean inventedBehavior,
        boolean executableDraftAllowed,
        String inputRef,
        String expectedOutputRef) {

    public static AcReadinessItem ready(
            String acId,
            String rpId,
            String ownerAuthoredTitle,
            String classification,
            List<String> linkedProductContext) {
        return new AcReadinessItem(
                acId,
                rpId,
                ownerAuthoredTitle,
                classification,
                "ready_for_generation",
                List.copyOf(linkedProductContext),
                List.of(),
                true,
                false,
                true,
                "",
                "");
    }

    public static AcReadinessItem readyWithRefs(
            String acId,
            String rpId,
            String ownerAuthoredTitle,
            String classification,
            List<String> linkedProductContext,
            String inputRef,
            String expectedOutputRef) {
        return new AcReadinessItem(
                acId,
                rpId,
                ownerAuthoredTitle,
                classification,
                "ready_for_generation",
                List.copyOf(linkedProductContext),
                List.of(),
                true,
                false,
                true,
                inputRef,
                expectedOutputRef);
    }

    public static AcReadinessItem notReady(
            String acId,
            String rpId,
            String ownerAuthoredTitle,
            String classification,
            List<AcReadinessGap> gaps) {
        return new AcReadinessItem(
                acId,
                rpId,
                ownerAuthoredTitle,
                classification,
                "not_ready_for_generation",
                List.of(),
                List.copyOf(gaps),
                true,
                false,
                false,
                "",
                "");
    }

    public static AcReadinessItem notReadyWithRefs(
            String acId,
            String rpId,
            String ownerAuthoredTitle,
            String classification,
            List<AcReadinessGap> gaps,
            String inputRef,
            String expectedOutputRef) {
        return new AcReadinessItem(
                acId,
                rpId,
                ownerAuthoredTitle,
                classification,
                "not_ready_for_generation",
                List.of(),
                List.copyOf(gaps),
                true,
                false,
                false,
                inputRef,
                expectedOutputRef);
    }
}

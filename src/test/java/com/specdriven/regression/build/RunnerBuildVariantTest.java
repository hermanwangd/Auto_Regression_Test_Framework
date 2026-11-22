package com.specdriven.regression.build;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RunnerBuildVariantTest {
    private RunnerBuildVariant.Descriptor parse(String input) {
        return RunnerBuildVariant.parse(input.getBytes(StandardCharsets.UTF_8));
    }
    @Test void explicitFullAndOmittedDescriptorsAreDistinct() {
        assertThat(parse("schema=runner-build.v1\nvariant=full\nibm_mq=available\n").ibmMqAvailable()).isTrue();
        var omitted = parse("schema=runner-build.v1\nvariant=no-ibm-mq\nibm_mq=unsupported\n");
        assertThat(omitted.valid()).isTrue(); assertThat(omitted.ibmMqAvailable()).isFalse();
    }
    @Test void malformedConflictingDuplicateAndOversizedDescriptorsFailClosed() {
        for (String bad : new String[]{"", "variant=full\n", "schema=runner-build.v2\nvariant=full\nibm_mq=available\n",
                "schema=runner-build.v1\nvariant=no-ibm-mq\nibm_mq=available\n",
                "schema=runner-build.v1\nvariant=full\nibm_mq=available\nibm_mq=unsupported\n",
                "schema=runner-build.v1\nvariant=full\nibm_mq=available\nextra=1\n", "x".repeat(4097)}) {
            var descriptor = parse(bad); assertThat(descriptor.valid()).isFalse();
            assertThat(descriptor.ibmMqAvailable()).isFalse();
        }
    }
    @Test void runtimeSystemPropertiesCannotEnableOrChangeThePackagedVariant() {
        var before = RunnerBuildVariant.describe(); String original = System.getProperty("runner.build.variant");
        try {
            System.setProperty("runner.build.variant", "full");
            System.setProperty("runner.build.ibm-mq.available", "true");
            assertThat(RunnerBuildVariant.describe()).isEqualTo(before);
        } finally {
            if (original == null) System.clearProperty("runner.build.variant"); else System.setProperty("runner.build.variant", original);
            System.clearProperty("runner.build.ibm-mq.available");
        }
    }
}

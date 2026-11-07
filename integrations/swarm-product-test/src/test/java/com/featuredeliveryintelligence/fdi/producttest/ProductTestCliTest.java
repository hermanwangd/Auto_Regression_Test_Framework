package com.featuredeliveryintelligence.fdi.producttest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductTestCliTest {
    @TempDir Path root;
    @Test void existingCommandFamiliesAreNotHandled() {
        assertThat(ProductTestCli.handles(new String[0])).isFalse();
        assertThat(ProductTestCli.handles(new String[]{"dev204-knowledge-consume"})).isFalse();
        assertThat(ProductTestCli.handles(new String[]{"--spring.main.web-application-type=none"})).isFalse();
    }
    @Test void malformedProductCommandFailsInsteadOfFallingThroughToSpring() {
        assertThatThrownBy(() -> ProductTestCli.handles(new String[]{"product-test", "shell", "--command", "anything"}))
                .hasMessageContaining("failed");
    }
    @Test void requestCannotSupplyExecutableOrArbitraryOptions() throws Exception {
        Path request = root.resolve("request.json"); Files.writeString(request, "{\"executable\":\"untrusted\"}");
        var errors = new ByteArrayOutputStream();
        int exit = ProductTestCli.run(new String[]{"product-test", "run", "--request", request.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors), Map.of(), getClass().getClassLoader());
        assertThat(exit).isEqualTo(2);
        assertThat(errors.toString()).contains("Unrecognized field");
    }
    @Test void duplicateRequestFieldFailsClosed() throws Exception {
        Path request = root.resolve("request.json"); Files.writeString(request, "{\"module_id\":\"product-test\",\"module_id\":\"other\"}");
        var errors = new ByteArrayOutputStream();
        int exit = ProductTestCli.run(new String[]{"product-test", "run", "--request", request.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors), Map.of(), getClass().getClassLoader());
        assertThat(exit).isEqualTo(2);
        assertThat(errors.toString()).contains("Duplicate field");
    }
    @Test void numericRevisionIsNotSilentlyCoercedToAString() throws Exception {
        Path request = root.resolve("request.json"); Files.writeString(request, "{\"execution_revision\":17}");
        var errors = new ByteArrayOutputStream();
        int exit = ProductTestCli.run(new String[]{"product-test", "run", "--request", request.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors), Map.of(), getClass().getClassLoader());
        assertThat(exit).isEqualTo(2);
        assertThat(errors.toString()).contains("Cannot coerce");
    }
}

package com.specdriven.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegressionApplicationTest {

    @TempDir
    Path tempDir;

    @Test
    void runCliDelegatesArgumentsToRegressionCommand() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = RegressionApplication.runCli(
                new String[] {"init-product-repo", "--root", tempDir.toString()},
                new PrintStream(output),
                new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(output.toString()).contains("status: pass");
        assertThat(Files.isDirectory(tempDir.resolve("docs/08-release"))).isTrue();
    }

    @Test
    void mainRunsCliWhenArgumentsAreProvided() {
        Path productRepo = tempDir.resolve("product-repo-from-main");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(output));
            RegressionApplication.main(new String[] {"init-product-repo", "--root", productRepo.toString()});
        } finally {
            System.setOut(originalOut);
        }

        assertThat(output.toString()).contains("status: pass");
        assertThat(Files.isDirectory(productRepo.resolve("docs/08-release"))).isTrue();
    }

    @Test
    void applicationCanBeConstructedBySpring() {
        assertThat(new RegressionApplication()).isNotNull();
    }
}

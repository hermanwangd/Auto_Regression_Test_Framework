package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class RegressionCommandV024BoundaryTest {

    @Test
    void generalHelpListsOnlyCanonicalSuiteModeCommands() {
        RegressionCommand command = command();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {"--help"}, print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(output.toString())
                .contains("validate --suite <suite_manifest>")
                .contains("run --suite <suite_manifest> --profile <profile>")
                .contains("run --suite <suite_manifest> --dry-run")
                .contains("report --result <result_json>")
                .contains("validate-evidence --result <result_json>")
                .doesNotContain("init-product-repo")
                .doesNotContain("check-readiness")
                .doesNotContain("init-rp")
                .doesNotContain("check-rp");
    }

    @Test
    void runHelpDocumentsDryRunWithoutNonCanonicalAliases() {
        RegressionCommand command = command();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {"run", "--help"}, print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(output.toString())
                .contains("regress run --suite <suite_manifest> --profile <profile>")
                .contains("regress run --suite <suite_manifest> --dry-run");
    }

    @Test
    void nonCanonicalCommandIsNotAFrameworkCommand() {
        RegressionCommand command = command();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {"project-run", "--suite", "samples/golden_e2e/suite_manifest.yaml"},
                print(new ByteArrayOutputStream()), print(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString())
                .contains("Unknown command: project-run")
                .doesNotContain("LEGACY_RP_MODE_DEPRECATED")
                .doesNotContain("run --suite")
                .doesNotContain("suite-mode");
    }

    private RegressionCommand command() {
        return new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
    }

    private PrintStream print(ByteArrayOutputStream stream) {
        return new PrintStream(stream);
    }
}

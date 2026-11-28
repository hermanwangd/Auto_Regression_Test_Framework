package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SuiteCommandBoundaryTest {
    private static final Path GOLDEN = Path.of("samples/00-getting-started/golden_e2e/suite_manifest.yaml").toAbsolutePath();
    private static final Path LEGACY = Path.of(
            "samples/90-compatibility/legacy-v0.2/00-getting-started/golden_e2e/suite_manifest.yaml").toAbsolutePath();

    @TempDir Path root;

    @Test
    void validateIdentifiesMissingManifestAndAllMissingGroupFields() throws Exception {
        Outcome absent = execute("validate", "--suite", "group.yaml", "--profile", "local");
        assertThat(absent.exit()).isEqualTo(1);
        assertThat(absent.stdout()).contains("validation_status: failed", "reason: missing_required_file", "group.yaml");

        Files.writeString(root.resolve("group.yaml"), "child_suites: []\n");
        Outcome group = execute("validate", "--suite", "group.yaml");
        assertThat(group.exit()).isEqualTo(1);
        assertThat(group.stdout()).contains("validation_status: failed", "test_count: 0",
                "field_path: suite_id", "field_path: profile", "field_path: child_suites",
                "List at least one child suite manifest.");
        assertThat(root.resolve("target/regression")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectingAnIndividualCaseNeverFallsBackToTheFullLegacySuite(boolean dryRun) {
        var options = new ArrayList<>(List.of("--suite", LEGACY.toString(), "--profile", "local_golden",
                "--test-case", "GOLDEN-E2E-TC-001"));
        if (dryRun) options.add("--dry-run");
        Outcome result = execute("run", options.toArray(String[]::new));

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).contains("run_status: blocked", "provider_runtime_invoked: false",
                "reason: unsupported_test_case_selection").doesNotContain("result_json:");
        assertThat(root.resolve("target/regression")).doesNotExist();
    }

    @Test
    void tagSelectionReturnsAUsageErrorBeforeExecutingALeaf() {
        Outcome result = execute("run", "--suite", GOLDEN.toString(), "--profile", "local_v03", "--tag", "fast");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.stderr()).contains("run --suite supports selection by --test-case on v0.3 leaf suites.");
        assertThat(root.resolve("target/regression")).doesNotExist();
    }

    @Test
    void missingProfileCannotBypassReviewedPolicyDiscovery() throws Exception {
        Files.writeString(root.resolve("suite.yaml"), """
                manifest_version: v0.3
                suite_id: PROFILE-CHECK
                env_profiles:
                  sit:
                    ref: env_profiles/sit.yaml
                """);

        Outcome result = execute("run", "--suite", "suite.yaml", "--profile", "sit");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).contains("run_status: blocked", "provider_runtime_invoked: false",
                "field_path: product_test", "reason: REVIEWED_HANDOFF_REQUIRED", "profile: sit",
                "cannot inspect suite product-test policy");
        assertThat(root.resolve("target/regression")).doesNotExist();
    }

    @Test
    void groupCannotExecuteAReviewedLeafAsAnOrdinaryChild() throws Exception {
        Path source = Path.of("samples/41-product-testing/reviewed_local");
        Path child = root.resolve("reviewed");
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) Files.copy(file, child.resolve(source.relativize(file)));
        }
        Files.writeString(root.resolve("group.yaml"), """
                suite_id: REVIEWED-GROUP
                profile: local_v03
                child_suites:
                  - id: reviewed
                    ref: reviewed/suite_manifest.yaml
                    profile: local_v03
                """);
        Outcome result = execute("run", "--suite", "group.yaml", "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).contains("failed_count: 1", "reason: REVIEWED_HANDOFF_REQUIRED",
                "Run a reviewed v0.3 leaf separately");
        var summary = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.path("suite_summary_json").toFile());
        assertThat(summary.at("/children/0/observed_status").asText()).isEqualTo("blocked");
        assertThat(summary.at("/children/0/result_json").asText()).isEmpty();
    }

    @Test
    void textReportRefusesToWriteDerivedCoverageIntoCanonicalEvidence() throws Exception {
        Outcome run = execute("run", "--suite", GOLDEN.toString(), "--profile", "local_v03");
        assertThat(run.exit()).as(run.stdout() + run.stderr()).isZero();
        Path canonical = run.path("result_json");
        byte[] original = Files.readAllBytes(canonical);

        Outcome report = execute("report", "--result", canonical.toString(), "--format", "text",
                "--coverage-dir", canonical.getParent().toString());
        assertThat(report.exit()).isEqualTo(1);
        assertThat(report.stderr()).contains("coverage output must be outside canonical run tree");
        assertThat(report.stdout()).doesNotContain("review_ready");
        assertThat(Files.readAllBytes(canonical)).containsExactly(original);
        assertThat(canonical.resolveSibling("coverage.json")).doesNotExist();
    }

    private Outcome execute(String command, String... options) {
        var args = new ArrayList<>(List.of(command, "--root", root.toString()));
        args.addAll(List.of(options));
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var cli = new RegressionCommand(new ProductRepoService(), new ReleasePackageService(), Map.of());
        int exit = cli.execute(args.toArray(String[]::new), new PrintStream(stdout), new PrintStream(stderr));
        return new Outcome(exit, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Outcome(int exit, String stdout, String stderr) {
        Path path(String field) {
            String prefix = field + ": ";
            return stdout.lines().filter(line -> line.startsWith(prefix))
                    .map(line -> Path.of(line.substring(prefix.length()))).findFirst().orElseThrow();
        }
    }
}

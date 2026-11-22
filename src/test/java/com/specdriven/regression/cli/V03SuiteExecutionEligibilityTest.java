package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class V03SuiteExecutionEligibilityTest {
    private static final Path SAMPLE = Path.of("samples/00-getting-started/golden_e2e");

    @TempDir
    Path root;

    static Stream<Arguments> lifecycleCases() {
        return Stream.of("draft_skeleton", "draft_executable", "needs_update", "retired")
                .flatMap(status -> Stream.of("validate", "dry-run", "run")
                        .map(mode -> Arguments.of(status, mode)));
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("lifecycleCases")
    void publicSuiteRejectsNonActiveCase(String status, String mode) throws IOException {
        Path manifest = copySuite("single", "status: " + status);
        assertBlocked(manifest, mode, "lifecycle_not_execution_eligible");
    }

    @ParameterizedTest
    @CsvSource({
            "'status: null', lifecycle_not_execution_eligible",
            "'status: \"  \"', lifecycle_not_execution_eligible",
            "'status: 12', lifecycle_not_execution_eligible",
            "'status: unknown', lifecycle_not_execution_eligible"
    })
    void explicitInvalidStatusFailsValidation(String yaml, String reason) throws IOException {
        assertBlocked(copySuite("single", yaml), "validate", reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"validate", "dry-run", "run"})
    void groupChecksEveryChildBeforeStartingTheFirst(String mode) throws IOException {
        copySuite("first", "status: active");
        copySuite("second", "status: needs_update");
        Path group = root.resolve("group.yaml");
        Files.writeString(group, """
                manifest_version: v0.3
                suite_id: ELIGIBILITY-GROUP
                profile: local_v03
                child_suites:
                  - id: first
                    ref: first/suite_manifest.yaml
                    profile: local_v03
                    expected_status: passed
                  - id: second
                    ref: second/suite_manifest.yaml
                    profile: local_v03
                    expected_status: passed
                """);
        assertBlocked(group, mode, "lifecycle_not_execution_eligible");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "status: active"})
    void currentAndExplicitActiveGoldenRemainRunnable(String statusLine) throws IOException {
        Path manifest = copySuite("single", statusLine);
        for (String mode : List.of("validate", "dry-run", "run")) {
            Result result = execute(manifest, mode);
            assertThat(result.exit()).as(result.output()).isZero();
            if (mode.equals("run")) {
                assertThat(result.output()).contains("run_status: passed", "provider_runtime_executed: true");
                assertThat(root.resolve("target/regression")).isDirectory();
            }
        }
    }

    private void assertBlocked(Path manifest, String mode, String reason) {
        Result result = execute(manifest, mode);
        assertThat(result.exit()).as(result.output()).isOne();
        assertThat(result.output())
                .contains(mode.equals("validate") ? "validation_status: failed" : "run_status: blocked")
                .contains("reason: " + reason)
                .doesNotContain("provider_runtime_executed: true", "result_json:", "evidence_dir:");
        assertThat(root.resolve("target")).doesNotExist();
    }

    private Path copySuite(String name, String statusLine) throws IOException {
        Path destination = root.resolve(name);
        try (Stream<Path> files = Files.walk(SAMPLE)) {
            for (Path source : files.toList()) {
                Path copy = destination.resolve(SAMPLE.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(source, copy);
                }
            }
        }
        Path testCase = destination.resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase);
        if (!statusLine.isEmpty()) {
            Files.writeString(testCase, statusLine + "\n" + yaml);
        }
        return destination.resolve("suite_manifest.yaml");
    }

    private Result execute(Path manifest, String mode) {
        List<String> args = new ArrayList<>(List.of(mode.equals("validate") ? "validate" : "run",
                "--root", root.toString(), "--suite", manifest.toString(), "--profile", "local_v03"));
        if (mode.equals("dry-run")) {
            args.add("--dry-run");
        }
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream print = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = command.execute(args.toArray(String[]::new), print, print);
            return new Result(exit, output.toString(StandardCharsets.UTF_8));
        }
    }

    private record Result(int exit, String output) {}
}

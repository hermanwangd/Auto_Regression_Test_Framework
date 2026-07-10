package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DslV03CommandTest {

    private static final Path V03_SUITE = Path.of("samples/v0_3_dsl/golden/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void v03ContractArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "schemas/test_case_dsl.v0.3.schema.yaml",
                "schemas/provider_contract.v0.3.schema.yaml",
                "schemas/env_profile.v0.3.schema.yaml",
                "schemas/suite_manifest.v0.3.schema.yaml",
                "samples/v0_3_dsl/golden/suite_manifest.yaml",
                "samples/v0_3_dsl/golden/env_profiles/local_v03.yaml",
                "samples/v0_3_dsl/golden/test_cases/golden_success.yaml",
                "samples/v0_3_dsl/golden/fixtures/input.json",
                "samples/v0_3_dsl/golden/fixtures/setup_fixture.yaml",
                "samples/v0_3_dsl/golden/fixtures/cleanup_fixture.yaml",
                "samples/v0_3_dsl/golden/expected_results/expected_output.json");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
    }

    @Test
    void validateV03SuiteResolvesSuiteTargetsWithoutProviderInstances() {
        CommandResult result = execute("validate", "--suite", V03_SUITE.toString(), "--profile", "local_v03");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: GOLDEN-E2E-v0.3")
                .contains("targets_used:")
                .contains("sample_runtime")
                .contains("provider_contracts_used:")
                .contains("sample_fake_provider.v0.3")
                .contains("provider_types_used:")
                .contains("sample_fake_provider")
                .doesNotContain("sample-fake-runtime");
    }

    @Test
    void dryRunV03SuiteEmitsResolvedPlanWithoutProviderRuntime() {
        CommandResult result = execute("run", "--suite", V03_SUITE.toString(), "--profile", "local_v03", "--dry-run");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("provider_runtime_invoked: false")
                .contains("run_status: dry_run_ready")
                .contains("suite_id: GOLDEN-E2E-v0.3")
                .contains("target: sample_runtime")
                .contains("provider_contract: sample_fake_provider.v0.3")
                .contains("provider_type: sample_fake_provider")
                .contains("profile: local_v03")
                .contains("runtime_mode: stub")
                .doesNotContain("provider_id:");
    }

    @Test
    void runV03GoldenSuiteProducesReportableResultWithoutProviderInstance() throws Exception {
        CommandResult run = execute("run", "--suite", V03_SUITE.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: GOLDEN-E2E-v0.3")
                .contains("provider_runtime_executed: true")
                .doesNotContain("sample-fake-runtime");
        Path resultJson = extractPath(run.stdout(), "result_json");
        assertThat(resultJson).isRegularFile();
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"dsl_version\": \"v0.3\"")
                .contains("\"target\": \"sample_runtime\"")
                .contains("\"provider_contract\": \"sample_fake_provider.v0.3\"")
                .doesNotContain("sample-fake-runtime");

        CommandResult report = execute("report", "--result", resultJson.toString());

        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("suite_id: GOLDEN-E2E-v0.3")
                .contains("status: passed")
                .doesNotContain("sample-fake-runtime");
    }

    @Test
    void runV03GoldenSuiteResolvesAssertionActualByDeclaredStepId() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase)
                .replace("id: produce_sample_output", "id: make_sample_output")
                .replace("step://produce_sample_output/actual_json.status", "step://make_sample_output/actual_json.status");
        Files.writeString(testCase, yaml);

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout()).contains("run_status: passed");
        String result = Files.readString(extractPath(run.stdout(), "result_json"));
        assertThat(result)
                .contains("\"id\": \"make_sample_output\"")
                .contains("\"status\": \"passed\"");
    }

    @Test
    void validateV03SuiteRejectsLegacyProviderIdField() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        Files.writeString(testCase, Files.readString(testCase) + "\nprovider_id: sample-fake-runtime\n");

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: provider_id")
                .contains("reason: prohibited_legacy_field")
                .contains("category: VALIDATION_ERROR");
    }

    @Test
    void validateV03SuiteRejectsAssertionSelfReference() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase)
                .replace("step://produce_sample_output/actual_json.status", "step://status_is_ok/result");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: verify.status_is_ok.assert.actual")
                .contains("reason: invalid_step_ref")
                .contains("Reference only prior steps");
    }

    @Test
    void validateV03SuiteRejectsNestedLegacyBindAsField() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase)
                .replace("      sample.input_ref: artifact://fixtures/input.json",
                        "      sample.input_ref:\n"
                                + "        ref: artifact://fixtures/input.json\n"
                                + "        bind_as: sample.input_ref");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: execute[0].with.sample.input_ref.bind_as")
                .contains("reason: prohibited_legacy_field");
    }

    @Test
    void validateV03SuiteRejectsUnknownVerifyType() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase).replace("type: assertion", "type: custom_check");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: verify.status_is_ok.type")
                .contains("reason: unsupported_verify_type");
    }

    @Test
    void validateV03SuiteRejectsDuplicateStepIds() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase)
                .replace("id: cleanup_sample_workspace", "id: produce_sample_output");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: cleanup.produce_sample_output.id")
                .contains("reason: duplicate_step_id");
    }

    private Path mutableV03Golden() throws IOException {
        Path source = Path.of("samples/v0_3_dsl/golden");
        Path target = tempDir.resolve("golden_v03");
        copyDirectory(source, target);
        return target.resolve("suite_manifest.yaml");
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, new PrintStream(stdout), new PrintStream(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private Path extractPath(String stdout, String key) {
        return stdout.lines()
                .filter(line -> line.startsWith(key + ": "))
                .map(line -> Path.of(line.substring((key + ": ").length()).trim()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing path for " + key + " in:\n" + stdout));
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}

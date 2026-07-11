package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DslV03NegativeSampleCommandTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("negativeSamples")
    void validateV03NegativeSamplesFailWithOwnerActionableReason(
            String name,
            Path suite,
            String expectedReason,
            String expectedFieldHint) {
        CommandResult validate = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(validate.exit()).as(name + "\n" + validate.stderr() + validate.stdout()).isEqualTo(1);
        assertThat(validate.stdout())
                .contains("validation_status: failed")
                .contains("reason: " + expectedReason)
                .contains("owner_action:");
        assertThat(validate.stdout()).contains(expectedFieldHint);
    }

    private static Stream<Arguments> negativeSamples() {
        return Stream.of(
                sample("unknown target", "target-resolution/unknown_target", "invalid_target_ref", "missing_target"),
                sample("missing Env_Profile target", "target-resolution/missing_env_profile_target", "missing_env_profile_provider_binding", "targets.common_verifier"),
                sample("missing provider contract", "target-resolution/missing_provider_contract", "missing_provider_contract", "missing_provider.v0.3"),
                sample("missing required binding", "bindings/missing_required_binding", "missing_required_binding_key", "bindings.dialect"),
                sample("unknown binding key", "bindings/unknown_binding_key", "unknown_binding_key", "unexpected_key"),
                sample("invalid runtime mode", "bindings/invalid_runtime_mode", "unsupported_runtime_mode", "runtime_mode"),
                sample("unsupported operation", "operations/unsupported_operation", "unsupported_operation", "not_supported"),
                sample("unsupported input", "operations/unsupported_input", "unsupported_input", "unsupported_input"),
                sample("invalid artifact ref", "refs/invalid_artifact_ref", "invalid_artifact_ref", "setup.invalid_artifact.with.expected_ref"),
                sample("symlink artifact escape", "refs/symlink_escape", "ref_outside_suite_root", "setup.escaped_symlink.with.expected_ref"),
                sample("forward step ref", "refs/forward_step_ref", "invalid_step_ref", "future_step"),
                sample("legacy data binding", "legacy-fields/data_binding", "prohibited_legacy_field", "data_binding"),
                sample("raw secret in DSL", "secrets/raw_secret_dsl", "raw_secret", "password"),
                sample("raw secret in Env_Profile", "secrets/raw_secret_env", "raw_secret", "connection.secret_ref"));
    }

    private static Arguments sample(
            String name,
            String relativeDirectory,
            String expectedReason,
            String expectedFieldHint) {
        return Arguments.of(
                name,
                Path.of("samples/80-negative").resolve(relativeDirectory).resolve("suite_manifest.yaml"),
                expectedReason,
                expectedFieldHint);
    }

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, new PrintStream(stdout), new PrintStream(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}

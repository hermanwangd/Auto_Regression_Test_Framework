package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DslV03NatsRuntimeCommandTest {

    private static final Path NATS_SUITE = Path.of("samples/20-provider-capability-p0/messaging/nats/suite_manifest.yaml");

    @Test
    void runV03NatsExecutesMockRuntimeAndValidatesEvidence() throws Exception {
        CommandResult validate = execute("validate", "--suite", NATS_SUITE.toString(), "--profile", "local_v03");

        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("nats.v0.3")
                .doesNotContain("provider_instances_used");

        CommandResult run = execute("run", "--suite", NATS_SUITE.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: NATS-v0.3")
                .contains("provider_runtime_executed: true")
                .contains("provider_type: nats");

        Path resultJson = extractPath(run.stdout(), "result_json");
        assertThat(resultJson).isRegularFile();
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"provider_contract\": \"nats.v0.3\"")
                .contains("\"operation\": \"nats_publish,event_published,event_payload_match\"")
                .contains("\"matched\": true")
                .doesNotContain("provider_instance");

        CommandResult evidence = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(evidence.exit()).as(evidence.stderr() + evidence.stdout()).isZero();
        assertThat(evidence.stdout())
                .contains("evidence_validation_status: passed")
                .contains("provider_type: nats")
                .contains("provider_id: event_bus");
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

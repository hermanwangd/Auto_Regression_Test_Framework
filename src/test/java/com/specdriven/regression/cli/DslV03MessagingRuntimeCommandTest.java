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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DslV03MessagingRuntimeCommandTest {

    private static final Path KAFKA_SUITE = Path.of("samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml");
    private static final Path IBM_MQ_SUITE = Path.of("samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml");
    private static final Path MIXED_SUITE =
            Path.of("samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void runV03KafkaExecutesMockRuntimeAndValidatesEvidence() throws Exception {
        Path resultJson = runAndAssert(KAFKA_SUITE, "KAFKA-v0.3", "kafka", "kafka.v0.3");
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"operation\": \"kafka_publish,kafka_payload_match\"")
                .contains("\"matched\": true");
    }

    @Test
    void runV03IbmMqExecutesMockRuntimeAndValidatesEvidence() throws Exception {
        Path resultJson = runAndAssert(IBM_MQ_SUITE, "IBM-MQ-v0.3", "ibm_mq", "ibm_mq.v0.3");
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"operation\": \"mq_put,mq_payload_match\"")
                .contains("\"matched\": true");
    }

    @Test
    void runV03KafkaAndIbmMqMixedSuiteExecutesBothProviderRuntimes() throws Exception {
        CommandResult run = execute("run", "--suite", MIXED_SUITE.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: KAFKA-IBM-MQ-MIXED-v0.3")
                .contains("provider_type: kafka")
                .contains("provider_type: ibm_mq");

        Path resultJson = extractPath(run.stdout(), "result_json");
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"provider_contract\": \"kafka.v0.3\"")
                .contains("\"provider_contract\": \"ibm_mq.v0.3\"")
                .contains("\"target\": \"order_events\"")
                .contains("\"target\": \"payment_queue\"")
                .doesNotContain("provider_instance");

        CommandResult report = execute("report", "--result", resultJson.toString());

        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("provider_results_count: 2")
                .contains("missing_evidence_count: 0");
    }

    @Test
    void missingExplicitExternalEnvBlocksWithoutLocalProfileFallback() throws Exception {
        Path suiteRoot = tempDir.resolve("missing-external-kafka-env");
        copyDirectory(KAFKA_SUITE.getParent(), suiteRoot);
        Path profile = suiteRoot.resolve("env_profiles/external_kafka.yaml");
        String missingEnv = "REGRESS_TEST_MISSING_"
                + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        Files.writeString(profile, Files.readString(profile).replace(
                "env://KAFKA_BOOTSTRAP_SERVERS", "env://" + missingEnv));

        CommandResult run = execute(
                "run",
                "--suite", suiteRoot.resolve("suite_manifest.yaml").toString(),
                "--profile", "external_kafka");

        assertThat(run.exit()).isNotZero();
        assertThat(run.stdout() + run.stderr())
                .contains("v03_plan_compilation_failed")
                .contains("missing_environment_value")
                .contains(missingEnv)
                .doesNotContain("profile: local_v03")
                .doesNotContain("runtime_mode: mock");
    }

    private Path runAndAssert(Path suite, String suiteId, String providerType, String providerContract) {
        CommandResult validate = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains(providerContract)
                .doesNotContain("provider_instances_used");

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: " + suiteId)
                .contains("provider_runtime_executed: true")
                .contains("provider_type: " + providerType);

        Path resultJson = extractPath(run.stdout(), "result_json");
        assertThat(resultJson).isRegularFile();
        String result;
        try {
            result = Files.readString(resultJson);
        } catch (Exception e) {
            throw new AssertionError("Failed to read result JSON " + resultJson, e);
        }
        assertThat(result)
                .contains("\"provider_contract\": \"" + providerContract + "\"")
                .doesNotContain("provider_instance");

        CommandResult evidence = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(evidence.exit()).as(evidence.stderr() + evidence.stdout()).isZero();
        assertThat(evidence.stdout())
                .contains("evidence_validation_status: passed")
                .contains("provider_type: " + providerType);
        return resultJson;
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

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}

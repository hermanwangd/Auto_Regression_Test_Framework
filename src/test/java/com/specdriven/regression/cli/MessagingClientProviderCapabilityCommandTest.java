package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagingClientProviderCapabilityCommandTest {

    private static final Path KAFKA_SUITE = Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml");
    private static final Path IBM_MQ_SUITE = Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml");
    private static final Path MIXED_MESSAGING_SUITE =
            Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void messagingClientSamplesAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/test_case.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/provider_instances/order_events.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/env_profiles/local_kafka.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/env_profiles/ci_kafka_external.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/fixtures/order_event.json",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/expected_results/order_event.json",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/ibm_mq/test_case.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/ibm_mq/provider_instances/payment_mq.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/ibm_mq/env_profiles/local_ibm_mq.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/ibm_mq/env_profiles/ci_ibm_mq_external.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/ibm_mq/fixtures/order_request.json",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/ibm_mq/expected_results/order_request.json",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/suite_manifest.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/kafka_test_case.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/ibm_mq_test_case.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/provider_instances/order_events.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/provider_instances/payment_mq.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/env_profiles/local_messaging.yaml");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
    }

    @Test
    void kafkaSuiteRunsThroughPublicCliAndReportConsumesResult() {
        CommandResult validate = execute("validate", "--suite", KAFKA_SUITE.toString());
        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: KAFKA-CAPABILITY-v0.2")
                .contains("provider_types_used:")
                .contains("  - kafka");

        CommandResult run = execute("run", "--suite", KAFKA_SUITE.toString(), "--profile", "local_kafka");
        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("provider_runtime_executed: true")
                .contains("provider_type: kafka")
                .contains("provider_id: order-events")
                .contains("topic: orders.created")
                .contains("evidence_classification: framework_provider_capability_only");

        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"provider_type\": \"kafka\"")
                .contains("\"topic\": \"orders.created\"")
                .contains("\"release_evidence_eligible\": false");
        assertThat(read(evidenceDir.resolve("evidence_index.yaml")))
                .contains("evidence_type: kafka_event")
                .contains("provider_type: kafka")
                .contains("provider_id: order-events");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("suite_id: KAFKA-CAPABILITY-v0.2")
                .contains("status: passed")
                .contains("provider_evidence_summary:")
                .contains("  - provider_type: kafka")
                .contains("    provider_id: order-events")
                .contains("    evidence_count:");
    }

    @Test
    void kafkaSuiteRunsMultipleTestCasesWithOneSharedProfile() throws Exception {
        Path suiteRoot = tempDir.resolve("multi_test_kafka_shared_profile");
        copyDirectory(Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka"), suiteRoot);
        Files.writeString(suiteRoot.resolve("second_test_case.yaml"), read(suiteRoot.resolve("test_case.yaml"))
                .replace("test_case_id: KAFKA-CAPABILITY-TC-001", "test_case_id: KAFKA-CAPABILITY-TC-002")
                .replace("title: Kafka client provider capability", "title: Kafka client provider capability second case")
                .replace("ORD-K-001", "ORD-K-002"));
        Files.writeString(suiteRoot.resolve("suite_manifest.yaml"), read(suiteRoot.resolve("suite_manifest.yaml"))
                .replace("tests:\n  - test_case.yaml", "tests:\n  - test_case.yaml\n  - second_test_case.yaml"));

        CommandResult run = execute("run", "--suite", suiteRoot.resolve("suite_manifest.yaml").toString(), "--profile", "local_kafka");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("test_count: 2")
                .contains("provider_runtime_executed: true")
                .contains("profile: local_kafka")
                .contains("provider_type: kafka");
        Path resultJson = extractPath(run.stdout(), "result_json");
        assertThat(read(resultJson))
                .contains("\"test_count\": 2")
                .contains("\"test_case_id\": \"KAFKA-CAPABILITY-TC-001\"")
                .contains("\"test_case_id\": \"KAFKA-CAPABILITY-TC-002\"")
                .contains("\"profile\": \"local_kafka\"");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("test_count: 2")
                .contains("pass_count: 2")
                .contains("fail_count: 0");
    }

    @Test
    void messagingSuiteRunsKafkaAndIbmMqTestCasesWithOneSharedProfile() throws Exception {
        Path suiteRoot = tempDir.resolve("mixed_messaging_shared_profile");
        Files.createDirectories(suiteRoot);
        copyDirectory(Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/provider_instances"), suiteRoot.resolve("provider_instances"));
        copyDirectory(Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/ibm_mq/provider_instances"), suiteRoot.resolve("provider_instances"));
        copyDirectory(Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/fixtures"), suiteRoot.resolve("fixtures"));
        copyDirectory(Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/ibm_mq/fixtures"), suiteRoot.resolve("fixtures"));
        copyDirectory(Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/expected_results"), suiteRoot.resolve("expected_results"));
        copyDirectory(Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/ibm_mq/expected_results"), suiteRoot.resolve("expected_results"));
        Files.createDirectories(suiteRoot.resolve("env_profiles"));
        Files.writeString(suiteRoot.resolve("env_profiles/local_messaging.yaml"), """
                env_profile_id: local_messaging
                execution_mode: local
                isolation_scope: per_run
                dependency_policy:
                  require_readiness_evidence: false
                  allow_framework_managed_dependencies: true
                dependency_substitution_policy:
                  allowed_runtime_modes: [mock]
                  mock_evidence_release_claim: prohibited
                dependency_provisioning_policy:
                  allowed_provisioners: [in_memory]
                data_policy:
                  approved_expected_results_required: true
                  production_data_allowed: false
                  generated_data_allowed: true
                  secrets_must_use_refs: true
                providers:
                  order-events:
                    runtime_mode: mock
                    bindings:
                      bootstrap_servers:
                        value: approved_local_kafka_ref
                      topic:
                        value: orders.created
                      consumer_group:
                        value: artf-local-kafka
                      timeout:
                        value: PT5S
                      poll_interval:
                        value: PT0.05S
                  payment-mq:
                    runtime_mode: mock
                    bindings:
                      queue_manager:
                        value: QM1
                      channel:
                        value: DEV.APP.SVRCONN
                      conn_name:
                        value: localhost(1414)
                      queue:
                        value: PAYMENT.REQUEST.LOCAL
                      credential:
                        secret_ref: secret://local/ibm-mq
                      timeout:
                        value: PT5S
                      poll_interval:
                        value: PT0.05S
                """);
        Files.writeString(suiteRoot.resolve("kafka_test_case.yaml"), read(Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/test_case.yaml"))
                .replace("compatible_profiles: [local_kafka, ci_kafka_external]", "compatible_profiles: [local_messaging]"));
        Files.writeString(suiteRoot.resolve("ibm_mq_test_case.yaml"), read(Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/ibm_mq/test_case.yaml"))
                .replace("compatible_profiles: [local_ibm_mq, ci_ibm_mq_external]", "compatible_profiles: [local_messaging]"));
        Files.writeString(suiteRoot.resolve("suite_manifest.yaml"), """
                contract_version: v0.2
                suite_id: MIXED-MESSAGING-CAPABILITY-v0.2
                purpose: Framework-owned mixed Kafka and IBM MQ client provider capability verification sample.
                profile: local_messaging
                selection:
                  mode: suite
                  suite: provider-capability-mixed-messaging
                tests:
                  - kafka_test_case.yaml
                  - ibm_mq_test_case.yaml
                artifact_roots:
                  provider_instances: provider_instances/
                  env_profiles: env_profiles/
                  expected_results: expected_results/
                  fixtures: fixtures/
                evidence_policy:
                  evidence_classification: framework_provider_capability_only
                  downstream_release_evidence: false
                """);

        CommandResult run = execute("run", "--suite", suiteRoot.resolve("suite_manifest.yaml").toString(), "--profile", "local_messaging");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("test_count: 2")
                .contains("provider_runtime_executed: true")
                .contains("profile: local_messaging")
                .contains("provider_summary:")
                .contains("  - provider_type: kafka")
                .contains("    provider_id: order-events")
                .contains("  - provider_type: ibm_mq")
                .contains("    provider_id: payment-mq")
                .doesNotContain("\nprovider_type: kafka\nprovider_id:");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        String resultJsonContent = read(resultJson);
        assertThat(resultJsonContent)
                .contains("\"test_count\": 2")
                .contains("\"provider_summary\"")
                .contains("\"provider_type\": \"kafka\"")
                .contains("\"provider_type\": \"ibm_mq\"")
                .contains("\"capabilities\": [\"kafka\", \"ibm_mq\"]")
                .contains("\"test_case_id\": \"KAFKA-CAPABILITY-TC-001\"")
                .contains("\"test_case_id\": \"IBM-MQ-CAPABILITY-TC-001\"");
        assertThat(rootFieldsBeforeProviderSummary(resultJsonContent))
                .doesNotContain("\"provider_type\"")
                .doesNotContain("\"provider_id\"")
                .doesNotContain("\"topic\"")
                .doesNotContain("\"queue\"");
        assertThat(providerEvidenceRefsSection(resultJsonContent))
                .contains("provider-evidence/kafka/")
                .contains("provider-evidence/ibm_mq/")
                .doesNotContain("evidence_index.yaml")
                .doesNotContain("logs/")
                .doesNotContain("batch/")
                .doesNotContain("assertions/");
        assertThat(read(evidenceDir.resolve("evidence_index.yaml")))
                .contains("evidence_type: kafka_event")
                .contains("evidence_type: ibm_mq_event")
                .contains("test_case_id: KAFKA-CAPABILITY-TC-001")
                .contains("test_case_id: IBM-MQ-CAPABILITY-TC-001");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("test_count: 2")
                .contains("pass_count: 2")
                .contains("fail_count: 0")
                .contains("  - provider_type: ibm_mq")
                .contains("    provider_id: payment-mq")
                .contains("  - provider_type: kafka")
                .contains("    provider_id: order-events");
    }

    @Test
    void mixedMessagingSampleRunsThroughPublicCliAndReportConsumesResult() {
        CommandResult validate = execute("validate", "--suite", MIXED_MESSAGING_SUITE.toString());
        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: MIXED-MESSAGING-CAPABILITY-v0.2")
                .contains("  - ibm_mq")
                .contains("  - kafka");

        CommandResult run = execute("run", "--suite", MIXED_MESSAGING_SUITE.toString(), "--profile", "local_messaging");
        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("test_count: 2")
                .contains("provider_runtime_executed: true")
                .contains("provider_summary:")
                .contains("  - provider_type: kafka")
                .contains("    provider_id: order-events")
                .contains("  - provider_type: ibm_mq")
                .contains("    provider_id: payment-mq")
                .doesNotContain("\nprovider_type: kafka\nprovider_id:");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        String resultJsonContent = read(resultJson);
        assertThat(providerEvidenceRefsSection(resultJsonContent))
                .contains("provider-evidence/kafka/")
                .contains("provider-evidence/ibm_mq/")
                .doesNotContain("evidence_index.yaml")
                .doesNotContain("logs/")
                .doesNotContain("batch/")
                .doesNotContain("assertions/");
        assertThat(read(evidenceDir.resolve("evidence_index.yaml")))
                .contains("evidence_type: kafka_event")
                .contains("evidence_type: ibm_mq_event")
                .contains("test_case_id: KAFKA-CAPABILITY-TC-001")
                .contains("test_case_id: IBM-MQ-CAPABILITY-TC-001");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("test_count: 2")
                .contains("pass_count: 2")
                .contains("fail_count: 0")
                .contains("provider_evidence_summary:")
                .contains("  - provider_type: ibm_mq")
                .contains("  - provider_type: kafka");
    }

    @Test
    void ibmMqSuiteRunsThroughPublicCliAndReportConsumesResult() {
        CommandResult validate = execute("validate", "--suite", IBM_MQ_SUITE.toString());
        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: IBM-MQ-CAPABILITY-v0.2")
                .contains("provider_types_used:")
                .contains("  - ibm_mq");

        CommandResult run = execute("run", "--suite", IBM_MQ_SUITE.toString(), "--profile", "local_ibm_mq");
        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("provider_runtime_executed: true")
                .contains("provider_type: ibm_mq")
                .contains("provider_id: payment-mq")
                .contains("queue: PAYMENT.REQUEST.LOCAL")
                .contains("evidence_classification: framework_provider_capability_only");

        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"provider_type\": \"ibm_mq\"")
                .contains("\"queue\": \"PAYMENT.REQUEST.LOCAL\"")
                .contains("\"correlation_id\": \"CORR-")
                .doesNotContain("CORR-MQ-001")
                .contains("\"release_evidence_eligible\": false");
        assertThat(read(evidenceDir.resolve("evidence_index.yaml")))
                .contains("evidence_type: ibm_mq_event")
                .contains("provider_type: ibm_mq")
                .contains("provider_id: payment-mq");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("suite_id: IBM-MQ-CAPABILITY-v0.2")
                .contains("status: passed")
                .contains("provider_evidence_summary:")
                .contains("  - provider_type: ibm_mq")
                .contains("    provider_id: payment-mq")
                .contains("    evidence_count:");
    }

    @Test
    void messagingSuiteWithMultipleRuntimeTargetsInOneTestIsBlockedUntilAggregationExists() throws Exception {
        Path suiteRoot = tempDir.resolve("multi_test_kafka");
        copyDirectory(Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka"), suiteRoot);
        Files.writeString(suiteRoot.resolve("test_case.yaml"), read(suiteRoot.resolve("test_case.yaml"))
                .replace("""
                targets:
                  event_bus:
                    provider_id: order-events
                """, """
                targets:
                  event_bus:
                    provider_id: order-events
                  secondary_event_bus:
                    provider_id: order-events
                """)
                .replace("""
                    - id: kafka_payload_match
                      type: kafka_payload_match
                      target: event_bus
                """, """
                    - id: kafka_payload_match
                      type: kafka_payload_match
                      target: event_bus
                    - id: kafka_secondary_payload_match
                      type: kafka_payload_match
                      target: secondary_event_bus
                      inputs:
                        expected_ref:
                          ref: ${data.order_expected}
                        consume_from:
                          value: test_start_time
                        timeout:
                          value: PT0.1S
                        poll_interval:
                          value: PT0.05S
                """));

        CommandResult run = execute("run", "--suite", suiteRoot.resolve("suite_manifest.yaml").toString(), "--profile", "local_kafka");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: blocked")
                .contains("reason: unsupported_messaging_suite_shape")
                .contains("exactly one runtime target per test case");
    }

    @Test
    void resolverBlockedOperationDoesNotClaimProviderRuntimeExecuted() throws Exception {
        Path suiteRoot = tempDir.resolve("resolver_blocked_kafka");
        copyDirectory(Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka"), suiteRoot);
        Files.writeString(suiteRoot.resolve("test_case.yaml"), read(suiteRoot.resolve("test_case.yaml"))
                .replace("""
                      inputs:
                        key:
                          value: ORD-K-001
                        payload_ref:
                          ref: ${data.order_event}
                """, """
                      inputs: {}
                """));

        CommandResult run = execute("run", "--suite", suiteRoot.resolve("suite_manifest.yaml").toString(), "--profile", "local_kafka");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: failed")
                .contains("provider_runtime_executed: false")
                .contains("provider_type: kafka");
    }

    @Test
    void kafkaNativeExternalProfileValidatesWithoutRuntimeDispatch() {
        CommandResult validate = execute("validate", "--suite", KAFKA_SUITE.toString(), "--profile", "ci_kafka_external");

        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: KAFKA-CAPABILITY-v0.2")
                .contains("provider_types_used:")
                .contains("  - kafka");
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
                .filter(line -> line.startsWith(key + ":"))
                .map(line -> Path.of(line.substring((key + ":").length()).trim()))
                .findFirst()
                .orElseThrow();
    }

    private String rootFieldsBeforeProviderSummary(String json) {
        int providerSummaryIndex = json.indexOf("\"provider_summary\"");
        assertThat(providerSummaryIndex).as("provider_summary must exist in result JSON").isGreaterThan(0);
        return json.substring(0, providerSummaryIndex);
    }

    private String providerEvidenceRefsSection(String json) {
        int refsStart = json.indexOf("\"provider_evidence_refs\"");
        int evidenceRefsStart = json.indexOf("\"evidence_refs\"", refsStart);
        assertThat(refsStart).as("provider_evidence_refs must exist in result JSON").isGreaterThan(0);
        assertThat(evidenceRefsStart).as("evidence_refs must follow provider_evidence_refs").isGreaterThan(refsStart);
        return json.substring(refsStart, evidenceRefsStart);
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
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

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}

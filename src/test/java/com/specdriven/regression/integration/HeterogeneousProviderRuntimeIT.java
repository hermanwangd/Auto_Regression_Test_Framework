package com.specdriven.regression.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.cli.RegressionCommand;
import com.specdriven.regression.cli.RegressionCommandTestSupport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeterogeneousProviderRuntimeIT {

    private static final String RP_ID = "RP-HETEROGENEOUS-RUNTIME";

    @TempDir
    Path tempDir;

    @Test
    void compatibilityRunExecutesHeterogeneousProviderRuntimeBatchWithFrameworkEvidence() throws Exception {
        Path packageRoot = createHeterogeneousRuntimeFixture();
        RegressionCommand command = RegressionCommandTestSupport.legacyRpModeCommand();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", RP_ID, "--env", "ci_ephemeral"},
                print(stdout), print(new ByteArrayOutputStream()));

        assertThat(exit).as(stdout.toString()).isZero();
        assertThat(stdout.toString())
                .contains("provider_runtime_started: true")
                .contains("batch_id: BATCH-001")
                .contains("run_status: passed");
        assertThat(Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml")))
                .contains("status: passed")
                .contains("run_id: RUN-001")
                .contains("run_id: RUN-012");
        assertRunPassed(packageRoot, "RP-HET-TC-DB", "provider_contract_kind: db_fixture", "fixture_setup.yaml",
                "cleanup.yaml");
        assertRunPassed(packageRoot, "RP-HET-TC-REST", "provider_contract_kind: request_response", "request_response.yaml");
        assertRunPassed(packageRoot, "RP-HET-TC-GRPC", "provider_contract_kind: request_response", "provider_type: grpc");
        assertRunPassed(packageRoot, "RP-HET-TC-KAFKA-PUBLISH", "provider_contract_kind: messaging", "provider_type: kafka",
                "mode: publish");
        assertRunPassed(packageRoot, "RP-HET-TC-KAFKA-OBSERVE", "provider_contract_kind: messaging", "provider_type: kafka",
                "mode: observe");
        assertRunPassed(packageRoot, "RP-HET-TC-KAFKA-CLEANUP", "provider_contract_kind: messaging", "provider_type: kafka",
                "mode: cleanup", "cleanup_strategy: drain");
        assertRunPassed(packageRoot, "RP-HET-TC-NATS-REQUEST", "provider_contract_kind: messaging", "provider_type: nats",
                "mode: request_reply");
        assertRunPassed(packageRoot, "RP-HET-TC-NATS-OBSERVE", "provider_contract_kind: messaging", "provider_type: nats",
                "mode: observe");
        assertRunPassed(packageRoot, "RP-HET-TC-NATS-CLEANUP", "provider_contract_kind: messaging", "provider_type: nats",
                "mode: cleanup", "cleanup_strategy: drain");
        assertRunPassed(packageRoot, "RP-HET-TC-K8S", "provider_contract_kind: deployment_readiness", "provider_type: k8s");
        assertRunPassed(packageRoot, "RP-HET-TC-VM", "provider_contract_kind: deployment_readiness", "provider_type: vm");
        assertRunPassed(packageRoot, "RP-HET-TC-RUNNER", "provider_contract_kind: external_runner", "external_runner.yaml");
        assertThat(countOrders("jdbc:h2:mem:heterogeneous_runtime;DB_CLOSE_DELAY=-1")).isZero();
    }

    private Path createHeterogeneousRuntimeFixture() throws Exception {
        RegressionCommand command = RegressionCommandTestSupport.legacyRpModeCommand();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", RP_ID, "--package-type", "framework_verification"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        Path packageRoot = tempDir.resolve("docs/08-release/release-packages").resolve(RP_ID);
        writeCommonFixtureFiles(packageRoot);
        writeGeneratedRuntimeArtifacts(packageRoot);
        writeApprovedTests(packageRoot);
        return packageRoot;
    }

    private void writeCommonFixtureFiles(Path packageRoot) throws Exception {
        Files.createDirectories(packageRoot.resolve("fixtures/api"));
        Files.createDirectories(packageRoot.resolve("fixtures/events"));
        Files.createDirectories(packageRoot.resolve("fixtures/db"));
        Files.createDirectories(packageRoot.resolve("fixtures/readiness"));
        Files.createDirectories(packageRoot.resolve("expected/output"));
        Files.createDirectories(packageRoot.resolve("descriptors"));
        Files.writeString(packageRoot.resolve("FRAMEWORK_VERIFICATION_FIXTURE.md"),
                "This fixture is framework verification evidence, not downstream RP release evidence.\n");
        Files.writeString(packageRoot.resolve("fixtures/api/payment_payload.json"),
                "{\"paymentId\":\"P-100\",\"amount\":42}");
        Files.writeString(packageRoot.resolve("fixtures/events/payment_event.json"),
                "{\"event\":\"payment.accepted\",\"paymentId\":\"P-100\"}");
        Files.writeString(packageRoot.resolve("fixtures/db/seed_orders.sql"), """
                CREATE TABLE IF NOT EXISTS orders (
                  id VARCHAR(64) PRIMARY KEY,
                  status VARCHAR(32)
                );
                MERGE INTO orders KEY(id) VALUES ('ORDER-100', 'READY');
                """);
        Files.writeString(packageRoot.resolve("fixtures/db/cleanup_orders.sql"),
                "DELETE FROM orders WHERE id = 'ORDER-100';\n");
        Files.writeString(packageRoot.resolve("fixtures/db/count_orders.sql"),
                "SELECT COUNT(*) FROM orders\n");
        Files.writeString(packageRoot.resolve("fixtures/readiness/k8s.ready"), "ready\n");
        Files.writeString(packageRoot.resolve("fixtures/readiness/vm.ready"), "ready\n");
        Files.writeString(packageRoot.resolve("descriptors/payment.desc"), "mock descriptor");
        writeExpected(packageRoot, "RP-HET-AC-DB", "db-fixture-ok");
        writeExpected(packageRoot, "RP-HET-AC-REST", "{\"status\":\"approved\",\"channel\":\"rest\"}");
        writeExpected(packageRoot, "RP-HET-AC-GRPC", "{\"status\":\"approved\",\"channel\":\"grpc\"}");
        writeExpected(packageRoot, "RP-HET-AC-KAFKA-PUBLISH", "{\"event\":\"payment.accepted\",\"paymentId\":\"P-100\"}");
        writeExpected(packageRoot, "RP-HET-AC-KAFKA-OBSERVE", "{\"event\":\"payment.observed\",\"paymentId\":\"P-100\"}");
        writeExpected(packageRoot, "RP-HET-AC-KAFKA-CLEANUP", "");
        writeExpected(packageRoot, "RP-HET-AC-NATS-REQUEST", "{\"reply\":\"accepted\",\"paymentId\":\"P-100\"}");
        writeExpected(packageRoot, "RP-HET-AC-NATS-OBSERVE", "{\"event\":\"nats.observed\",\"paymentId\":\"P-100\"}");
        writeExpected(packageRoot, "RP-HET-AC-NATS-CLEANUP", "");
        writeExpected(packageRoot, "RP-HET-AC-K8S", "ready\n");
        writeExpected(packageRoot, "RP-HET-AC-VM", "ready\n");
        writeExpected(packageRoot, "RP-HET-AC-RUNNER", "legacy-ok");
    }

    private void writeGeneratedRuntimeArtifacts(Path packageRoot) throws Exception {
        Path generated = packageRoot.resolve("generated-framework");
        Files.createDirectories(generated.resolve("run_profiles"));
        Files.createDirectories(generated.resolve("environment_bindings"));
        Files.createDirectories(generated.resolve("provider_contracts"));
        Files.writeString(generated.resolve("suite_manifest.yaml"), """
                suite_id: heterogeneous-runtime
                tests:
                  - tests/approved/RP-HET-TC-DB.yaml
                  - tests/approved/RP-HET-TC-REST.yaml
                  - tests/approved/RP-HET-TC-GRPC.yaml
                  - tests/approved/RP-HET-TC-KAFKA-PUBLISH.yaml
                  - tests/approved/RP-HET-TC-KAFKA-OBSERVE.yaml
                  - tests/approved/RP-HET-TC-KAFKA-CLEANUP.yaml
                  - tests/approved/RP-HET-TC-NATS-REQUEST.yaml
                  - tests/approved/RP-HET-TC-NATS-OBSERVE.yaml
                  - tests/approved/RP-HET-TC-NATS-CLEANUP.yaml
                  - tests/approved/RP-HET-TC-K8S.yaml
                  - tests/approved/RP-HET-TC-VM.yaml
                  - tests/approved/RP-HET-TC-RUNNER.yaml
                coverage_source_ref: acceptance_criteria.md
                traceability_map_ref: traceability_map.yaml
                """);
        Files.writeString(generated.resolve("run_plan.yaml"), """
                run_profile_ref: run_profiles/ci_ephemeral.yaml
                environment_binding_ref: environment_bindings/ci_ephemeral.yaml
                execution_mode: ci_ephemeral
                target_dependencies:
                  RU-payment-db: []
                  RU-payment-rest:
                    - target_id: RU-payment-db
                  RU-payment-grpc:
                    - target_id: RU-payment-db
                  RU-payment-kafka-publish:
                    - target_id: RU-payment-rest
                  RU-payment-kafka-observe:
                    - target_id: RU-payment-kafka-publish
                  RU-payment-kafka-cleanup:
                    - target_id: RU-payment-kafka-observe
                  RU-payment-nats-request:
                    - target_id: RU-payment-grpc
                  RU-payment-nats-observe:
                    - target_id: RU-payment-nats-request
                  RU-payment-nats-cleanup:
                    - target_id: RU-payment-nats-observe
                  RU-payment-k8s: []
                  RU-payment-vm: []
                  RU-legacy-runner:
                    - target_id: RU-payment-kafka-cleanup
                    - target_id: RU-payment-nats-cleanup
                runtime:
                  timeout: PT5M
                """);
        Files.writeString(generated.resolve("run_profiles/ci_ephemeral.yaml"), """
                profile_id: ci_ephemeral
                execution_mode: ci_ephemeral
                environment_binding_ref: environment_bindings/ci_ephemeral.yaml
                isolation_scope: target_graph
                dependency_policy: generated_target_graph
                max_duration: PT5M
                data_policy:
                  cleanup_required: true
                """);
        Files.writeString(generated.resolve("environment_bindings/ci_ephemeral.yaml"), environmentBindingYaml());
        Files.writeString(generated.resolve("traceability_map.yaml"), "source_labels: {}\n");
        writeProviderContract(generated, "RU-payment-db", providerContractDb());
        writeProviderContract(generated, "RU-payment-rest", providerContractRest());
        writeProviderContract(generated, "RU-payment-grpc", providerContractGrpc());
        writeProviderContract(generated, "RU-payment-kafka-publish", providerContractKafkaPublish());
        writeProviderContract(generated, "RU-payment-kafka-observe", providerContractKafkaObserve());
        writeProviderContract(generated, "RU-payment-kafka-cleanup", providerContractKafkaCleanup());
        writeProviderContract(generated, "RU-payment-nats-request", providerContractNatsRequest());
        writeProviderContract(generated, "RU-payment-nats-observe", providerContractNatsObserve());
        writeProviderContract(generated, "RU-payment-nats-cleanup", providerContractNatsCleanup());
        writeProviderContract(generated, "RU-payment-k8s", providerContractK8s());
        writeProviderContract(generated, "RU-payment-vm", providerContractVm());
        writeProviderContract(generated, "RU-legacy-runner", providerContractExternalRunner());
    }

    private String environmentBindingYaml() {
        StringBuilder builder = new StringBuilder("""
                environment_id: ci_ephemeral
                environment_type: isolated
                targets:
                """);
        appendTarget(builder, "RU-payment-db", "spring_boot_cli");
        appendTarget(builder, "RU-payment-rest", "request_response");
        appendTarget(builder, "RU-payment-grpc", "grpc_request_response");
        appendTarget(builder, "RU-payment-kafka-publish", "kafka_publish_bus");
        appendTarget(builder, "RU-payment-kafka-observe", "kafka_observe_bus");
        appendTarget(builder, "RU-payment-kafka-cleanup", "kafka_cleanup_bus");
        appendTarget(builder, "RU-payment-nats-request", "nats_request_bus");
        appendTarget(builder, "RU-payment-nats-observe", "nats_observe_bus");
        appendTarget(builder, "RU-payment-nats-cleanup", "nats_cleanup_bus");
        appendTarget(builder, "RU-payment-k8s", "k8s_readiness");
        appendTarget(builder, "RU-payment-vm", "vm_readiness");
        appendTarget(builder, "RU-legacy-runner", "external_runner");
        return builder.toString();
    }

    private void appendTarget(StringBuilder builder, String targetId, String runner) {
        builder.append("  ").append(targetId).append(":\n");
        builder.append("    target_id: ").append(targetId).append("\n");
        builder.append("    runner: ").append(runner).append("\n");
        builder.append("    execution_mode: ci_ephemeral\n");
        builder.append("    environment_ref: ci://framework-verification/").append(targetId).append("\n");
        builder.append("    provider_contract_ref: provider_contracts/")
                .append(targetId)
                .append(".yaml#providers.")
                .append(runner)
                .append("\n");
    }

    private String providerContractDb() {
        return """
                provider_contracts:
                  providers:
                    spring_boot_cli:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      command: printf 'db-fixture-ok'
                      timeout_seconds: 10
                      success_exit_codes: [0]
                      logs:
                        stdout: logs/db-stdout.log
                        stderr: logs/db-stderr.log
                      outputs:
                        actual_output_ref: actual/db-output.txt
                      contract_path: release_units[0].provider_contracts.providers.spring_boot_cli
                  bindings:
                    db_seed:
                      provider_contract_kind: file_batch
                      provider_type: file_fixture
                      bind_as: jdbc_seed
                      contract_path: release_units[0].provider_contracts.bindings.db_seed
                  fixtures:
                    relational_db:
                      provider_contract_kind: db_fixture
                      provider_type: jdbc
                      connection_ref: jdbc:h2:mem:heterogeneous_runtime;DB_CLOSE_DELAY=-1
                      isolation_key: test_run_id
                      cleanup_strategy: by_test_run_id
                      setup_actions:
                        seed_orders:
                          sql_ref: fixtures/db/seed_orders.sql
                      cleanup_actions:
                        cleanup_orders:
                          sql_ref: fixtures/db/cleanup_orders.sql
                      verification_queries:
                        seeded_orders:
                          sql_ref: fixtures/db/count_orders.sql
                      contract_path: release_units[0].provider_contracts.fixtures.relational_db
                """;
    }

    private String providerContractRest() {
        return """
                provider_contracts:
                  providers:
                    request_response:
                      provider_contract_kind: request_response
                      provider_type: rest
                      endpoint_ref: mock://payment-api
                      timeout_seconds: 10
                      actions:
                        call_api:
                          method: POST
                          path: /payments
                          request_binding: payment_payload
                          mock_response: '{"status":"approved","channel":"rest"}'
                      outputs:
                        actual_output_ref: actual/rest-response.json
                      contract_path: release_units[1].provider_contracts.providers.request_response
                  bindings:
                    api_payload:
                      provider_contract_kind: request_response
                      provider_type: request_body
                      bind_as: request_body
                      contract_path: release_units[1].provider_contracts.bindings.api_payload
                """;
    }

    private String providerContractGrpc() {
        return """
                provider_contracts:
                  providers:
                    grpc_request_response:
                      provider_contract_kind: request_response
                      provider_type: grpc
                      service_ref: mock://payment-grpc
                      descriptor_ref: descriptors/payment.desc
                      timeout_seconds: 10
                      actions:
                        call_api:
                          service: payment.PaymentService
                          method: SubmitPayment
                          request_binding: payment_payload
                          mock_response: '{"status":"approved","channel":"grpc"}'
                      outputs:
                        actual_output_ref: actual/grpc-response.json
                      contract_path: release_units[2].provider_contracts.providers.grpc_request_response
                  bindings:
                    api_payload:
                      provider_contract_kind: request_response
                      provider_type: request_body
                      bind_as: request_body
                      contract_path: release_units[2].provider_contracts.bindings.api_payload
                """;
    }

    private String providerContractKafkaPublish() {
        return """
                provider_contracts:
                  providers:
                    kafka_publish_bus:
                      provider_contract_kind: messaging
                      provider_type: kafka
                      bootstrap_servers_ref: mock://kafka
                      topic_ref: kafka://payments.accepted
                      timeout_seconds: 10
                      actions:
                        publish_message:
                          mode: publish
                          payload_binding: payment_event
                          serialization: json
                          correlation_id: P-100
                      outputs:
                        actual_output_ref: actual/kafka-message.json
                      contract_path: release_units[3].provider_contracts.providers.kafka_publish_bus
                  bindings:
                    message_event:
                      provider_contract_kind: messaging
                      provider_type: event_payload
                      bind_as: event_payload
                      contract_path: release_units[3].provider_contracts.bindings.message_event
                """;
    }

    private String providerContractKafkaObserve() {
        return """
                provider_contracts:
                  providers:
                    kafka_observe_bus:
                      provider_contract_kind: messaging
                      provider_type: kafka
                      bootstrap_servers_ref: mock://kafka
                      topic_ref: kafka://payments.accepted
                      timeout_seconds: 10
                      actions:
                        consume_message:
                          mode: observe
                          correlation_id: P-100
                          expected_count: 1
                          observed_payload: '{"event":"payment.observed","paymentId":"P-100"}'
                      outputs:
                        actual_output_ref: actual/kafka-observed.json
                      contract_path: release_units[4].provider_contracts.providers.kafka_observe_bus
                """;
    }

    private String providerContractKafkaCleanup() {
        return """
                provider_contracts:
                  providers:
                    kafka_cleanup_bus:
                      provider_contract_kind: messaging
                      provider_type: kafka
                      bootstrap_servers_ref: mock://kafka
                      topic_ref: kafka://payments.accepted
                      timeout_seconds: 10
                      actions:
                        consume_message:
                          mode: cleanup
                          correlation_id: P-100
                          cleanup_strategy: drain
                          max_count: 1
                      outputs:
                        actual_output_ref: actual/kafka-cleanup.txt
                      contract_path: release_units[5].provider_contracts.providers.kafka_cleanup_bus
                """;
    }

    private String providerContractNatsRequest() {
        return """
                provider_contracts:
                  providers:
                    nats_request_bus:
                      provider_contract_kind: messaging
                      provider_type: nats
                      server_ref: mock://nats
                      subject_ref: nats://payments.accepted
                      timeout_seconds: 10
                      actions:
                        request_reply_message:
                          mode: request_reply
                          payload_binding: payment_event
                          correlation_id: P-100
                          reply_payload: '{"reply":"accepted","paymentId":"P-100"}'
                      outputs:
                        actual_output_ref: actual/nats-reply.json
                      contract_path: release_units[6].provider_contracts.providers.nats_request_bus
                  bindings:
                    message_event:
                      provider_contract_kind: messaging
                      provider_type: event_payload
                      bind_as: event_payload
                      contract_path: release_units[4].provider_contracts.bindings.message_event
                """;
    }

    private String providerContractNatsObserve() {
        return """
                provider_contracts:
                  providers:
                    nats_observe_bus:
                      provider_contract_kind: messaging
                      provider_type: nats
                      server_ref: mock://nats
                      subject_ref: nats://payments.accepted
                      timeout_seconds: 10
                      actions:
                        consume_message:
                          mode: observe
                          correlation_id: P-100
                          expected_count: 1
                          observed_payload: '{"event":"nats.observed","paymentId":"P-100"}'
                      outputs:
                        actual_output_ref: actual/nats-observed.json
                      contract_path: release_units[7].provider_contracts.providers.nats_observe_bus
                """;
    }

    private String providerContractNatsCleanup() {
        return """
                provider_contracts:
                  providers:
                    nats_cleanup_bus:
                      provider_contract_kind: messaging
                      provider_type: nats
                      server_ref: mock://nats
                      subject_ref: nats://payments.accepted
                      timeout_seconds: 10
                      actions:
                        consume_message:
                          mode: cleanup
                          correlation_id: P-100
                          cleanup_strategy: drain
                          max_count: 1
                      outputs:
                        actual_output_ref: actual/nats-cleanup.txt
                      contract_path: release_units[8].provider_contracts.providers.nats_cleanup_bus
                """;
    }

    private String providerContractK8s() {
        return """
                provider_contracts:
                  providers:
                    k8s_readiness:
                      provider_contract_kind: deployment_readiness
                      provider_type: k8s
                      readiness_probe: rollout_status
                      kube_context_ref: mock://k8s
                      namespace_ref: payments
                      deployment_ref: deployment/payment-api
                      deployed_version_ref: build-123
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/k8s-readiness.txt
                      contract_path: release_units[5].provider_contracts.providers.k8s_readiness
                """;
    }

    private String providerContractVm() {
        return """
                provider_contracts:
                  providers:
                    vm_readiness:
                      provider_contract_kind: deployment_readiness
                      provider_type: vm
                      readiness_probe: tcp_connect
                      host_ref: mock://payment-vm
                      port: 8443
                      service_ref: payment-api
                      deployed_version_ref: build-123
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/vm-readiness.txt
                      contract_path: release_units[6].provider_contracts.providers.vm_readiness
                """;
    }

    private String providerContractExternalRunner() {
        return """
                provider_contracts:
                  providers:
                    external_runner:
                      provider_contract_kind: external_runner
                      provider_type: command_runner
                      approval_ref: docs/10-change-control/external-runner-approval.md
                      approved_by: release_owner
                      reason: legacy harness cannot use a reusable built-in provider yet
                      command: printf 'legacy-ok'
                      timeout_seconds: 10
                      inputs:
                        request: fixtures/api/payment_payload.json
                      outputs:
                        actual_output_ref: actual/legacy-output.txt
                      evidence_map:
                        runner_output: actual/legacy-output.txt
                      contract_path: release_units[7].provider_contracts.providers.external_runner
                """;
    }

    private void writeProviderContract(Path generatedRoot, String targetId, String content) throws Exception {
        Files.writeString(generatedRoot.resolve("provider_contracts/" + targetId + ".yaml"), content);
    }

    private void writeApprovedTests(Path packageRoot) throws Exception {
        writeDslTest(packageRoot, "RP-HET-TC-DB", "RP-HET-AC-DB", "RU-payment-db",
                "spring_boot_cli", "execute_command", "db_output", "actual/db-output.txt", """
                  orders_seed:
                    type: db_seed
                    provider: relational_db
                    ref: fixtures/db/seed_orders.sql
                    bind_as: db_seed
                    cleanup_ref: fixtures/db/cleanup_orders.sql
                    setup_action: seed_orders
                    cleanup_action: cleanup_orders
                """, "");
        writeDslTest(packageRoot, "RP-HET-TC-REST", "RP-HET-AC-REST", "RU-payment-rest",
                "request_response", "call_api", "response", "actual/rest-response.json", "{}", """
                      payment_payload:
                        ref: fixtures/api/payment_payload.json
                        bind_as: api_payload
                """);
        writeDslTest(packageRoot, "RP-HET-TC-GRPC", "RP-HET-AC-GRPC", "RU-payment-grpc",
                "grpc_request_response", "call_api", "response", "actual/grpc-response.json", "{}", """
                      payment_payload:
                        ref: fixtures/api/payment_payload.json
                        bind_as: api_payload
                """);
        writeDslTest(packageRoot, "RP-HET-TC-KAFKA-PUBLISH", "RP-HET-AC-KAFKA-PUBLISH",
                "RU-payment-kafka-publish", "kafka_publish_bus", "publish_message", "event",
                "actual/kafka-message.json", "{}", """
                      payment_event:
                        ref: fixtures/events/payment_event.json
                        bind_as: message_event
                """);
        writeDslTest(packageRoot, "RP-HET-TC-KAFKA-OBSERVE", "RP-HET-AC-KAFKA-OBSERVE",
                "RU-payment-kafka-observe", "kafka_observe_bus", "consume_message", "event",
                "actual/kafka-observed.json", "{}", "");
        writeDslTest(packageRoot, "RP-HET-TC-KAFKA-CLEANUP", "RP-HET-AC-KAFKA-CLEANUP",
                "RU-payment-kafka-cleanup", "kafka_cleanup_bus", "consume_message", "cleanup",
                "actual/kafka-cleanup.txt", "{}", "");
        writeDslTest(packageRoot, "RP-HET-TC-NATS-REQUEST", "RP-HET-AC-NATS-REQUEST",
                "RU-payment-nats-request", "nats_request_bus", "request_reply_message", "reply",
                "actual/nats-reply.json", "{}", """
                      payment_event:
                        ref: fixtures/events/payment_event.json
                        bind_as: message_event
                """);
        writeDslTest(packageRoot, "RP-HET-TC-NATS-OBSERVE", "RP-HET-AC-NATS-OBSERVE",
                "RU-payment-nats-observe", "nats_observe_bus", "consume_message", "event",
                "actual/nats-observed.json", "{}", "");
        writeDslTest(packageRoot, "RP-HET-TC-NATS-CLEANUP", "RP-HET-AC-NATS-CLEANUP",
                "RU-payment-nats-cleanup", "nats_cleanup_bus", "consume_message", "cleanup",
                "actual/nats-cleanup.txt", "{}", "");
        writeDslTest(packageRoot, "RP-HET-TC-K8S", "RP-HET-AC-K8S", "RU-payment-k8s",
                "k8s_readiness", "execute_command", "readiness", "actual/k8s-readiness.txt", "{}", "");
        writeDslTest(packageRoot, "RP-HET-TC-VM", "RP-HET-AC-VM", "RU-payment-vm",
                "vm_readiness", "execute_command", "readiness", "actual/vm-readiness.txt", "{}", "");
        writeDslTest(packageRoot, "RP-HET-TC-RUNNER", "RP-HET-AC-RUNNER", "RU-legacy-runner",
                "external_runner", "execute_command", "runner_output", "actual/legacy-output.txt", "{}", "");
    }

    private void writeDslTest(
            Path packageRoot,
            String testCaseId,
            String acId,
            String targetId,
            String runner,
            String operation,
            String outputName,
            String outputRef,
            String fixturesYaml,
            String withYaml) throws Exception {
        String expectedResultId = acId.replace("-AC-", "-ER-");
        Path testPath = packageRoot.resolve("tests/approved/" + testCaseId + ".yaml");
        Files.createDirectories(testPath.getParent());
        Files.writeString(testPath, """
                dsl_version: v0.2
                test_case_id: %s
                status: active
                revision: 1
                tags: [heterogeneous-runtime]
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                labels:
                  package: %s
                  runtime_unit: %s
                compatible_profiles: [ci_ephemeral]
                targets:
                  %s:
                    provider_id: %s
                    type: runtime_target
                    runner: %s
                    environment: ci://framework-verification/%s
                data:
                  main_expected:
                    ref: expected-results/approved/%s.yaml
                setup:
                  fixtures:
                %s
                execute:
                  - id: execute
                    target: %s
                    operation: %s
                    with:
                %s
                    outputs:
                      %s: %s
                verify:
                  - id: verify
                    type: file_diff
                    actual: ${execute.execute.outputs.%s}
                    expected: ${data.main_expected}
                evidence:
                  required:
                    - ${execute.execute.outputs.%s}
                    - ${verify.verify.result}
                runtime:
                  timeout: PT30S
                  cleanup_required: true
                  retry:
                    max_attempts: 0
                """.formatted(
                testCaseId,
                acId,
                RP_ID,
                targetId,
                targetId,
                targetId,
                runner,
                targetId,
                expectedResultId,
                fixturesYaml.equals("{}") ? "    {}\n" : fixturesYaml.indent(4),
                targetId,
                operation,
                withYaml.isBlank() ? "      {}\n" : withYaml.indent(6),
                outputName,
                outputRef,
                outputName,
                outputName));
    }

    private void writeExpected(Path packageRoot, String acId, String expectedOutput) throws Exception {
        String expectedResultId = acId.replace("-AC-", "-ER-");
        Path expected = packageRoot.resolve("expected-results/approved/" + expectedResultId + ".yaml");
        Files.createDirectories(expected.getParent());
        Files.writeString(expected, """
                expected_result_id: %s
                rp_id: %s
                ac_id: %s
                status: approved_for_regression
                source_refs:
                  - acceptance_criteria.md#%s
                input_refs: []
                expected_outputs:
                  output_ref: expected/output/%s.txt
                assumptions: []
                unresolved_gaps: []
                approved_by: platform
                approved_at: 2026-06-28T00:00:00+08:00
                approval_ref: FRAMEWORK_VERIFICATION_FIXTURE.md
                blocked_reason: null
                """.formatted(expectedResultId, RP_ID, acId, acId, expectedResultId));
        Files.writeString(packageRoot.resolve("expected/output/" + expectedResultId + ".txt"), expectedOutput);
    }

    private void assertRunPassed(Path packageRoot, String testCaseId, String... expectedEvidence) throws Exception {
        Path runDir = runDirForTestCase(packageRoot, testCaseId);
        String run = Files.readString(runDir.resolve("run.yaml"));
        assertThat(run)
                .contains("test_case_id: " + testCaseId)
                .contains("status: passed")
                .contains("assertion_status: passed")
                .contains("provider_runtime_started: true");
        for (String expected : expectedEvidence) {
            assertThat(run + "\n" + evidenceFiles(runDir)).contains(expected);
        }
    }

    private Path runDirForTestCase(Path packageRoot, String testCaseId) throws Exception {
        Path runsRoot = packageRoot.resolve("evidence/runs");
        try (var paths = Files.list(runsRoot)) {
            return paths.filter(Files::isDirectory)
                    .filter(path -> runEvidenceContains(path, "test_case_id: " + testCaseId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing run evidence for " + testCaseId));
        }
    }

    private boolean runEvidenceContains(Path runDir, String expectedText) {
        try {
            return Files.readString(runDir.resolve("run.yaml")).contains(expectedText);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String evidenceFiles(Path runDir) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (String file : List.of(
                "fixture_setup.yaml",
                "cleanup.yaml",
                "request_response.yaml",
                "messaging.yaml",
                "readiness.yaml",
                "external_runner.yaml")) {
            Path path = runDir.resolve(file);
            if (Files.isRegularFile(path)) {
                builder.append(Files.readString(path)).append('\n');
            }
        }
        return builder.toString();
    }

    private int countOrders(String jdbcUrl) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl);
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM orders")) {
            return result.next() ? result.getInt(1) : -1;
        }
    }

    private PrintStream print(ByteArrayOutputStream output) {
        return new PrintStream(output, true);
    }
}

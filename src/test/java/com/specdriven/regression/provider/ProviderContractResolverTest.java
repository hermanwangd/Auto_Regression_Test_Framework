package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderContractResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesAdapterAndBindingContractsFromRuMapping() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("""
                provider_contracts:
                  providers:
                    spring_boot_cli:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      command: java -jar ${repo}/target/release-unit.jar
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings:
                    db_seed:
                      provider_contract_kind: file_batch
                      provider_type: file_fixture
                      materialize_as: input_file
                  fixtures: {}
                  oracles: {}
                  assertions: {}
                  observations: {}
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "spring_boot_cli",
                List.of("db_seed"),
                List.of());

        assertThat(report.ready()).isTrue();
        assertThat(report.resolvedContracts()).extracting(ResolvedProviderContract::contractType)
                .contains("provider", "binding");
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void resolvesWithInjectedCapabilityRegistry() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("""
                provider_contracts:
                  providers:
                    spring_boot_cli:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      command: java -jar ${repo}/target/release-unit.jar
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings: {}
                  fixtures: {}
                """));

        ProviderContractResolutionReport report =
                new ProviderContractResolver(new ProviderCapabilityRegistry()).resolve(
                        mapping,
                        "spring_boot_cli",
                        List.of(),
                        List.of());

        assertThat(report.ready()).isTrue();
        assertThat(report.resolvedContracts()).singleElement().satisfies(contract -> {
            assertThat(contract.contractType()).isEqualTo("provider");
            assertThat(contract.providerName()).isEqualTo("spring_boot_cli");
        });
    }

    @Test
    void blocksMissingAdapterAndBindingContractBeforeExecution() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("provider_contracts: {}\n"));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "spring_boot_cli",
                List.of("db_seed"),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(ProviderContractGap::fieldPath)
                .contains("release_units[0].provider_contracts.providers.spring_boot_cli",
                        "release_units[0].provider_contracts.bindings.db_seed");
        assertThat(report.gaps()).extracting(ProviderContractGap::ownerAction)
                .allMatch(action -> action.contains("Add provider contract"));
    }

    @Test
    void blocksAdapterContractWithoutCommandBeforeExecution() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("""
                provider_contracts:
                  providers:
                    spring_boot_cli:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings:
                    db_seed:
                      provider_contract_kind: file_batch
                      provider_type: file_fixture
                      materialize_as: input_file
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "spring_boot_cli",
                List.of("db_seed"),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(ProviderContractGap::fieldPath)
                .contains("release_units[0].provider_contracts.providers.spring_boot_cli.command");
        assertThat(report.gaps()).extracting(ProviderContractGap::ownerAction)
                .anyMatch(action -> action.contains("Declare executable provider command"));
    }

    @Test
    void blocksFixtureActionWithoutFixtureProviderContract() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("""
                provider_contracts:
                  providers:
                    spring_boot_cli:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      command: java -jar ${repo}/target/release-unit.jar
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings:
                    db_seed:
                      provider_contract_kind: file_batch
                      provider_type: file_fixture
                      materialize_as: input_file
                  fixtures: {}
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "spring_boot_cli",
                List.of("db_seed"),
                List.of("relational_db"));

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(ProviderContractGap::fieldPath)
                .contains("release_units[0].provider_contracts.fixtures.relational_db");
    }

    @Test
    void resolvesProviderFamilyMetadataAcrossReleaseUnits() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - ru_id: RU-payment-api
                    repo: /repo/payment-api
                    unit_type: service
                    owner: product_developer
                    version_ref: build-123
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/api
                    provider: request_response
                    provider_contracts:
                      providers:
                        request_response:
                          provider_contract_kind: request_response
                          provider_type: rest
                          endpoint_ref: env://PAYMENT_API
                          timeout_seconds: 10
                          actions:
                            submit_payment:
                              method: POST
                              path: /payments
                          outputs:
                            actual_output_ref: actual/response.json
                      bindings:
                        api_payload:
                          provider_contract_kind: request_response
                          provider_type: request_body
                          bind_as: request_body
                      fixtures: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                  - ru_id: RU-payment-events
                    repo: /repo/payment-events
                    unit_type: service
                    owner: product_developer
                    version_ref: build-456
                    validation_boundary: event_observation
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/events
                    provider: message_bus
                    provider_contracts:
                      providers:
                        message_bus:
                          provider_contract_kind: messaging
                          provider_type: kafka
                          topic_ref: kafka://payment.events
                      bindings:
                        message_event:
                          provider_contract_kind: messaging
                          provider_type: event_payload
                          bind_as: event_payload
                      fixtures:
                        relational_db:
                          provider_contract_kind: db_fixture
                          provider_type: jdbc
                          connection_ref: secret://ci/payment-db
                          isolation_key: test_run_id
                          cleanup_strategy: by_test_run_id
                    evidence_responsibility: [execution_log, cleanup_result]
                    dependencies: [RU-payment-api]
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "request_response",
                List.of("api_payload", "message_event"),
                List.of("relational_db"));

        assertThat(report.ready()).isTrue();
        assertThat(report.resolvedContracts())
                .anySatisfy(contract -> {
                    assertThat(contract.providerName()).isEqualTo("request_response");
                    assertThat(contract.providerFamily()).isEqualTo("request_response");
                    assertThat(contract.affectedRu()).isEqualTo("RU-payment-api");
                    assertThat(contract.capability()).isEqualTo("request_response");
                    assertThat(contract.contractPath())
                            .isEqualTo("release_units[0].provider_contracts.providers.request_response");
                })
                .anySatisfy(contract -> {
                    assertThat(contract.providerName()).isEqualTo("message_event");
                    assertThat(contract.providerFamily()).isEqualTo("messaging");
                    assertThat(contract.affectedRu()).isEqualTo("RU-payment-events");
                    assertThat(contract.capability()).isEqualTo("message_event");
                    assertThat(contract.contractPath())
                            .isEqualTo("release_units[1].provider_contracts.bindings.message_event");
                })
                .anySatisfy(contract -> {
                    assertThat(contract.providerName()).isEqualTo("relational_db");
                    assertThat(contract.providerFamily()).isEqualTo("db_fixture");
                    assertThat(contract.affectedRu()).isEqualTo("RU-payment-events");
                    assertThat(contract.capability()).isEqualTo("relational_db");
                    assertThat(contract.contractPath())
                            .isEqualTo("release_units[1].provider_contracts.fixtures.relational_db");
                });
    }

    @Test
    void infersProviderFamilyMetadataWhenContractKindIsNotDeclared() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - ru_id: RU-payment-api
                    repo: /repo/payment-api
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    provider: request_response
                    provider_contracts:
                      providers:
                        request_response:
                          provider_type: rest
                          endpoint_ref: env://PAYMENT_API
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/response.json
                          actions:
                            submit_payment:
                              method: POST
                              path: /payments
                      bindings:
                        api_payload:
                          provider_type: request_body
                          bind_as: request_body
                      fixtures: {}
                  - ru_id: RU-payment-events
                    repo: /repo/payment-events
                    validation_boundary: event_observation
                    execution_mode: ci_ephemeral
                    provider: message_bus
                    provider_contracts:
                      providers:
                        message_bus:
                          provider_type: kafka
                          bootstrap_servers_ref: env://KAFKA_BOOTSTRAP
                          topic_ref: kafka://payment.events
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/event.json
                          actions:
                            publish_payment_event:
                              payload_binding: message_event
                      bindings:
                        message_event:
                          provider_type: event_payload
                          bind_as: event_payload
                      fixtures:
                        relational_db:
                          provider_type: jdbc
                          connection_ref: secret://ci/payment-db
                          isolation_key: test_run_id
                          cleanup_strategy: by_test_run_id
                  - ru_id: RU-payment-runtime
                    repo: /repo/payment-runtime
                    validation_boundary: deployed_runtime
                    execution_mode: ci_ephemeral
                    provider: payment_readiness
                    provider_contracts:
                      providers:
                        payment_readiness:
                          provider_type: mock
                          readiness_probe: http_get
                          deployment_ref: payment-api
                          deployed_version_ref: build-43
                          timeout_seconds: 5
                          outputs:
                            actual_output_ref: actual/readiness.txt
                        payment_runner:
                          provider_type: command_runner
                          approval_ref: docs/adr/approved-runner.md
                          approved_by: test-architecture
                          reason: legacy bridge requires approved external runner
                          command: ./run-bridge.sh
                          timeout_seconds: 30
                          inputs:
                            payload: fixtures/request.json
                          outputs:
                            actual_output_ref: actual/runner-output.json
                          evidence_map:
                            runner_log: logs/external-runner.log
                        spring_boot_cli:
                          provider_type: shell
                          command: java -jar target/payment.jar
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/cli-output.txt
                      bindings: {}
                      fixtures: {}
                """));

        ProviderContractResolver resolver = new ProviderContractResolver();
        ProviderContractResolutionReport request = resolver.resolve(
                mapping,
                "request_response",
                List.of("api_payload"),
                List.of());
        ProviderContractResolutionReport messaging = resolver.resolve(
                mapping,
                "message_bus",
                List.of("message_event"),
                List.of("relational_db"));
        ProviderContractResolutionReport readiness = resolver.resolve(mapping, "payment_readiness", List.of(), List.of());
        ProviderContractResolutionReport runner = resolver.resolve(mapping, "payment_runner", List.of(), List.of());
        ProviderContractResolutionReport cli = resolver.resolve(mapping, "spring_boot_cli", List.of(), List.of());

        assertThat(request.ready()).isTrue();
        assertThat(request.resolvedContracts()).extracting(ResolvedProviderContract::providerFamily)
                .contains("request_response");
        assertThat(messaging.ready()).isTrue();
        assertThat(messaging.resolvedContracts()).extracting(ResolvedProviderContract::providerFamily)
                .contains("messaging", "db_fixture");
        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.resolvedContracts()).singleElement()
                .extracting(ResolvedProviderContract::providerFamily)
                .isEqualTo("deployment_readiness");
        assertThat(runner.ready()).isTrue();
        assertThat(runner.resolvedContracts()).singleElement()
                .extracting(ResolvedProviderContract::providerFamily)
                .isEqualTo("external_runner");
        assertThat(cli.ready()).isTrue();
        assertThat(cli.resolvedContracts()).singleElement()
                .extracting(ResolvedProviderContract::providerFamily)
                .isEqualTo("file_batch");
    }

    @Test
    void reportsAffectedRuAndProviderFamilyForMissingHeterogeneousContracts() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - ru_id: RU-payment-events
                    repo: /repo/payment-events
                    unit_type: service
                    owner: product_developer
                    version_ref: build-456
                    validation_boundary: event_observation
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/events
                    provider: message_bus
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "message_bus",
                List.of("message_event"),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps())
                .anySatisfy(gap -> {
                    assertThat(gap.contractType()).isEqualTo("provider");
                    assertThat(gap.providerName()).isEqualTo("message_bus");
                    assertThat(gap.providerFamily()).isEqualTo("messaging");
                    assertThat(gap.affectedRu()).isEqualTo("RU-payment-events");
                    assertThat(gap.capability()).isEqualTo("message_bus");
                    assertThat(gap.fieldPath())
                            .isEqualTo("release_units[0].provider_contracts.providers.message_bus");
                    assertThat(gap.ownerAction()).contains("RU-payment-events");
                })
                .anySatisfy(gap -> {
                    assertThat(gap.contractType()).isEqualTo("binding");
                    assertThat(gap.providerName()).isEqualTo("message_event");
                    assertThat(gap.providerFamily()).isEqualTo("messaging");
                    assertThat(gap.affectedRu()).isEqualTo("RU-payment-events");
                    assertThat(gap.capability()).isEqualTo("message_event");
                    assertThat(gap.fieldPath())
                            .isEqualTo("release_units[0].provider_contracts.bindings.message_event");
                    assertThat(gap.ownerAction()).contains("RU-payment-events");
                });
    }

    @Test
    void blocksAmbiguousAdapterMatchAcrossReleaseUnitsBeforeExecution() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - ru_id: RU-payment-api-blue
                    repo: /repo/payment-api-blue
                    unit_type: service
                    owner: product_developer
                    version_ref: build-123
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/api-blue
                    provider: request_response
                    provider_contracts:
                      providers:
                        request_response:
                          provider_contract_kind: request_response
                          provider_type: rest
                          endpoint_ref: env://PAYMENT_API_BLUE
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/blue-response.json
                          actions:
                            submit_payment:
                              method: POST
                              path: /payments
                    evidence_responsibility: [execution_log]
                    dependencies: []
                  - ru_id: RU-payment-api-green
                    repo: /repo/payment-api-green
                    unit_type: service
                    owner: product_developer
                    version_ref: build-456
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/api-green
                    provider: request_response
                    provider_contracts:
                      providers:
                        request_response:
                          provider_contract_kind: request_response
                          provider_type: rest
                          endpoint_ref: env://PAYMENT_API_GREEN
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/green-response.json
                          actions:
                            submit_payment:
                              method: POST
                              path: /payments
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "request_response",
                List.of(),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.resolvedContracts()).isEmpty();
        assertThat(report.gaps())
                .anySatisfy(gap -> {
                    assertThat(gap.contractType()).isEqualTo("provider");
                    assertThat(gap.providerName()).isEqualTo("request_response");
                    assertThat(gap.providerFamily()).isEqualTo("request_response");
                    assertThat(gap.registryStatus()).isEqualTo("ambiguous");
                    assertThat(gap.runtimeStatus()).isEqualTo("blocked");
                    assertThat(gap.affectedRu()).isEqualTo("RU-payment-api-blue,RU-payment-api-green");
                    assertThat(gap.fieldPath())
                            .isEqualTo("release_units[0].provider_contracts.providers.request_response");
                    assertThat(gap.ownerAction())
                            .contains("Select target RU")
                            .contains("RU-payment-api-blue")
                            .contains("RU-payment-api-green");
        });
    }

    @Test
    void blocksAmbiguousBindingAndFixtureOwnersAcrossReleaseUnits() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - ru_id: RU-runner
                    repo: /repo/runner
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: build-1
                    validation_boundary: execute_pipeline
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://runner
                    provider: spring_boot_cli
                    provider_contracts:
                      providers:
                        spring_boot_cli:
                          provider_contract_kind: file_batch
                          provider_type: shell
                          command: java -jar app.jar
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/output.txt
                    evidence_responsibility: [execution_log]
                    dependencies: []
                  - ru_id: RU-source-a
                    repo: /repo/source-a
                    unit_type: fixture
                    owner: product_developer
                    version_ref: build-a
                    validation_boundary: data_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://source-a
                    provider: source_a
                    provider_contracts:
                      bindings:
                        db_seed:
                          provider_contract_kind: file_batch
                          provider_type: file_fixture
                          materialize_as: input_file
                      fixtures:
                        relational_db:
                          provider_contract_kind: db_fixture
                          provider_type: jdbc
                          connection_ref: secret://db-a
                          cleanup_strategy: by_test_run_id
                          isolation_key: test_run_id
                    evidence_responsibility: [fixture]
                    dependencies: []
                  - ru_id: RU-source-b
                    repo: /repo/source-b
                    unit_type: fixture
                    owner: product_developer
                    version_ref: build-b
                    validation_boundary: data_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://source-b
                    provider: source_b
                    provider_contracts:
                      bindings:
                        db_seed:
                          provider_contract_kind: file_batch
                          provider_type: file_fixture
                          materialize_as: input_file
                      fixtures:
                        relational_db:
                          provider_contract_kind: db_fixture
                          provider_type: jdbc
                          connection_ref: secret://db-b
                          cleanup_strategy: by_test_run_id
                          isolation_key: test_run_id
                    evidence_responsibility: [fixture]
                    dependencies: []
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "spring_boot_cli",
                List.of("db_seed"),
                List.of("relational_db"));

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps())
                .anySatisfy(gap -> {
                    assertThat(gap.contractType()).isEqualTo("binding");
                    assertThat(gap.providerName()).isEqualTo("db_seed");
                    assertThat(gap.registryStatus()).isEqualTo("ambiguous");
                    assertThat(gap.affectedRu()).isEqualTo("RU-source-a,RU-source-b");
                })
                .anySatisfy(gap -> {
                    assertThat(gap.contractType()).isEqualTo("fixture");
                    assertThat(gap.providerName()).isEqualTo("relational_db");
                    assertThat(gap.registryStatus()).isEqualTo("ambiguous");
                    assertThat(gap.affectedRu()).isEqualTo("RU-source-a,RU-source-b");
                });
    }

    @Test
    void reportsProviderFamilySpecificGapWhenRequestResponseContractIsIncomplete() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - ru_id: RU-payment-api
                    repo: /repo/payment-api
                    unit_type: service
                    owner: product_developer
                    version_ref: build-123
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/api
                    provider: request_response
                    provider_contracts:
                      providers:
                        request_response:
                          provider_contract_kind: request_response
                          provider_type: rest
                          actions:
                            submit_payment:
                              method: POST
                              path: /payments
                      bindings:
                        api_payload:
                          provider_contract_kind: request_response
                          provider_type: request_body
                          bind_as: request_body
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "request_response",
                List.of("api_payload"),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps())
                .anySatisfy(gap -> {
                    assertThat(gap.providerFamily()).isEqualTo("request_response");
                    assertThat(gap.affectedRu()).isEqualTo("RU-payment-api");
                    assertThat(gap.capability()).isEqualTo("request_response");
                    assertThat(gap.fieldPath())
                            .isEqualTo("release_units[0].provider_contracts.providers.request_response.endpoint_ref");
                    assertThat(gap.ownerAction()).contains("endpoint", "RU-payment-api");
                    assertThat(gap.ownerAction()).doesNotContain("command");
                });
    }

    @Test
    void blocksSelectedContractsWithoutExplicitProviderFamilyAndType() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("""
                provider_contracts:
                  providers:
                    spring_boot_cli:
                      command: java -jar ${repo}/target/release-unit.jar
                  bindings:
                    db_seed:
                      provider: file_fixture
                      materialize_as: input_file
                  fixtures: {}
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "spring_boot_cli",
                List.of("db_seed"),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(ProviderContractGap::fieldPath)
                .contains(
                        "release_units[0].provider_contracts.providers.spring_boot_cli.provider_type",
                        "release_units[0].provider_contracts.bindings.db_seed.provider_type");
        assertThat(report.gaps()).extracting(ProviderContractGap::ownerAction)
                .anyMatch(action -> action.contains("provider_type"));
    }

    @Test
    void blocksIncompleteNativeMessagingContractBeforeExecution() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - ru_id: RU-payment-events
                    repo: /repo/payment-events
                    unit_type: service
                    owner: product_developer
                    version_ref: build-456
                    validation_boundary: event_observation
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/events
                    provider: message_bus
                    provider_contracts:
                      providers:
                        message_bus:
                          provider_contract_kind: messaging
                          provider_type: kafka
                          topic_ref: kafka://payment.events
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "message_bus",
                List.of(),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps())
                .anySatisfy(gap -> {
                    assertThat(gap.fieldPath())
                            .isEqualTo("release_units[0].provider_contracts.providers.message_bus.bootstrap_servers_ref");
                    assertThat(gap.providerFamily()).isEqualTo("messaging");
                    assertThat(gap.providerType()).isEqualTo("kafka");
                    assertThat(gap.registryStatus()).isEqualTo("incomplete");
                    assertThat(gap.ownerAction()).contains("Declare bootstrap_servers_ref or connection_ref");
                });
    }

    @Test
    void blocksExternalRunnerWithoutEscapeHatchApprovalMetadata() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - ru_id: RU-legacy-runner
                    repo: /repo/legacy-runner
                    unit_type: external_test_runner
                    owner: qa
                    version_ref: runner-42
                    validation_boundary: external_runner
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://runner
                    provider: external_runner
                    provider_contracts:
                      providers:
                        external_runner:
                          provider_contract_kind: external_runner
                          provider_type: command_runner
                          command: ./run-legacy-check.sh
                    evidence_responsibility: [runner_result]
                    dependencies: []
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "external_runner",
                List.of(),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps())
                .anySatisfy(gap -> {
                    assertThat(gap.fieldPath())
                            .isEqualTo("release_units[0].provider_contracts.providers.external_runner.approval_ref");
                    assertThat(gap.providerFamily()).isEqualTo("external_runner");
                    assertThat(gap.providerType()).isEqualTo("command_runner");
                    assertThat(gap.registryStatus()).isEqualTo("unapproved_escape_hatch");
	                    assertThat(gap.ownerAction()).contains("approval metadata");
	                });
	    }

    @Test
    void resolvesGeneratedProviderContractsAndDirectContractAccessors() throws Exception {
        writeGeneratedFramework(
                tempDir,
                """
                    RU-generated-job:
                      target_id: RU-generated-job
                      runner: spring_boot_cli
                      execution_mode: ci_ephemeral
                      environment_ref: ci://generated/RU-generated-job
                      provider_contract_ref: provider_contracts/RU-generated-job.yaml#providers.spring_boot_cli
                    """,
                generatedProviderContracts("""
                  providers:
                    spring_boot_cli:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      command: java -jar app.jar
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings:
                    db_seed:
                      provider_contract_kind: file_batch
                      provider_type: file_fixture
                      materialize_as: input_file
                  fixtures:
                    relational_db:
                      provider_contract_kind: db_fixture
                      provider_type: jdbc
                      connection_ref: secret://ci/payment-db
                      cleanup_strategy: by_test_run_id
                      isolation_key: test_run_id
                """));
        ProviderContractResolver resolver = new ProviderContractResolver();

        ProviderContractResolutionReport report = resolver.resolveGenerated(
                tempDir,
                "ci_ephemeral",
                "RU-generated-job",
                "spring_boot_cli",
                List.of("db_seed"),
                List.of("relational_db"));

        assertThat(report.ready()).isTrue();
        assertThat(report.gaps()).isEmpty();
        assertThat(report.resolvedContracts())
                .anySatisfy(contract -> {
                    assertThat(contract.contractType()).isEqualTo("provider");
                    assertThat(contract.providerName()).isEqualTo("spring_boot_cli");
                    assertThat(contract.sourceLevel()).isEqualTo("generated");
                    assertThat(contract.affectedRu()).isEqualTo("RU-generated-job");
                    assertThat(contract.contractPath())
                            .isEqualTo("generated-framework/provider_contracts/RU-generated-job.yaml#providers.spring_boot_cli");
                })
                .anySatisfy(contract -> {
                    assertThat(contract.contractType()).isEqualTo("binding");
                    assertThat(contract.providerName()).isEqualTo("db_seed");
                    assertThat(contract.providerFamily()).isEqualTo("file_batch");
                })
                .anySatisfy(contract -> {
                    assertThat(contract.contractType()).isEqualTo("fixture");
                    assertThat(contract.providerName()).isEqualTo("relational_db");
                    assertThat(contract.providerFamily()).isEqualTo("db_fixture");
                });
        assertThat(resolver.generatedAdapterContract(
                tempDir,
                "ci_ephemeral",
                "RU-generated-job",
                "spring_boot_cli"))
                .containsEntry("command", "java -jar app.jar");
        assertThat(resolver.generatedFixtureContract(
                tempDir,
                "ci_ephemeral",
                "RU-generated-job",
                "spring_boot_cli",
                "relational_db"))
                .containsEntry("isolation_key", "test_run_id");
    }

    @Test
    void blocksAmbiguousGeneratedTargetWhenTargetIdIsNotSelected() throws Exception {
        writeGeneratedFramework(
                tempDir,
                """
                    RU-generated-blue:
                      target_id: RU-generated-blue
                      runner: spring_boot_cli
                      environment_ref: ci://generated/blue
                      provider_contract_ref: provider_contracts/RU-generated-job.yaml#providers.spring_boot_cli
                    RU-generated-green:
                      target_id: RU-generated-green
                      runner: spring_boot_cli
                      environment_ref: ci://generated/green
                      provider_contract_ref: provider_contracts/RU-generated-job.yaml#providers.spring_boot_cli
                    """,
                generatedProviderContracts("""
                  providers:
                    spring_boot_cli:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      command: java -jar app.jar
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings: {}
                  fixtures: {}
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolveGenerated(
                tempDir,
                "ci_ephemeral",
                "",
                "spring_boot_cli",
                List.of(),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.resolvedContracts()).isEmpty();
        assertThat(report.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.contractType()).isEqualTo("provider");
            assertThat(gap.providerName()).isEqualTo("spring_boot_cli");
            assertThat(gap.registryStatus()).isEqualTo("ambiguous");
            assertThat(gap.runtimeStatus()).isEqualTo("blocked");
            assertThat(gap.affectedRu()).isEqualTo("RU-generated-blue,RU-generated-green");
            assertThat(gap.ownerAction()).contains("Select target ID");
        });
    }

    @Test
    void resolvesSingleGeneratedTargetWhenTargetIdIsNotSelected() throws Exception {
        writeGeneratedFramework(
                tempDir,
                """
                    RU-generated-job:
                      target_id: RU-generated-job
                      runner: spring_boot_cli
                      environment_ref: ci://generated/job
                      provider_contract_ref: provider_contracts/RU-generated-job.yaml#providers.spring_boot_cli
                    """,
                generatedProviderContracts("""
                  providers:
                    spring_boot_cli:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      command: java -jar app.jar
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings: {}
                  fixtures: {}
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolveGenerated(
                tempDir,
                "ci_ephemeral",
                "",
                "spring_boot_cli",
                List.of(),
                List.of());

        assertThat(report.ready()).isTrue();
        assertThat(report.gaps()).isEmpty();
        assertThat(report.resolvedContracts()).singleElement().satisfies(contract -> {
            assertThat(contract.providerName()).isEqualTo("spring_boot_cli");
            assertThat(contract.affectedRu()).isEqualTo("RU-generated-job");
        });
    }

    @Test
    void returnsEmptyGeneratedAccessorContractsWhenTargetIsMissing() throws Exception {
        writeGeneratedFramework(
                tempDir,
                """
                    RU-generated-job:
                      target_id: RU-generated-job
                      runner: spring_boot_cli
                      environment_ref: ci://generated/job
                      provider_contract_ref: provider_contracts/RU-generated-job.yaml#providers.spring_boot_cli
                    """,
                generatedProviderContracts("""
                  providers:
                    spring_boot_cli:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      command: java -jar app.jar
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings: {}
                  fixtures: {}
                """));
        ProviderContractResolver resolver = new ProviderContractResolver();

        assertThat(resolver.generatedAdapterContract(
                tempDir,
                "ci_ephemeral",
                "RU-missing",
                "spring_boot_cli"))
                .isEmpty();
        assertThat(resolver.generatedFixtureContract(
                tempDir,
                "ci_ephemeral",
                "RU-missing",
                "spring_boot_cli",
                "relational_db"))
                .isEmpty();
    }

    @Test
    void generatedResolutionReportsMissingTargetWhenNoGeneratedBindingMatchesRequest() throws Exception {
        writeGeneratedFramework(
                tempDir,
                """
                    RU-generated-job:
                      target_id: RU-generated-job
                      runner: spring_boot_cli
                      environment_ref: ci://generated/job
                      provider_contract_ref: provider_contracts/RU-generated-job.yaml#providers.spring_boot_cli
                    """,
                generatedProviderContracts("""
                  providers:
                    spring_boot_cli:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      command: java -jar app.jar
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings: {}
                  fixtures: {}
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolveGenerated(
                tempDir,
                "ci_ephemeral",
                "RU-missing",
                "spring_boot_cli",
                List.of(),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.resolvedContracts()).isEmpty();
        assertThat(report.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.fieldPath()).isEqualTo("generated-framework/environment_bindings.targets");
            assertThat(gap.contractType()).isEqualTo("provider");
            assertThat(gap.registryStatus()).isEqualTo("missing");
            assertThat(gap.runtimeStatus()).isEqualTo("blocked");
            assertThat(gap.affectedRu()).isEqualTo("RU-missing");
        });
    }

    @Test
    void generatedResolutionReportsBlankProviderNameAndMissingGeneratedContracts() throws Exception {
        writeGeneratedFramework(
                tempDir,
                """
                    RU-generated-job:
                      target_id: RU-generated-job
                      runner: ""
                      environment_ref: ci://generated/job
                      provider_contract_ref: provider_contracts/RU-generated-job.yaml#providers.
                    """,
                generatedProviderContracts("""
                  providers: {}
                  bindings: {}
                  fixtures: {}
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolveGenerated(
                tempDir,
                "ci_ephemeral",
                "RU-generated-job",
                "",
                List.of("db_seed"),
                List.of("relational_db"));

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps())
                .anySatisfy(gap -> {
                    assertThat(gap.fieldPath()).isEqualTo("generated-framework/provider_contracts");
                    assertThat(gap.contractType()).isEqualTo("provider");
                    assertThat(gap.registryStatus()).isEqualTo("missing");
                })
                .anySatisfy(gap -> {
                    assertThat(gap.fieldPath())
                            .isEqualTo("generated-framework/provider_contracts/RU-generated-job.yaml#bindings.db_seed");
                    assertThat(gap.contractType()).isEqualTo("binding");
                    assertThat(gap.providerName()).isEqualTo("db_seed");
                })
                .anySatisfy(gap -> {
                    assertThat(gap.fieldPath())
                            .isEqualTo("generated-framework/provider_contracts/RU-generated-job.yaml#fixtures.relational_db");
                    assertThat(gap.contractType()).isEqualTo("fixture");
                    assertThat(gap.providerName()).isEqualTo("relational_db");
                });
    }

    @Test
    void generatedResolutionReportsProviderValidationViolationsWithAffectedTarget() throws Exception {
        writeGeneratedFramework(
                tempDir,
                """
                    RU-generated-job:
                      target_id: RU-generated-job
                      runner: spring_boot_cli
                      environment_ref: ci://generated/job
                      provider_contract_ref: provider_contracts/RU-generated-job.yaml#providers.spring_boot_cli
                    """,
                generatedProviderContracts("""
                  providers:
                    spring_boot_cli:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings: {}
                  fixtures: {}
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolveGenerated(
                tempDir,
                "ci_ephemeral",
                "RU-generated-job",
                "spring_boot_cli",
                List.of(),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.resolvedContracts()).isEmpty();
        assertThat(report.gaps()).anySatisfy(gap -> {
            assertThat(gap.fieldPath())
                    .isEqualTo("generated-framework/provider_contracts/RU-generated-job.yaml#providers.spring_boot_cli.command");
            assertThat(gap.contractType()).isEqualTo("provider");
            assertThat(gap.providerFamily()).isEqualTo("file_batch");
            assertThat(gap.providerType()).isEqualTo("shell");
            assertThat(gap.ownerAction()).contains("Affected target: `RU-generated-job`");
        });
    }

    @Test
    void doesNotDuplicateAffectedContextWhenOwnerActionAlreadyNamesRuOrTarget() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - ru_id: spring_boot_cli
                    repo: /repo/runner
                    unit_type: service
                    owner: product_developer
                    version_ref: build-1
                    validation_boundary: execute_pipeline
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://runner
                    provider: spring_boot_cli
                    provider_contracts:
                      providers:
                        spring_boot_cli:
                          provider_contract_kind: file_batch
                          provider_type: shell
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/output.txt
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """));
        ProviderContractResolutionReport ruReport = new ProviderContractResolver().resolve(
                mapping,
                "spring_boot_cli",
                List.of(),
                List.of());

        assertThat(ruReport.ready()).isFalse();
        assertThat(ruReport.gaps()).anySatisfy(gap -> {
            assertThat(gap.ownerAction()).contains("spring_boot_cli");
            assertThat(gap.ownerAction()).doesNotContain("Affected RU");
        });

        writeGeneratedFramework(
                tempDir,
                """
                    spring_boot_cli:
                      target_id: spring_boot_cli
                      runner: spring_boot_cli
                      environment_ref: ci://generated/job
                      provider_contract_ref: provider_contracts/RU-generated-job.yaml#providers.spring_boot_cli
                    """,
                generatedProviderContracts("""
                  providers:
                    spring_boot_cli:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings: {}
                  fixtures: {}
                """));
        ProviderContractResolutionReport generatedReport = new ProviderContractResolver().resolveGenerated(
                tempDir,
                "ci_ephemeral",
                "spring_boot_cli",
                "spring_boot_cli",
                List.of(),
                List.of());

        assertThat(generatedReport.ready()).isFalse();
        assertThat(generatedReport.gaps()).anySatisfy(gap -> {
            assertThat(gap.ownerAction()).contains("spring_boot_cli");
            assertThat(gap.ownerAction()).doesNotContain("Affected target");
        });
    }

    @Test
    void resolvesExplicitTargetRuWhenAdapterNameMatchesMultipleReleaseUnits() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - ru_id: RU-payment-api-blue
                    repo: /repo/payment-api-blue
                    unit_type: service
                    owner: product_developer
                    version_ref: build-123
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/api-blue
                    provider: request_response
                    provider_contracts:
                      providers:
                        request_response:
                          provider_contract_kind: request_response
                          provider_type: rest
                          endpoint_ref: env://PAYMENT_API_BLUE
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/blue-response.json
                          actions:
                            submit_payment:
                              method: POST
                              path: /payments
                    evidence_responsibility: [execution_log]
                    dependencies: []
                  - ru_id: RU-payment-api-green
                    repo: /repo/payment-api-green
                    unit_type: service
                    owner: product_developer
                    version_ref: build-456
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/api-green
                    provider: request_response
                    provider_contracts:
                      providers:
                        request_response:
                          provider_contract_kind: request_response
                          provider_type: rest
                          endpoint_ref: env://PAYMENT_API_GREEN
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/green-response.json
                          actions:
                            submit_payment:
                              method: POST
                              path: /payments
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "RU-payment-api-green",
                "request_response",
                List.of(),
                List.of());

        assertThat(report.ready()).isTrue();
        assertThat(report.gaps()).isEmpty();
        assertThat(report.resolvedContracts()).singleElement().satisfies(contract -> {
            assertThat(contract.affectedRu()).isEqualTo("RU-payment-api-green");
            assertThat(contract.contractPath())
                    .isEqualTo("release_units[1].provider_contracts.providers.request_response");
        });
    }

    @Test
    void fallsBackToAdapterMatchWhenRequestedTargetRuDoesNotExist() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("""
                provider_contracts:
                  providers:
                    spring_boot_cli:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      command: java -jar ${repo}/target/release-unit.jar
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings: {}
                  fixtures: {}
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "RU-does-not-exist",
                "spring_boot_cli",
                List.of(),
                List.of());

        assertThat(report.ready()).isTrue();
        assertThat(report.resolvedContracts()).singleElement().satisfies(contract ->
                assertThat(contract.affectedRu()).isEqualTo("RU-transform-job"));
    }

    @Test
    void infersProviderFamilyForMissingContractsFromNameBoundaryAndSection() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - ru_id: RU-custom
                    repo: /repo/custom
                    unit_type: custom
                    owner: product_developer
                    version_ref: build-1
                    validation_boundary: custom
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://custom
                    provider: spring_boot_cli
                    provider_contracts:
                      providers:
                        spring_boot_cli:
                          provider_contract_kind: file_batch
                          provider_type: shell
                          command: java -jar app.jar
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/output.txt
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "spring_boot_cli",
                List.of("request_payload", "plain_state"),
                List.of("k8s_readiness", "external_runner"));

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps())
                .anySatisfy(gap -> {
                    assertThat(gap.providerName()).isEqualTo("request_payload");
                    assertThat(gap.providerFamily()).isEqualTo("request_response");
                })
                .anySatisfy(gap -> {
                    assertThat(gap.providerName()).isEqualTo("k8s_readiness");
                    assertThat(gap.providerFamily()).isEqualTo("deployment_readiness");
                })
                .anySatisfy(gap -> {
                    assertThat(gap.providerName()).isEqualTo("external_runner");
                    assertThat(gap.providerFamily()).isEqualTo("external_runner");
                })
                .anySatisfy(gap -> {
                    assertThat(gap.providerName()).isEqualTo("plain_state");
                    assertThat(gap.providerFamily()).isEqualTo("binding");
	                });
    }

    @Test
    void reportsMissingContractsAgainstFirstRuWhenAdapterCannotBeMatched() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - ru_id: RU-first
                    repo: /repo/first
                    unit_type: service
                    owner: product_developer
                    version_ref: build-1
                    validation_boundary: event_observation
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://first
                    provider: actual_adapter
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "missing_adapter",
                List.of("missing_binding"),
                List.of("missing_fixture"));

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps())
                .anySatisfy(gap -> {
                    assertThat(gap.contractType()).isEqualTo("provider");
                    assertThat(gap.providerName()).isEqualTo("missing_adapter");
                    assertThat(gap.affectedRu()).isEqualTo("RU-first");
                    assertThat(gap.fieldPath())
                            .isEqualTo("release_units[0].provider_contracts.providers.missing_adapter");
                    assertThat(gap.ownerAction()).contains("RU-first");
                })
                .anySatisfy(gap -> {
                    assertThat(gap.contractType()).isEqualTo("binding");
                    assertThat(gap.providerName()).isEqualTo("missing_binding");
                    assertThat(gap.affectedRu()).isEqualTo("RU-first");
                })
                .anySatisfy(gap -> {
                    assertThat(gap.contractType()).isEqualTo("fixture");
                    assertThat(gap.providerName()).isEqualTo("missing_fixture");
                    assertThat(gap.affectedRu()).isEqualTo("RU-first");
                });
    }

    @Test
    void infersProviderFamilyForAmbiguousContractsWithoutDeclaredFamily() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - ru_id: RU-rest-blue
                    repo: /repo/rest-blue
                    unit_type: service
                    owner: product_developer
                    version_ref: build-1
                    validation_boundary: custom
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://rest-blue
                    provider: rest_adapter
                    provider_contracts:
                      providers:
                        rest_adapter:
                          provider_type: rest
                    evidence_responsibility: [execution_log]
                    dependencies: []
                  - ru_id: RU-rest-green
                    repo: /repo/rest-green
                    unit_type: service
                    owner: product_developer
                    version_ref: build-2
                    validation_boundary: custom
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://rest-green
                    provider: rest_adapter
                    provider_contracts:
                      providers:
                        rest_adapter:
                          provider_type: rest
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "rest_adapter",
                List.of(),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.registryStatus()).isEqualTo("ambiguous");
            assertThat(gap.providerFamily()).isEqualTo("request_response");
            assertThat(gap.providerType()).isEqualTo("rest");
            assertThat(gap.affectedRu()).isEqualTo("RU-rest-blue,RU-rest-green");
        });
    }

    @Test
    void filtersBlankRuIdsFromAmbiguousRuGap() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, heterogeneousMapping("""
                  - repo: /repo/blank
                    unit_type: service
                    owner: product_developer
                    version_ref: build-blank
                    validation_boundary: custom
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://blank
                    provider: shared_adapter
                    provider_contracts:
                      providers:
                        shared_adapter:
                          provider_contract_kind: file_batch
                          provider_type: shell
                          command: java -jar blank.jar
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/blank.txt
                    evidence_responsibility: [execution_log]
                    dependencies: []
                  - ru_id: RU-named
                    repo: /repo/named
                    unit_type: service
                    owner: product_developer
                    version_ref: build-named
                    validation_boundary: custom
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://named
                    provider: shared_adapter
                    provider_contracts:
                      providers:
                        shared_adapter:
                          provider_contract_kind: file_batch
                          provider_type: shell
                          command: java -jar named.jar
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/named.txt
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "shared_adapter",
                List.of(),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.registryStatus()).isEqualTo("ambiguous");
            assertThat(gap.affectedRu()).isEqualTo("RU-named");
        });
    }

    @Test
    void infersProviderFamilyFallbackMatrixForAmbiguousUndeclaredContracts() throws Exception {
        List<InferenceCase> cases = List.of(
                new InferenceCase("grpc_adapter", "grpc", "custom", "request_response"),
                new InferenceCase("event_adapter", "nats", "custom", "messaging"),
                new InferenceCase("plain_adapter", "custom", "event_stream", "messaging"),
                new InferenceCase("warehouse", "document_db", "custom", "db_fixture"),
                new InferenceCase("vm_probe", "vm", "custom", "deployment_readiness"),
                new InferenceCase("health_probe", "custom", "deployed_service", "deployment_readiness"),
                new InferenceCase("legacy_runner", "custom", "custom", "external_runner"),
                new InferenceCase("batch_cli", "custom", "custom", "file_batch"));
        ProviderContractResolver resolver = new ProviderContractResolver();

        for (int i = 0; i < cases.size(); i++) {
            InferenceCase inferenceCase = cases.get(i);
            Path mapping = tempDir.resolve("inference-" + i + ".yaml");
            Files.writeString(mapping, ambiguousUndeclaredAdapterMapping(inferenceCase));

            ProviderContractResolutionReport report = resolver.resolve(
                    mapping,
                    inferenceCase.providerName(),
                    List.of(),
                    List.of());

            assertThat(report.ready()).as(inferenceCase.providerName()).isFalse();
            assertThat(report.gaps()).singleElement().satisfies(gap -> {
                assertThat(gap.registryStatus()).isEqualTo("ambiguous");
                assertThat(gap.providerName()).isEqualTo(inferenceCase.providerName());
                assertThat(gap.providerFamily()).isEqualTo(inferenceCase.expectedFamily());
                assertThat(gap.providerType()).isEqualTo(inferenceCase.providerType());
            });
        }
    }

    @Test
    void skipsMalformedReleaseUnitEntriesWhileResolvingContracts() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: RP-MALFORMED-RU
                release_units:
                  - not-a-map
                  - ru_id: RU-valid
                    provider: valid_adapter
                    validation_boundary: custom
                    execution_mode: ci_ephemeral
                    provider_contracts:
                      providers:
                        valid_adapter:
                          provider_contract_kind: file_batch
                          provider_type: shell
                          command: java -jar app.jar
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/output.txt
                """);

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "valid_adapter",
                List.of(),
                List.of());

        assertThat(report.ready()).isTrue();
        assertThat(report.resolvedContracts()).singleElement().satisfies(contract -> {
            assertThat(contract.affectedRu()).isEqualTo("RU-valid");
            assertThat(contract.contractPath())
                    .isEqualTo("release_units[1].provider_contracts.providers.valid_adapter");
        });
    }

    @Test
    void reportsOwnerActionWhenMappingHasNoReleaseUnits() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: RP-EMPTY
                release_units: []
                """);

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "spring_boot_cli",
                List.of("db_seed"),
                List.of("relational_db"));

        assertThat(report.ready()).isFalse();
        assertThat(report.resolvedContracts()).isEmpty();
        assertThat(report.gaps()).extracting(ProviderContractGap::fieldPath)
                .containsExactly(
                        "release_units[0].provider_contracts.providers.spring_boot_cli",
                        "release_units[0].provider_contracts.bindings.db_seed",
                        "release_units[0].provider_contracts.fixtures.relational_db");
        assertThat(report.gaps()).extracting(ProviderContractGap::registryStatus)
                .containsOnly("missing");
        assertThat(report.gaps()).extracting(ProviderContractGap::runtimeStatus)
                .containsOnly("blocked");
    }

    @Test
    void reportsMissingContractsWhenMappingRootOrReleaseUnitsAreNotReadableObjects() throws Exception {
        Path listRootMapping = tempDir.resolve("list-root.yaml");
        Path missingUnitsMapping = tempDir.resolve("missing-units.yaml");
        Files.writeString(listRootMapping, "[]\n");
        Files.writeString(missingUnitsMapping, "rp_id: RP-NO-UNITS\n");

        ProviderContractResolutionReport listRootReport = new ProviderContractResolver().resolve(
                listRootMapping,
                "spring_boot_cli",
                List.of(),
                List.of());
        ProviderContractResolutionReport missingUnitsReport = new ProviderContractResolver().resolve(
                missingUnitsMapping,
                "spring_boot_cli",
                List.of(),
                List.of());

        assertThat(listRootReport.ready()).isFalse();
        assertThat(missingUnitsReport.ready()).isFalse();
        assertThat(listRootReport.gaps()).singleElement()
                .extracting(ProviderContractGap::fieldPath)
                .isEqualTo("release_units[0].provider_contracts.providers.spring_boot_cli");
        assertThat(missingUnitsReport.gaps()).singleElement()
                .extracting(ProviderContractGap::fieldPath)
                .isEqualTo("release_units[0].provider_contracts.providers.spring_boot_cli");
    }

    @Test
    void treatsNonMapProviderContractsAsEmptyContracts() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: RP-SCALAR-CONTRACTS
                release_units:
                  - ru_id: RU-scalar
                    provider: scalar_adapter
                    validation_boundary: custom
                    execution_mode: ci_ephemeral
                    provider_contracts: not-a-map
                """);

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "scalar_adapter",
                List.of(),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.providerName()).isEqualTo("scalar_adapter");
            assertThat(gap.affectedRu()).isEqualTo("RU-scalar");
            assertThat(gap.registryStatus()).isEqualTo("missing");
        });
    }

    @Test
    void throwsUncheckedIoExceptionWhenMappingCannotBeRead() {
        Path missing = tempDir.resolve("missing-rp-ru-mapping.yaml");

        assertThatThrownBy(() -> new ProviderContractResolver().resolve(
                missing,
                "spring_boot_cli",
                List.of(),
                List.of()))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to read provider contracts");
    }

    private String mappingWithContracts(String providerContracts) {
        return """
                rp_id: RP-001
                release_units:
                  - ru_id: RU-transform-job
                    repo: /repo/transform
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/RP-001
                    provider: spring_boot_cli
                %s\
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """.formatted(providerContracts.indent(4));
    }

    private String heterogeneousMapping(String releaseUnits) {
        return """
                rp_id: RP-HETEROGENEOUS
                release_units:
                %s\
                """.formatted(releaseUnits);
    }

    private String ambiguousUndeclaredAdapterMapping(InferenceCase inferenceCase) {
        return heterogeneousMapping("""
                  - ru_id: RU-%s-blue
                    repo: /repo/%s-blue
                    unit_type: service
                    owner: product_developer
                    version_ref: build-blue
                    validation_boundary: %s
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://%s/blue
                    provider: %s
                    provider_contracts:
                      providers:
                        %s:
                          provider_type: %s
                    evidence_responsibility: [execution_log]
                    dependencies: []
                  - ru_id: RU-%s-green
                    repo: /repo/%s-green
                    unit_type: service
                    owner: product_developer
                    version_ref: build-green
                    validation_boundary: %s
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://%s/green
                    provider: %s
                    provider_contracts:
                      providers:
                        %s:
                          provider_type: %s
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """.formatted(
                inferenceCase.providerName(),
                inferenceCase.providerName(),
                inferenceCase.validationBoundary(),
                inferenceCase.providerName(),
                inferenceCase.providerName(),
                inferenceCase.providerName(),
                inferenceCase.providerType(),
                inferenceCase.providerName(),
                inferenceCase.providerName(),
                inferenceCase.validationBoundary(),
                inferenceCase.providerName(),
                inferenceCase.providerName(),
                inferenceCase.providerName(),
                inferenceCase.providerType()));
    }

    private void writeGeneratedFramework(
            Path packageRoot,
            String targets,
            String providerContracts) throws Exception {
        Path generated = packageRoot.resolve("generated-framework");
        Files.createDirectories(generated.resolve("run_profiles"));
        Files.createDirectories(generated.resolve("environment_bindings"));
        Files.createDirectories(generated.resolve("provider_contracts"));
        Files.writeString(generated.resolve("run_plan.yaml"), """
                run_profile_ref: run_profiles/ci_ephemeral.yaml
                environment_binding_ref: environment_bindings/ci_ephemeral.yaml
                execution_mode: ci_ephemeral
                target_dependencies: {}
                """);
        Files.writeString(generated.resolve("run_profiles/ci_ephemeral.yaml"), """
                profile_id: ci_ephemeral
                execution_mode: ci_ephemeral
                environment_binding_ref: environment_bindings/ci_ephemeral.yaml
                isolation_scope: target_graph
                dependency_policy: generated_target_graph
                max_duration: PT10M
                data_policy:
                  cleanup_required: true
                """);
        Files.writeString(generated.resolve("environment_bindings/ci_ephemeral.yaml"), """
                environment_id: ci_ephemeral
                environment_type: isolated
                targets:
                %s\
                """.formatted(targets.indent(2)));
        Files.writeString(generated.resolve("provider_contracts/RU-generated-job.yaml"), providerContracts);
    }

    private String generatedProviderContracts(String sections) {
        return """
                provider_contracts:
                %s\
                """.formatted(sections.indent(2));
    }

    private record InferenceCase(
            String providerName,
            String providerType,
            String validationBoundary,
            String expectedFamily) {
    }
}

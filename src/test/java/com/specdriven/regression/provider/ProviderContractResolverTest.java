package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;

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
                  adapters:
                    spring_boot_cli:
                      provider_family: file_batch
                      provider_type: shell
                      command: java -jar ${repo}/target/release-unit.jar
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings:
                    db_seed:
                      provider_family: file_batch
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
                .contains("adapter", "binding");
        assertThat(report.gaps()).isEmpty();
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
                .contains("release_units[0].provider_contracts.adapters.spring_boot_cli",
                        "release_units[0].provider_contracts.bindings.db_seed");
        assertThat(report.gaps()).extracting(ProviderContractGap::ownerAction)
                .allMatch(action -> action.contains("Add provider contract"));
    }

    @Test
    void blocksAdapterContractWithoutCommandBeforeExecution() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("""
                provider_contracts:
                  adapters:
                    spring_boot_cli:
                      provider_family: file_batch
                      provider_type: shell
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings:
                    db_seed:
                      provider_family: file_batch
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
                .contains("release_units[0].provider_contracts.adapters.spring_boot_cli.command");
        assertThat(report.gaps()).extracting(ProviderContractGap::ownerAction)
                .anyMatch(action -> action.contains("Declare executable adapter command"));
    }

    @Test
    void blocksFixtureActionWithoutFixtureProviderContract() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("""
                provider_contracts:
                  adapters:
                    spring_boot_cli:
                      provider_family: file_batch
                      provider_type: shell
                      command: java -jar ${repo}/target/release-unit.jar
                      timeout_seconds: 10
                      outputs:
                        actual_output_ref: actual/output.txt
                  bindings:
                    db_seed:
                      provider_family: file_batch
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
                    adapter: request_response
                    provider_contracts:
                      adapters:
                        request_response:
                          provider_family: request_response
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
                          provider_family: request_response
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
                    adapter: message_bus
                    provider_contracts:
                      adapters:
                        message_bus:
                          provider_family: messaging
                          provider_type: kafka
                          topic_ref: kafka://payment.events
                      bindings:
                        message_event:
                          provider_family: messaging
                          provider_type: event_payload
                          bind_as: event_payload
                      fixtures:
                        relational_db:
                          provider_family: db_fixture
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
                            .isEqualTo("release_units[0].provider_contracts.adapters.request_response");
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
                    adapter: message_bus
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
                    assertThat(gap.contractType()).isEqualTo("adapter");
                    assertThat(gap.providerName()).isEqualTo("message_bus");
                    assertThat(gap.providerFamily()).isEqualTo("messaging");
                    assertThat(gap.affectedRu()).isEqualTo("RU-payment-events");
                    assertThat(gap.capability()).isEqualTo("message_bus");
                    assertThat(gap.fieldPath())
                            .isEqualTo("release_units[0].provider_contracts.adapters.message_bus");
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
                    adapter: request_response
                    provider_contracts:
                      adapters:
                        request_response:
                          provider_family: request_response
                          provider_type: rest
                          actions:
                            submit_payment:
                              method: POST
                              path: /payments
                      bindings:
                        api_payload:
                          provider_family: request_response
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
                            .isEqualTo("release_units[0].provider_contracts.adapters.request_response.endpoint_ref");
                    assertThat(gap.ownerAction()).contains("endpoint", "RU-payment-api");
                    assertThat(gap.ownerAction()).doesNotContain("command");
                });
    }

    @Test
    void blocksSelectedContractsWithoutExplicitProviderFamilyAndType() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("""
                provider_contracts:
                  adapters:
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
                        "release_units[0].provider_contracts.adapters.spring_boot_cli.provider_family",
                        "release_units[0].provider_contracts.adapters.spring_boot_cli.provider_type",
                        "release_units[0].provider_contracts.bindings.db_seed.provider_family",
                        "release_units[0].provider_contracts.bindings.db_seed.provider_type");
        assertThat(report.gaps()).extracting(ProviderContractGap::ownerAction)
                .anyMatch(action -> action.contains("provider_family"))
                .anyMatch(action -> action.contains("provider_type"));
    }

    @Test
    void blocksUnsupportedProviderTypeBeforeExecution() throws Exception {
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
                    adapter: message_bus
                    provider_contracts:
                      adapters:
                        message_bus:
                          provider_family: messaging
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
                            .isEqualTo("release_units[0].provider_contracts.adapters.message_bus.provider_type");
                    assertThat(gap.providerFamily()).isEqualTo("messaging");
                    assertThat(gap.providerType()).isEqualTo("kafka");
                    assertThat(gap.registryStatus()).isEqualTo("unsupported");
                    assertThat(gap.ownerAction()).contains("Unsupported provider_type `kafka`");
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
                    adapter: external_runner
                    provider_contracts:
                      adapters:
                        external_runner:
                          provider_family: external_runner
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
                            .isEqualTo("release_units[0].provider_contracts.adapters.external_runner.approval_ref");
                    assertThat(gap.providerFamily()).isEqualTo("external_runner");
                    assertThat(gap.providerType()).isEqualTo("command_runner");
                    assertThat(gap.registryStatus()).isEqualTo("unapproved_escape_hatch");
                    assertThat(gap.ownerAction()).contains("approval metadata");
                });
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
                    adapter: spring_boot_cli
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
}

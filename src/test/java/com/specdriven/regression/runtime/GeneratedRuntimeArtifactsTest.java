package com.specdriven.regression.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedRuntimeArtifactsTest {

    @TempDir
    Path tempDir;

    private final GeneratedRuntimeArtifacts artifacts = new GeneratedRuntimeArtifacts();

    @Test
    void defaultProfileReportsMissingGeneratedRuntimeArtifacts() {
        GeneratedRuntimeContext context = artifacts.resolve(tempDir, "");

        assertThat(context.ready()).isFalse();
        assertThat(context.profileId()).isEqualTo("default");
        assertThat(context.targets()).isEmpty();
        assertThat(context.gaps()).extracting(GeneratedRuntimeGap::fieldPath)
                .contains(
                        "generated-framework/run_plan.yaml",
                        "generated-framework/run_profiles/default.yaml",
                        "generated-framework/environment_bindings/default.yaml",
                        "generated-framework/environment_bindings.targets");
    }

    @Test
    void nullRequestedProfileUsesDefaultProfileArtifacts() throws Exception {
        writeGeneratedFramework(
                "default",
                """
                        environment_binding_ref: environment_bindings/default.yaml
                        execution_mode: ci_ephemeral
                        """,
                """
                        profile_id: default
                        execution_mode: ci_ephemeral
                        environment_binding_ref: environment_bindings/default.yaml
                        isolation_scope: target_graph
                        dependency_policy: generated_target_graph
                        max_duration: PT5M
                        data_policy:
                          cleanup_required: true
                        """,
                """
                        targets:
                          api:
                            runner: request_response
                            provider_contract_ref: provider_contracts/api.yaml#providers.request_response
                            environment_ref: ci://api
                        """);

        GeneratedRuntimeContext context = artifacts.resolve(tempDir, null);

        assertThat(context.ready()).isTrue();
        assertThat(context.profileId()).isEqualTo("default");
        assertThat(context.executionMode()).isEqualTo("ci_ephemeral");
        assertThat(context.environmentRef()).isEqualTo("ci://api");
    }

    @Test
    void resolvesProviderContractRefsPathsAndDeclaredContractPath() throws Exception {
        Path providerContracts = tempDir.resolve("generated-framework/provider_contracts/runtime.yaml");
        Files.createDirectories(providerContracts.getParent());
        Files.writeString(providerContracts, """
                provider_contracts:
                  providers:
                    request_response:
                      contract_path: generated-framework/contracts/request_response.yaml#providers.request_response
                      provider_contract_kind: request_response
                      provider_type: rest
                      endpoint_ref: env://PAYMENT_API
                """);

        GeneratedRuntimeArtifacts.ProviderContractRef ref =
                artifacts.providerContractRef("provider_contracts/runtime.yaml#providers.request_response");
        Map<String, Object> contract = artifacts.providerContract(tempDir, ref);

        assertThat(ref.fileRef()).isEqualTo("provider_contracts/runtime.yaml");
        assertThat(ref.section()).isEqualTo("providers");
        assertThat(ref.providerName()).isEqualTo("request_response");
        GeneratedRuntimeArtifacts.ProviderContractRef fileOnlyRef =
                artifacts.providerContractRef("provider_contracts/runtime.yaml");
        assertThat(fileOnlyRef.fileRef()).isEqualTo("provider_contracts/runtime.yaml");
        assertThat(fileOnlyRef.section()).isEmpty();
        assertThat(fileOnlyRef.providerName()).isEmpty();
        assertThat(ref.with("bindings", "api_payload"))
                .isEqualTo("provider_contracts/runtime.yaml#bindings.api_payload");
        assertThat(contract).containsEntry("provider_type", "rest");
        assertThat(artifacts.contractPath("provider_contracts/runtime.yaml#providers.request_response", contract))
                .isEqualTo("generated-framework/contracts/request_response.yaml#providers.request_response");
        assertThat(artifacts.contractPath(
                "generated-framework/provider_contracts/runtime.yaml#providers.request_response",
                Map.of()))
                .isEqualTo("generated-framework/provider_contracts/runtime.yaml#providers.request_response");
        assertThat(artifacts.providerContract(tempDir, "", "providers", "request_response")).isEmpty();
        assertThat(artifacts.providerContract(tempDir, "provider_contracts/runtime.yaml", null, "request_response"))
                .isEmpty();
        assertThat(artifacts.providerContract(tempDir, "provider_contracts/runtime.yaml", "providers", null))
                .isEmpty();
        assertThat(artifacts.generatedPath(tempDir, "generated-framework/provider_contracts/runtime.yaml"))
                .isEqualTo(providerContracts.normalize());
        assertThat(artifacts.generatedPath(tempDir, null))
                .isEqualTo(tempDir.resolve("generated-framework").normalize());
    }

    @Test
    void providerContractReturnsEmptyMapForNonMapYamlArtifact() throws Exception {
        Path providerContracts = tempDir.resolve("generated-framework/provider_contracts/runtime.yaml");
        Files.createDirectories(providerContracts.getParent());
        Files.writeString(providerContracts, "[]\n");

        assertThat(artifacts.providerContract(
                tempDir,
                "provider_contracts/runtime.yaml",
                "providers",
                "request_response"))
                .isEmpty();
    }

    @Test
    void resolvesTargetsInDependencyOrderAndReportsTargetReadinessGaps() throws Exception {
        writeGeneratedFramework("""
                run_profile_ref: run_profiles/sit.yaml
                environment_binding_ref: environment_bindings/sit.yaml
                execution_mode: sit_deployed
                target_dependencies:
                  downstream:
                    - upstream
                    - target_id: optional-target
                      required: false
                    - external-target
                """, """
                profile_id: sit
                execution_mode: sit_deployed
                environment_binding_ref: environment_bindings/sit.yaml
                isolation_scope: target_graph
                dependency_policy: generated_target_graph
                max_duration: PT10M
                data_policy:
                  cleanup_required: true
                """, """
                environment_id: sit
                environment_type: shared
                targets:
                  downstream:
                    target_id: downstream
                    runner: downstream_cli
                    provider_contract_ref: provider_contracts/downstream.yaml#providers.downstream_cli
                    environment_ref: sit://downstream
                    readiness_ref: readiness/downstream.yaml
                  upstream:
                    target_id: upstream
                    runner: upstream_cli
                    provider_contract_ref: provider_contracts/upstream.yaml#providers.upstream_cli
                    environment_ref: sit://upstream
                    readiness_ref: readiness/upstream.yaml
                  broken: not-a-map
                """);

        GeneratedRuntimeContext context = artifacts.resolve(tempDir, "sit");

        assertThat(context.ready()).isFalse();
        assertThat(context.executionMode()).isEqualTo("sit_deployed");
        assertThat(context.environmentRef()).isEqualTo("sit://upstream");
        assertThat(context.targets()).extracting(GeneratedRuntimeTarget::targetId)
                .containsExactly("upstream", "downstream", "broken");
        assertThat(context.dependencies("downstream", "downstream_cli"))
                .containsExactly("upstream", "external-target");
        assertThat(context.gaps()).extracting(GeneratedRuntimeGap::fieldPath)
                .contains(
                        "generated-framework/environment_bindings.targets.broken.provider",
                        "generated-framework/environment_bindings.targets.broken.provider_contract_ref",
                        "generated-framework/environment_bindings.targets.broken.environment_ref",
                        "generated-framework/environment_bindings.targets.broken.readiness_ref");
    }

    @Test
    void reportsInvalidRunProfileFieldsBeforeExecution() throws Exception {
        writeGeneratedFramework("""
                environment_binding_ref: environment_bindings/sit.yaml
                execution_mode: sit_deployed
                """, """
                profile_id: sit
                execution_mode: unsupported_mode
                environment_binding_ref: ''
                isolation_scope: ''
                dependency_policy: generated_target_graph
                max_duration: ''
                data_policy: ''
                """, """
                targets:
                  api:
                    runner: request_response
                    provider_contract_ref: provider_contracts/api.yaml#providers.request_response
                    environment_ref: sit://api
                    readiness_ref: readiness/api.yaml
                """);

        GeneratedRuntimeContext context = artifacts.resolve(tempDir, "sit");

        assertThat(context.ready()).isFalse();
        assertThat(context.gaps()).extracting(GeneratedRuntimeGap::fieldPath)
                .contains(
                        "generated-framework/run_profiles/sit.yaml#environment_binding_ref",
                        "generated-framework/run_profiles/sit.yaml#isolation_scope",
                        "generated-framework/run_profiles/sit.yaml#max_duration",
                        "generated-framework/run_profiles/sit.yaml#data_policy",
                        "generated-framework/run_profiles/sit.yaml#execution_mode");
    }

    @Test
    void resolvesDependencyCyclesAndIgnoresNonListDependencyEntries() throws Exception {
        writeGeneratedFramework("""
                environment_binding_ref: environment_bindings/sit.yaml
                execution_mode: ci_ephemeral
                target_dependencies:
                  api:
                    - worker
                  worker:
                    - api
                  batch: not-a-list
                """, """
                profile_id: sit
                execution_mode: ci_ephemeral
                environment_binding_ref: environment_bindings/sit.yaml
                isolation_scope: target_graph
                dependency_policy: generated_target_graph
                max_duration: PT10M
                data_policy:
                  cleanup_required: true
                """, """
                targets:
                  api:
                    runner: request_response
                    provider_contract_ref: provider_contracts/api.yaml#providers.request_response
                    environment_ref: ci://api
                  worker:
                    runner: messaging
                    provider_contract_ref: provider_contracts/worker.yaml#providers.messaging
                    environment_ref: ci://worker
                  batch:
                    runner: external_runner
                    provider_contract_ref: provider_contracts/batch.yaml#providers.external_runner
                    environment_ref: ci://batch
                """);

        GeneratedRuntimeContext context = artifacts.resolve(tempDir, "sit");

        assertThat(context.ready()).isTrue();
        assertThat(context.targets()).extracting(GeneratedRuntimeTarget::targetId)
                .containsExactly("worker", "api", "batch");
        assertThat(context.dependencies("api", "request_response")).containsExactly("worker");
        assertThat(context.dependencies("batch", "external_runner")).isEmpty();
    }

    @Test
    void throwsUncheckedIoExceptionWhenGeneratedYamlCannotBeRead() throws Exception {
        Files.createDirectories(tempDir.resolve("generated-framework/provider_contracts/runtime.yaml"));

        assertThatThrownBy(() -> artifacts.providerContract(
                tempDir,
                "provider_contracts/runtime.yaml",
                "providers",
                "request_response"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to read generated runtime artifact");
    }

    private void writeGeneratedFramework(
            String runPlan,
            String runProfile,
            String environmentBinding) throws Exception {
        writeGeneratedFramework("sit", runPlan, runProfile, environmentBinding);
    }

    private void writeGeneratedFramework(
            String profileId,
            String runPlan,
            String runProfile,
            String environmentBinding) throws Exception {
        Path generated = tempDir.resolve("generated-framework");
        Files.createDirectories(generated.resolve("run_profiles"));
        Files.createDirectories(generated.resolve("environment_bindings"));
        Files.writeString(generated.resolve("run_plan.yaml"), runPlan);
        Files.writeString(generated.resolve("run_profiles/" + profileId + ".yaml"), runProfile);
        Files.writeString(generated.resolve("environment_bindings/" + profileId + ".yaml"), environmentBinding);
    }
}

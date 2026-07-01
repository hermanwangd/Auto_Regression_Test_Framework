package com.specdriven.regression.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class FrameworkPublicInterfaceContractTest {

    private static final Path CONTRACT_ROOT = Path.of("docs/02-architecture/contracts");
    private static final Path PUBLIC_INTERFACE = CONTRACT_ROOT.resolve("framework_usage_interface.v0.2.md");

    @Test
    @DisplayName("FWK-013 | public interface contract freezes runtime invocation and artifact surfaces")
    void FWK_013_publicInterfaceContractDeclaresStableRuntimeAndArtifactSurfaces() throws Exception {
        String contract = Files.readString(PUBLIC_INTERFACE);

        assertThat(contract)
                .contains("# Framework Public Interface v0.2")
                .contains("## Stable Interface Families")
                .contains("## Invocation Interface: Runtime Commands")
                .contains("## DSL and Test Definition Interface")
                .contains("## Run, Environment, and Provider Configuration Interface")
                .contains("## Stable Input Artifact Locations")
                .contains("## Stable Output Artifact Locations")
                .contains("## Next-Stage Support Commands")
                .contains("`regress check-rp`")
                .contains("`regress run`")
                .contains("`regress report`")
                .contains("`--rp-id <rp-id>`")
                .contains("`--root <product-repo>`")
                .contains("`--strict-schema`")
                .contains("`--env <profile>`")
                .contains("`--dry-run`")
                .contains("`--test-case <test-case-id>`")
                .contains("`--suite <suite-id>`")
                .contains("`--tag <tag>`")
                .contains("`--batch-id <batch-id>`")
                .contains("`--format text`")
                .contains("operation-level `inputs` maps")
                .contains("`data.<name>.ref`")
                .contains("`generated-framework/suite_manifest.yaml`")
                .contains("`generated-framework/run_plan.yaml`")
                .contains("`generated-framework/run_profiles/`")
                .contains("`generated-framework/environment_bindings/`")
                .contains("framework built-in Provider Contract catalog")
                .contains("`generated-framework/traceability_map.yaml`")
                .contains("`regress init-product-repo`")
                .contains("`regress check-readiness`")
                .contains("`regress init-rp`")
                .contains("`regress generate-tests`")
                .contains("`regress draft-expected-results`")
                .contains("framework runtime consumes")
                .contains("must not infer Product/RP/RU topology");
    }

    @Test
    @DisplayName("FWK-013 | referenced YAML and plugin contract artifacts exist and parse")
    void FWK_013_referencedContractArtifactsExistAndParse() throws Exception {
        for (String file : yamlContracts()) {
            Path path = CONTRACT_ROOT.resolve(file);
            assertThat(path).isRegularFile();
            assertThat(loadYaml(path))
                    .as(file)
                    .isNotNull();
        }

        for (String file : markdownContracts()) {
            Path path = CONTRACT_ROOT.resolve(file);
            assertThat(path).isRegularFile();
            assertThat(Files.readString(path))
                    .as(file)
                    .contains("# ")
                    .contains("Status:");
        }
    }

    private List<String> yamlContracts() {
        return List.of(
                "test_case_dsl.v0.2.schema.yaml",
                "run_profile.v0.2.schema.yaml",
                "environment_binding.v0.2.schema.yaml",
                "suite_manifest.v0.2.schema.yaml",
                "provider_contract.v0.2.schema.yaml",
                "provider_capability_registry.v0.2.yaml",
                "result.v0.2.schema.yaml",
                "evidence.v0.2.schema.yaml");
    }

    private List<String> markdownContracts() {
        return List.of(
                "runner_plugin_contract.md",
                "provider_plugin_contract.md",
                "verify_plugin_contract.md");
    }

    private Object loadYaml(Path path) throws IOException {
        return new Yaml().load(Files.readString(path));
    }
}

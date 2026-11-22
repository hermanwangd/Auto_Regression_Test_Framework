package com.specdriven.regression.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class FrameworkPublicInterfaceContractTest {

    private static final Path CONTRACT_ROOT = Path.of("docs/02-architecture/contracts");
    private static final Path PUBLIC_INTERFACE = CONTRACT_ROOT.resolve("framework_usage_interface.v0.2.md");
    private static final Path USER_GUIDE = Path.of("docs/09-operations/test_framework_user_guide.md");
    private static final Path TEST_PLAN = Path.of("docs/07-validation-evidence/07_regression_test_plan.md");

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
                .contains("## Non-Runtime Support Boundary")
                .contains("`regress run`")
                .contains("`regress report`")
                .contains("`--dry-run`")
                .contains("`--test-case <test-case-id>`")
                .contains("`--suite <suite_manifest_path>`")
                .contains("`--profile <env_profile_id>`")
                .contains("`--tag <tag>`")
                .contains("`--format text|yaml|json`")
                .contains("`regress validate-evidence`")
                .contains("`regress report --format json` is a v0.3 public report contract")
                .contains("operation-level `inputs` maps")
                .contains("`data.<name>.ref`")
                .contains("`generated-framework/suite_manifest.yaml`")
                .contains("`generated-framework/run_plan.yaml`")
                .contains("`generated-framework/run_profiles/`")
                .contains("`generated-framework/environment_bindings/`")
                .contains("framework built-in Provider Contract catalog")
                .contains("`generated-framework/traceability_map.yaml`")
                .contains("framework runtime consumes")
                .contains("must not infer Product/RP/RU topology");
        assertThat(contract)
                .doesNotContain("regress run --root <product-repo> --rp-id <rp-id> --env <profile> --dry-run")
                .doesNotContain("regress report --root <product-repo> --rp-id <rp-id> --batch-id")
                .doesNotContain("LEGACY_RP_MODE_DEPRECATED")
                .doesNotContain("`regress init-product-repo`")
                .doesNotContain("`regress check-readiness`")
                .doesNotContain("`regress init-rp`")
                .doesNotContain("`regress check-rp`")
                .doesNotContain("`regress generate-tests`")
                .doesNotContain("`regress draft-expected-results`");
    }

    @Test
    @DisplayName("FWK-013 | user docs keep v0.3 runtime release interface suite-mode only")
    void FWK_013_userDocsKeepRuntimeReleaseInterfaceSuiteModeOnly() throws Exception {
        String userGuide = Files.readString(USER_GUIDE);
        String testPlan = Files.readString(TEST_PLAN);

        assertThat(userGuide)
                .contains("The runtime public interface is suite-mode")
                .contains("Product/RP tooling must translate owner-authored artifacts into suite-mode")
                .contains("artifacts before invoking the framework runtime")
                .contains("Direct Product/RP runtime")
                .contains("orchestration is not part of the framework public interface")
                .contains("Product/RP-specific report forms are outside the v0.3 framework runtime public interface");
        assertThat(testPlan)
                .contains("run --suite <suite_manifest>")
                .contains("report --result <generated_result_json>")
                .contains("Product/RP runtime orchestration is outside the v0.3 framework public interface")
                .doesNotContain("report --batch-id")
                .doesNotContain("LEGACY_RP_MODE_DEPRECATED")
                .doesNotContain("requested `--env` Env_Profile")
                .doesNotContain("`regress run` supports Product Repo mode")
                .doesNotContain("`regress report` supports Product Repo mode");
    }

    @Test
    @DisplayName("FWK-013 | provider capability samples rely on the built-in provider contract catalog")
    void FWK_013_providerCapabilitySamplesDoNotCopyBuiltInProviderContracts() throws Exception {
        Path samples = Path.of("samples/20-provider-capability-p0");
        try (var paths = Files.walk(samples)) {
            List<Path> suiteLocalBuiltInContracts = paths
                    .filter(path -> path.toString().contains("/provider_contracts/"))
                    .toList();

            assertThat(suiteLocalBuiltInContracts)
                    .as("Provider capability runtime samples must use the framework built-in Provider Contract catalog; suite-local contracts are custom/snapshot opt-in only.")
                    .isEmpty();
        }

        assertThat(Files.exists(Path.of("samples/provider_contracts")))
                .as("Use docs/02-architecture/contracts/provider-contracts as the canonical built-in Provider Contract catalog; do not keep a second samples/provider_contracts catalog.")
                .isFalse();
    }

    @Test
    @DisplayName("FWK-013 | user guide declares standard evidence index and provider evidence refs")
    void FWK_013_userGuideDeclaresStandardEvidenceIndexContract() throws Exception {
        String userGuide = Files.readString(USER_GUIDE);
        String evidenceContract = Files.readString(CONTRACT_ROOT.resolve("evidence_folder_structure.v0.2.md"));

        for (String text : List.of(userGuide, evidenceContract)) {
            assertThat(text)
                    .contains("evidence_id:")
                    .contains("file_path:")
                    .contains("masking_applied:")
                    .contains("provider_evidence_refs[]")
                    .contains("evidence_refs[]");
        }
        assertThat(userGuide)
                .contains("Do not publish legacy compact entries")
                .contains("Framework logs, batch summaries, assertion diffs, and expected artifacts belong in `evidence_refs[]` only.");
        assertThat(evidenceContract)
                .contains("Legacy compact entries using `ref:` plus `masked:` are not valid");
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

    @Test
    @DisplayName("FWK-013 | provider runtime modes do not use execution mode names")
    void FWK_013_providerRuntimeModesUseProviderRuntimeTaxonomy() throws Exception {
        List<String> invalidModes = new ArrayList<>();
        for (Path path : runtimeContractArtifacts()) {
            Object document = loadYaml(path);
            collectInvalidRuntimeModes(path.toString(), document, invalidModes);
        }

        assertThat(invalidModes)
                .as("Use execution_mode for local/ci/sit/preprod; runtime_mode must use native/mock/stub/ephemeral.")
                .isEmpty();
    }

    @Test
    @DisplayName("FWK-013 | provider registry required binding keys match Provider Contracts")
    void FWK_013_providerRegistryRequiredBindingKeysMatchContracts() throws Exception {
        Map<?, ?> registry = map(loadYaml(CONTRACT_ROOT.resolve("provider_capability_registry.v0.2.yaml")));
        Map<?, ?> providerTypes = map(registry.get("provider_types"));
        List<String> mismatches = new ArrayList<>();

        for (Map.Entry<?, ?> entry : providerTypes.entrySet()) {
            String providerType = String.valueOf(entry.getKey());
            Map<?, ?> registryEntry = map(entry.getValue());
            String contractRef = String.valueOf(registryEntry.get("contract_ref"));
            Map<?, ?> contract = map(loadYaml(CONTRACT_ROOT.resolve(contractRef)));
            Set<String> registryRequired = Set.copyOf(strings(registryEntry.get("required_binding_keys")));
            Set<String> contractRequired = requiredBindingKeys(contract);
            if (!registryRequired.equals(contractRequired)) {
                mismatches.add(providerType + ": registry=" + registryRequired + ", contract=" + contractRequired);
            }
        }

        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("FWK-013 | provider registry support_status values are cataloged")
    void FWK_013_providerRegistrySupportStatusValuesAreCataloged() throws Exception {
        Map<?, ?> registry = map(loadYaml(CONTRACT_ROOT.resolve("provider_capability_registry.v0.2.yaml")));
        Set<String> catalog = Set.copyOf(map(registry.get("support_status_catalog")).keySet().stream()
                .map(String::valueOf)
                .toList());
        List<String> uncataloged = new ArrayList<>();

        for (Map.Entry<?, ?> entry : map(registry.get("provider_types")).entrySet()) {
            String providerType = String.valueOf(entry.getKey());
            String supportStatus = String.valueOf(map(entry.getValue()).get("support_status"));
            if (!catalog.contains(supportStatus)) {
                uncataloged.add(providerType + ": " + supportStatus);
            }
        }

        assertThat(uncataloged).isEmpty();
    }

    @Test
    @DisplayName("FWK-013 | provider registry distinguishes contract runtime vocabulary from executable runtime modes")
    void FWK_013_providerRegistryRuntimeModesDistinguishContractVocabularyFromExecutableSupport() throws Exception {
        Map<?, ?> registry = map(loadYaml(CONTRACT_ROOT.resolve("provider_capability_registry.v0.2.yaml")));
        Map<?, ?> providerTypes = map(registry.get("provider_types"));
        List<String> mismatches = new ArrayList<>();

        for (Map.Entry<?, ?> entry : providerTypes.entrySet()) {
            String providerType = String.valueOf(entry.getKey());
            Map<?, ?> registryEntry = map(entry.getValue());
            Map<?, ?> contract = map(loadYaml(CONTRACT_ROOT.resolve(String.valueOf(registryEntry.get("contract_ref")))));
            Set<String> contractRuntimeModes = Set.copyOf(strings(contract.get("runtime_modes")));
            Set<String> supportedRuntimeModes = Set.copyOf(strings(registryEntry.get("supported_runtime_modes")));
            Set<String> executableRuntimeModes = Set.copyOf(strings(contract.get("executable_runtime_modes")));
            Set<String> contractOnlyRuntimeModes = Set.copyOf(strings(contract.get("contract_only_runtime_modes")));

            if (!contractRuntimeModes.containsAll(supportedRuntimeModes)) {
                mismatches.add(providerType + ": supported_runtime_modes not in Provider Contract runtime_modes");
            }
            if (!executableRuntimeModes.isEmpty() && !supportedRuntimeModes.equals(executableRuntimeModes)) {
                mismatches.add(providerType + ": registry supported_runtime_modes must equal contract executable_runtime_modes");
            }
            if (!contractRuntimeModes.containsAll(executableRuntimeModes)) {
                mismatches.add(providerType + ": executable_runtime_modes not in Provider Contract runtime_modes");
            }
            if (!contractRuntimeModes.containsAll(contractOnlyRuntimeModes)) {
                mismatches.add(providerType + ": contract_only_runtime_modes not in Provider Contract runtime_modes");
            }
        }

        assertThat(mismatches).isEmpty();
        for (String providerType : List.of("kafka", "ibm_mq")) {
            Map<?, ?> registryEntry = map(providerTypes.get(providerType));
            Map<?, ?> contract = map(loadYaml(CONTRACT_ROOT.resolve(String.valueOf(registryEntry.get("contract_ref")))));
            assertThat(strings(registryEntry.get("supported_runtime_modes"))).containsExactly("mock", "native");
            assertThat(strings(contract.get("executable_runtime_modes"))).containsExactly("mock", "native");
            assertThat(strings(contract.get("contract_only_runtime_modes"))).containsExactly("ephemeral");
        }
    }

    @Test
    @DisplayName("FWK-013 | WireMock-backed mock providers distinguish protocol contract from runtime implementation")
    void FWK_013_wireMockBackedMockProvidersDistinguishProtocolFromImplementation() throws Exception {
        Map<?, ?> registry = map(loadYaml(CONTRACT_ROOT.resolve("provider_capability_registry.v0.2.yaml")));
        Map<?, ?> providerTypes = map(registry.get("provider_types"));

        assertWireMockBackedProvider(providerTypes, "wiremock_http_mock", "http");
        assertWireMockBackedProvider(providerTypes, "soap_mock", "soap");
        assertWireMockBackedProvider(providerTypes, "grpc_mock", "grpc");

        Map<?, ?> httpEntry = map(providerTypes.get("wiremock_http_mock"));
        assertThat(String.valueOf(httpEntry.get("canonical_provider_type"))).isEqualTo("http_mock");
        assertThat(strings(httpEntry.get("compatibility_aliases"))).contains("wiremock_http_mock");
    }

    @Test
    @DisplayName("FWK-013 | wiremock_http_mock external base_url is WireMock Admin API, not generic REST")
    void FWK_013_wireMockHttpMockExternalBaseUrlHasNarrowAdminApiBoundary() throws Exception {
        Path contractPath = CONTRACT_ROOT.resolve("provider-contracts/wiremock_http_mock.yaml");
        String contractText = Files.readString(contractPath);
        String userGuide = Files.readString(USER_GUIDE);
        String supportMatrix = Files.readString(Path.of("docs/09-operations/provider_support_matrix.md"));

        assertThat(contractText)
                .contains("WireMock-compatible")
                .contains("WireMock Admin API")
                .contains("Do not use for a generic external REST/SUT endpoint")
                .contains("rest_client.base_url")
                .contains("must not start or stop the WireMock process");
        assertThat(userGuide)
                .contains("`wiremock_http_mock.base_url` has one narrow meaning")
                .contains("not a generic external REST endpoint binding")
                .contains("`connect_mock` is the preferred operation name for external mode")
                .contains("Generic project-provisioned HTTP endpoints")
                .contains("must be modeled as `rest_client.base_url`");
        assertThat(supportMatrix)
                .contains("External `base_url` means an owner-provisioned WireMock-compatible Admin API endpoint")
                .contains("not a generic REST/SUT endpoint");
    }

    @Test
    @DisplayName("FWK-013 | suite manifest public docs expose tests and child suites, not suite type discriminators")
    void FWK_013_suiteManifestDocsDoNotExposeSuiteTypeDiscriminator() throws Exception {
        for (Path path : List.of(
                Path.of("schemas/suite_manifest.v0.2.schema.yaml"),
                CONTRACT_ROOT.resolve("suite_manifest.v0.2.schema.yaml"),
                PUBLIC_INTERFACE,
                Path.of("docs/09-operations/test_framework_user_guide.md"))) {
            String text = Files.readString(path);
            assertThat(text)
                    .as(path.toString())
                    .contains("tests[]")
                    .doesNotContain("suite_type");
        }
    }

    private void assertWireMockBackedProvider(Map<?, ?> providerTypes, String providerType, String protocol)
            throws IOException {
        Map<?, ?> registryEntry = map(providerTypes.get(providerType));
        assertThat(registryEntry).as(providerType).isNotEmpty();
        assertThat(String.valueOf(registryEntry.get("provider_role"))).isEqualTo("mock_server");
        assertThat(String.valueOf(registryEntry.get("protocol"))).isEqualTo(protocol);
        assertThat(String.valueOf(registryEntry.get("runtime_implementation"))).isEqualTo("wiremock");

        Map<?, ?> contract = map(loadYaml(CONTRACT_ROOT.resolve(String.valueOf(registryEntry.get("contract_ref")))));
        assertThat(String.valueOf(contract.get("provider_role"))).isEqualTo("mock_server");
        assertThat(String.valueOf(contract.get("protocol"))).isEqualTo(protocol);
        assertThat(String.valueOf(contract.get("runtime_implementation"))).isEqualTo("wiremock");
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
                "evidence_folder_structure.v0.2.md",
                "runner_plugin_contract.md",
                "provider_plugin_contract.md",
                "verify_plugin_contract.md");
    }

    private Object loadYaml(Path path) throws IOException {
        return new Yaml().load(Files.readString(path));
    }

    private Set<String> requiredBindingKeys(Map<?, ?> contract) {
        Map<?, ?> bindingKeys = map(contract.get("binding_keys"));
        Set<String> required = new java.util.LinkedHashSet<>();
        for (Map.Entry<?, ?> entry : bindingKeys.entrySet()) {
            Map<?, ?> spec = map(entry.getValue());
            if (Boolean.TRUE.equals(spec.get("required"))) {
                required.add(String.valueOf(entry.getKey()));
            }
        }
        return required;
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream().map(String::valueOf).toList();
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private List<Path> runtimeContractArtifacts() throws IOException {
        List<Path> roots = List.of(
                CONTRACT_ROOT.resolve("provider-contracts"),
                Path.of("samples"));
        List<Path> paths = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (var stream = Files.walk(root)) {
                stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                        .filter(path -> path.toString().contains("provider-contracts")
                                || path.toString().contains("provider_contracts")
                                || path.toString().contains("provider_instances")
                                || path.toString().contains("env_profiles")
                                || path.toString().contains("environment_bindings"))
                        .sorted()
                        .forEach(paths::add);
            }
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    private void collectInvalidRuntimeModes(String path, Object value, List<String> invalidModes) {
        Set<String> executionModes = Set.of("local", "ci", "sit", "preprod");
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object child = entry.getValue();
                if ("runtime_mode".equals(key) && executionModes.contains(String.valueOf(child))) {
                    invalidModes.add(path + ": runtime_mode=" + child);
                }
                if (Set.of("runtime_modes", "allowed_runtime_modes", "supported_runtime_modes").contains(key)
                        && child instanceof Collection<?> modes) {
                    for (Object mode : modes) {
                        if (executionModes.contains(String.valueOf(mode))) {
                            invalidModes.add(path + ": " + key + " contains " + mode);
                        }
                    }
                }
                collectInvalidRuntimeModes(path, child, invalidModes);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectInvalidRuntimeModes(path, item, invalidModes);
            }
        }
    }
}

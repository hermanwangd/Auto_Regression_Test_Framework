package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import org.junit.jupiter.api.Test;

class V03ProviderContractCatalogTest {
    private final V03ProviderContractCatalog catalog = new V03ProviderContractCatalog();

    @Test
    void loadsBundledV03ContractsWithTypedOperationAndEvidenceMetadata() {
        Map<String, V03ProviderContract> contracts = catalog.load(Path.of(
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock"));

        assertThat(contracts).containsKeys("rest_client.v0.3", "http_mock.v0.3", "jdbc.v0.3");
        assertThat(contracts.get("rest_client.v0.3").runtimeModes()).contains("native", "mock");
        assertThat(contracts.get("rest_client.v0.3").operations().get("http_request").requiredInputs())
                .contains("request.method", "request.path");
        assertThat(contracts.get("sample_fake_provider.v0.3").operations().get("setup_fixture").allowedPhases())
                .containsExactly("setup");
        assertThat(contracts.get("http_mock.v0.3").bindableOutputs()).containsExactly("base_url");
        assertThat(contracts.get("jdbc.v0.3").failureCodes()).contains("DB_CONNECTION_FAILED");
    }

    @Test
    void rejectsUnknownTypedContractVocabulary() {
        assertThatThrownBy(() -> V03ValueType.parse("decimal128"))
                .hasMessageContaining("invalid_value_type");
        assertThatThrownBy(() -> V03ReferenceKind.parse("runtime_memory"))
                .hasMessageContaining("invalid_reference_kind");
        assertThatThrownBy(() -> V03Sensitivity.parse("internal_only"))
                .hasMessageContaining("invalid_sensitivity");
    }

    @Test
    void requiresExplicitTypedMetadataInsteadOfPermissiveDefaults() {
        assertThatThrownBy(() -> V03ValueType.parse(""))
                .hasMessageContaining("missing_value_type");
        assertThatThrownBy(() -> V03Sensitivity.parse(""))
                .hasMessageContaining("missing_sensitivity");
    }

    @Test
    void keepsTheV02CompatibilityListsDerivedFromTypedV03OperationMaps() throws Exception {
        Path contracts = Path.of("docs/02-architecture/contracts/provider-contracts");
        try (var files = Files.list(contracts)) {
            for (Path path : files.filter(file -> file.getFileName().toString().endsWith("_v0_3.yaml")).toList()) {
                Map<String, Object> document = map(new Yaml().load(Files.readString(path)));
                for (Map.Entry<String, Object> entry : map(document.get("operations")).entrySet()) {
                    Map<String, Object> operation = map(entry.getValue());
                    assertThat(operation).as("%s operation %s", path, entry.getKey())
                            .containsKeys("inputs", "outputs", "allowed_inputs", "required_inputs", "output_refs");
                    Map<String, Object> inputs = map(operation.get("inputs"));
                    Map<String, Object> outputs = map(operation.get("outputs"));
                    assertThat(strings(operation.get("allowed_inputs")))
                            .containsExactlyElementsOf(inputs.keySet());
                    assertThat(strings(operation.get("required_inputs"))).containsExactlyElementsOf(inputs.entrySet().stream()
                            .filter(input -> Boolean.parseBoolean(String.valueOf(map(input.getValue()).get("required"))))
                            .map(Map.Entry::getKey).toList());
                    assertThat(strings(operation.get("output_refs"))).containsExactlyElementsOf(outputs.keySet());
                }
            }
        }
    }

    private Map<String, Object> map(Object value) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (value instanceof Map<?, ?> values) {
            values.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> items) {
            items.forEach(item -> result.add(String.valueOf(item)));
        }
        return result;
    }
}

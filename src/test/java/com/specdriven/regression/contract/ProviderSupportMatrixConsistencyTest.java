package com.specdriven.regression.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ProviderSupportMatrixConsistencyTest {

    private static final Path REGISTRY =
            Path.of("docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml");
    private static final Path SUPPORT_MATRIX = Path.of("docs/09-operations/provider_support_matrix.md");
    private static final Set<String> SUPPORT_STATUS_VALUES =
            Set.of("supported", "contract_only", "deprecated", "unsupported");

    @Test
    void supportMatrixUsesProviderLevelSupportStatusRows() throws Exception {
        String matrix = Files.readString(SUPPORT_MATRIX);

        assertThat(matrix)
                .contains("| Provider Type | support_status |")
                .doesNotContain("| Provider Type | Native | Mock | Ephemeral |")
                .doesNotContain("production-ready")
                .doesNotContain("framework-verification-only")
                .doesNotContain("escape-hatch");
    }

    @Test
    void supportMatrixMatchesRegistrySupportStatus() throws Exception {
        Map<String, String> registry = registrySupportStatuses();
        Map<String, String> matrix = matrixSupportStatuses();

        assertThat(matrix).containsExactlyInAnyOrderEntriesOf(registry);
        assertThat(matrix.values()).allSatisfy(status -> assertThat(status).isIn(SUPPORT_STATUS_VALUES));
        assertThat(matrix.get("kafka_messaging")).isEqualTo("deprecated");
    }

    private Map<String, String> registrySupportStatuses() throws IOException {
        Map<?, ?> root = map(new Yaml().load(Files.readString(REGISTRY)));
        Map<String, String> statuses = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map(root.get("provider_types")).entrySet()) {
            statuses.put(String.valueOf(entry.getKey()), String.valueOf(map(entry.getValue()).get("support_status")));
        }
        return statuses;
    }

    private Map<String, String> matrixSupportStatuses() throws IOException {
        Map<String, String> statuses = new LinkedHashMap<>();
        for (String line : Files.readString(SUPPORT_MATRIX).lines().toList()) {
            if (!line.startsWith("| `")) {
                continue;
            }
            String[] columns = line.split("\\|");
            if (columns.length < 3) {
                continue;
            }
            String providerType = unquote(columns[1].trim());
            String supportStatus = unquote(columns[2].trim());
            if (!providerType.isBlank()) {
                statuses.put(providerType, supportStatus);
            }
        }
        return statuses;
    }

    private String unquote(String value) {
        if (value.startsWith("`") && value.endsWith("`")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }
}

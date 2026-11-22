package com.specdriven.regression.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class FrameworkProviderContractCatalogTest {

    private static final String RESOURCE_ROOT = "docs/02-architecture/contracts/provider-contracts";

    @Test
    void generatedBundledProviderContractIndexTracksAllProviderContracts() throws IOException {
        List<String> expected = yamlFiles(Path.of(RESOURCE_ROOT));
        Path sourceIndex = Path.of(RESOURCE_ROOT, "provider-contracts.index");
        URL index = Thread.currentThread().getContextClassLoader()
                .getResource(RESOURCE_ROOT + "/provider-contracts.index");

        assertThat(sourceIndex).exists();
        assertIndexTracksProviderContracts(Files.readString(sourceIndex), expected);
        assertThat(index).isNotNull();
        String indexText = new String(index.openStream().readAllBytes(), StandardCharsets.UTF_8);
        assertIndexTracksProviderContracts(indexText, expected);
    }

    @Test
    void providerContractIndexIncludesDslV03ContractIds() throws IOException {
        String sourceIndex = Files.readString(Path.of(RESOURCE_ROOT, "provider-contracts.index"));

        assertThat(sourceIndex)
                .contains("http_mock_v0_3.yaml")
                .contains("common_verify_v0_3.yaml")
                .contains("grpc_client_v0_3.yaml")
                .contains("grpc_mock_v0_3.yaml")
                .contains("polling_observer_v0_3.yaml")
                .contains("rest_client_v0_3.yaml")
                .contains("sample_fake_provider_v0_3.yaml")
                .contains("soap_mock_v0_3.yaml");
    }

    private void assertIndexTracksProviderContracts(String indexText, List<String> expected) {
        List<String> rawLines = indexText.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .toList();
        List<String> actual = rawLines.stream()
                .map(line -> Path.of(line.replace('\\', '/')).getFileName().toString())
                .sorted()
                .toList();

        assertThat(rawLines)
                .allSatisfy(line -> assertThat(line)
                        .doesNotContain("/")
                        .doesNotContain("\\"));
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    private List<String> yamlFiles(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }
}

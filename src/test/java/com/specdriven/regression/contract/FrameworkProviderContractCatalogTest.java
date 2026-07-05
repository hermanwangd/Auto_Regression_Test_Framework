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
        URL index = Thread.currentThread().getContextClassLoader()
                .getResource(RESOURCE_ROOT + "/provider-contracts.index");

        assertThat(index).isNotNull();
        String indexText = new String(index.openStream().readAllBytes(), StandardCharsets.UTF_8);
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

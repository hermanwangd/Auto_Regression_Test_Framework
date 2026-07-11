package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
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
        assertThat(contracts.get("http_mock.v0.3").bindableOutputs()).containsExactly("base_url");
        assertThat(contracts.get("jdbc.v0.3").failureCodes()).contains("DB_CONNECTION_FAILED");
    }
}

package com.specdriven.regression.provider.wiremock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WireMockHttpMockProviderRuntimeTest {
    @TempDir Path temp;

    @Test
    void managedConfigurationBindsLoopbackAndDisallowsProxyTargets() {
        var config = WireMockHttpMockProviderRuntime.managedConfiguration(temp);
        assertThat(config.bindAddress()).isEqualTo("127.0.0.1");
        assertThat(config.browserProxyingEnabled()).isFalse();
        assertThat(config.getProxyTargetRules().isAllowedAll()).isFalse();
        assertThat(config.getProxyTargetRules().isAllowed("127.0.0.1")).isFalse();
        assertThat(config.getProxyTargetRules().isAllowed("fixture.example")).isFalse();
    }

    @Test
    void externalOriginsRequireAProcessBindingForRemoteRoots() throws Exception {
        String remote = "https://mock.example:8443";
        assertThat(WireMockHttpMockProviderRuntime.externalOrigin(remote + "/", remote)).isEqualTo(URI.create(remote));
        assertThat(WireMockHttpMockProviderRuntime.externalOrigin("http://localhost:8080", null))
                .isEqualTo(URI.create("http://localhost:8080"));
        assertThatThrownBy(() -> WireMockHttpMockProviderRuntime.externalOrigin(remote, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireMockHttpMockProviderRuntime.externalOrigin(remote, "https://mock.example"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireMockHttpMockProviderRuntime.externalOrigin(remote + "/api", remote))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applicationRequestPathsUseOnlyTheirConfiguredOrigin() throws Exception {
        URI origin = URI.create("http://localhost:8080");
        assertThat(WireMockHttpMockProviderRuntime.requestUri(origin, "/items/blue%20box?page=2"))
                .isEqualTo(URI.create("http://localhost:8080/items/blue%20box?page=2"));
        for (String invalid : List.of("items", "https://mock.example/items", "//mock.example/items",
                "/items#part", "/__admin/requests", "/items/../ready")) {
            assertThatThrownBy(() -> WireMockHttpMockProviderRuntime.requestUri(origin, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void validatesStaticMappingsBeforeReplacingPreviouslyLoadedState() throws Exception {
        var runtime = new WireMockHttpMockProviderRuntime();
        var context = new ProviderExecutionContext("fixture", "http_mock", "local", "mock", temp,
                temp.resolve("run"), Map.of(), Map.of(), Map.of("port_strategy", "dynamic"));
        Files.writeString(temp.resolve("ready.json"), """
                {"request":{"method":"POST","urlPath":"/ready"},"response":{"status":201,"body":"ready"}}
                """);
        Files.writeString(temp.resolve("incomplete.json"), "{\"request\":{},\"sample_marker\":\"private-fixture-label\"}");
        Files.writeString(temp.resolve("input.yaml"), """
                method: POST
                path: /ready?view=compact
                body: {label: private-fixture-label}
                """);
        Files.writeString(temp.resolve("wrong.yaml"), """
                method: POST
                path: /ready?view=compact
                body: {label: different}
                """);
        try {
            ProviderOperationResult loaded = runtime.execute(context,
                    operation("load_stubs", Map.of("mock.mappings_ref", "ready.json")));
            assertThat(loaded.passed()).as("%s", loaded.failure()).isTrue();
            ProviderOperationResult invalid = runtime.execute(context,
                    operation("load_stubs", Map.of("mock.mappings_ref", "incomplete.json")));
            assertThat(invalid.passed()).isFalse();
            assertThat(invalid.failure().reason()).isEqualTo("WireMock stub mapping is invalid.");
            assertThat(Files.readString(context.runDir().resolve("provider-evidence/wiremock/failure_detail.yaml")))
                    .doesNotContain("private-fixture-label", temp.toString());

            ProviderOperationResult request = runtime.execute(context, operation("send_http_request",
                    Map.of("http.request_body", "input.yaml", "mock.request_filter", "input.yaml")));
            assertThat(request.passed()).as("%s", request.failure()).isTrue();
            assertThat(request.outputs()).containsEntry("matched_count", 1).containsEntry("http_status", 201);
            assertThat(runtime.observedRequestBodyMatches(temp, "input.yaml")).isTrue();
            assertThat(runtime.observedRequestBodyMatches(temp, "wrong.yaml")).isFalse();
            String journal = Files.readString(context.runDir().resolve("provider-evidence/wiremock/request_journal.json"));
            assertThat(journal).contains("/ready").doesNotContain("private-fixture-label", "view=compact", "headers", "body");
        } finally {
            assertThat(runtime.execute(context, operation("reset_mock", Map.of())).passed()).isTrue();
        }
    }

    @Test
    void rejectsNonStaticResponseOptionsBeforeUsingWireMock() {
        assertThatThrownBy(() -> WireMockHttpMockProviderRuntime.staticMapping("""
                {"request":{"method":"GET","url":"/ready"},"response":{"body":{}}}
                """, false)).isInstanceOf(IllegalArgumentException.class);
        for (String property : List.of("proxyBaseUrl", "transformers", "bodyFileName")) {
            String mapping = "{\"request\":{\"method\":\"GET\",\"url\":\"/ready\"},\"response\":{\"" + property + "\":null}}";
            assertThatThrownBy(() -> WireMockHttpMockProviderRuntime.staticMapping(mapping, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void literalTemplateExamplesAreLocalOnly() throws Exception {
        String json = """
                {"request":{"method":"GET","url":"/ready"},"response":{"jsonBody":{"{{label}}":"example"}}}
                """;
        assertThat(WireMockHttpMockProviderRuntime.staticMapping(json, false)).isNotNull();
        assertThatThrownBy(() -> WireMockHttpMockProviderRuntime.staticMapping(json, true))
                .isInstanceOf(IllegalArgumentException.class);
        String encoded = """
                {"request":{"method":"GET","url":"/ready"},"response":{"base64Body":"e3tsYWJlbH19"}}
                """;
        assertThatThrownBy(() -> WireMockHttpMockProviderRuntime.staticMapping(encoded, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ProviderOperationRequest operation(String name, Map<String, String> inputs) {
        var parameters = inputs.entrySet().stream()
                .map(entry -> Map.<String, Object>of("bind_as", entry.getKey(), "ref", entry.getValue())).toList();
        return new ProviderOperationRequest(name, parameters, Map.of());
    }
}

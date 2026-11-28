package com.specdriven.regression.contract.v03.ref;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specdriven.regression.contract.v03.V03ProducedOutput;
import com.specdriven.regression.contract.v03.V03Sensitivity;
import com.specdriven.regression.contract.v03.V03StepOutputKey;
import com.specdriven.regression.contract.v03.V03ValueType;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V03JsonMatchSourceTest {
    @TempDir Path root;
    private final V03ReferenceResolver resolver = new V03ReferenceResolver();

    @Test
    void decodesSerializedResponseDocumentsOnceAndPreservesOtherStepStrings() {
        var body = "{\"text\":\"[1]\",\"amount\":0.12345678901234567891}";
        assertThat(value("rest_client.v0.3", "response.body", body, "/text")).isEqualTo("[1]");
        assertThat(value("rest_client.v0.3", "response.body", body, "/amount"))
                .isEqualTo(new BigDecimal("0.12345678901234567891"));
        assertThat(value("grpc_client.v0.3", "response.message", "{\"id\":1}", "")).isEqualTo(Map.of("id", 1));
        assertThat(value("rest_client.v0.3", "response.body", "\"null\"", "")).isEqualTo("null");
        assertThat(value("rest_client.v0.3", "response.body", "null", "")).isNull();
        assertThat(value("other.v0.3", "response.body", body, "")).isEqualTo(body);
        assertThat(value("rest_client.v0.3", "response.headers", body, "")).isEqualTo(body);
    }

    @Test
    void failsOnMalformedOrMultipleRawResponseDocuments() {
        for (String invalid : new String[]{"id: 1", "{\"id\":1} true", "{\"id\":1,\"id\":2}", ""}) {
            assertThatThrownBy(() -> value("rest_client.v0.3", "response.body", invalid, ""))
                    .hasMessageStartingWith("invalid_response_json:");
        }
    }

    private Object value(String contract, String name, String text, String pointer) {
        var output = new V03ProducedOutput("TC", "read", "service", contract, "read", name,
                V03ValueType.ANY, V03Sensitivity.PUBLIC, false, text);
        var context = new V03ReferenceResolutionContext(root, Map.of(), "TC",
                Map.of(new V03StepOutputKey("TC", "read", name), output), Map.of(), Map.of());
        return resolver.resolveJsonMatchValue(new V03Reference.Step("read", name, pointer), context);
    }
}

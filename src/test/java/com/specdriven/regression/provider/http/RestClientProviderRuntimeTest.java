package com.specdriven.regression.provider.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class RestClientProviderRuntimeTest {
    @Test
    void serializesStructuredRequestBodiesAsJson() throws Exception {
        Method method = RestClientProviderRuntime.class.getDeclaredMethod("requestBody", Path.class, ProviderOperationRequest.class);
        method.setAccessible(true);

        String body = (String) method.invoke(new RestClientProviderRuntime(), Path.of("."),
                new ProviderOperationRequest("http_request", List.of(Map.of("bind_as", "request.body", "ref",
                        List.of(Map.of("ORDER_ID", "ORD-1", "STATUS", "CREATED")))), Map.of()));

        Object parsed = new Yaml().load(body);
        assertThat(parsed).isEqualTo(List.of(Map.of("ORDER_ID", "ORD-1", "STATUS", "CREATED")));
    }
}

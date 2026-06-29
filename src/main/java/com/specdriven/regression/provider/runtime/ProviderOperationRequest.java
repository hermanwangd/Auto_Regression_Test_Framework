package com.specdriven.regression.provider.runtime;

import java.util.List;
import java.util.Map;

public record ProviderOperationRequest(
        String operation,
        List<Map<String, Object>> parameters,
        Map<String, Object> outputs) {
}

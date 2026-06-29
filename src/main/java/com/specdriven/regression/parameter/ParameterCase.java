package com.specdriven.regression.parameter;

import java.util.Map;

public record ParameterCase(String caseId, String bindAs, Map<String, Object> values) {
}

package com.specdriven.regression.contract.v03;

import java.math.BigDecimal;

/** Validates profile binding values before provider dispatch without resolving secret material. */
public final class V03BindingValueValidator {

    public void validateEnvironmentValue(String value, V03ValueType type, String location) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing_environment_value: `" + location + "` is blank.");
        }
        switch (type) {
            case ANY, STRING -> { }
            case NUMBER -> {
                try {
                    new BigDecimal(value);
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("invalid_environment_value_type: `" + location
                            + "` requires NUMBER.", error);
                }
            }
            case BOOLEAN -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new IllegalArgumentException("invalid_environment_value_type: `" + location
                            + "` requires BOOLEAN.");
                }
            }
            case OBJECT, ARRAY -> throw new IllegalArgumentException("unsupported_environment_value_type: `"
                    + location + "` requires an explicit structured binding contract.");
        }
    }
}

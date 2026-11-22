package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class V03BindingValueValidatorTest {

    private final V03BindingValueValidator validator = new V03BindingValueValidator();

    @Test
    void acceptsStrictNumberAndBooleanEnvironmentValues() {
        assertThatCode(() -> validator.validateEnvironmentValue("42.5", V03ValueType.NUMBER, "binding.timeout"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateEnvironmentValue("false", V03ValueType.BOOLEAN, "binding.enabled"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidOrUnsupportedEnvironmentValueShapes() {
        assertThatThrownBy(() -> validator.validateEnvironmentValue("yes", V03ValueType.BOOLEAN, "binding.enabled"))
                .hasMessageContaining("invalid_environment_value_type");
        assertThatThrownBy(() -> validator.validateEnvironmentValue("{}", V03ValueType.OBJECT, "binding.config"))
                .hasMessageContaining("unsupported_environment_value_type");
    }
}

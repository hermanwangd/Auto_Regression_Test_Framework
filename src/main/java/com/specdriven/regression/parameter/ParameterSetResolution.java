package com.specdriven.regression.parameter;

import java.util.List;

public record ParameterSetResolution(
        boolean parameterized,
        String ref,
        String bindAs,
        List<ParameterCase> cases,
        List<ParameterSetGap> gaps) {
}

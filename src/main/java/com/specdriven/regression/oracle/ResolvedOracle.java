package com.specdriven.regression.oracle;

import java.nio.file.Path;

public record ResolvedOracle(
        String name,
        String type,
        String oracleReference,
        String expectedRef,
        Path expectedPath) {
}

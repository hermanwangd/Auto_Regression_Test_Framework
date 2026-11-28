package com.specdriven.regression.contract.v03;

import java.util.List;
import java.util.Map;

/** Immutable suite metadata retained after v0.3 YAML compilation. */
public record V03SuiteMetadata(String manifestVersion, String suiteId, String defaultProfile,
        List<Map<String, Object>> caseSources) {
    public V03SuiteMetadata {
        caseSources = List.copyOf(caseSources);
    }

    public V03SuiteMetadata(String manifestVersion, String suiteId, String defaultProfile) {
        this(manifestVersion, suiteId, defaultProfile, List.of());
    }
}

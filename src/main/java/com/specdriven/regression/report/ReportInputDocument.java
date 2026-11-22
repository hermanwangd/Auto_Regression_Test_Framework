package com.specdriven.regression.report;

import java.nio.file.Path;
import java.util.Map;

public record ReportInputDocument(
        Kind kind,
        Path source,
        Map<String, Object> result,
        Map<String, Object> summary,
        boolean compatibilitySource) {

    public enum Kind { STANDARD_RESULT, LEGACY_SUITE_SUMMARY }
}

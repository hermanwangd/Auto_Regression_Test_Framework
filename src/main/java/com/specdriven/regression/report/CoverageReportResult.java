package com.specdriven.regression.report;

import java.nio.file.Path;
import java.util.List;

public record CoverageReportResult(
        boolean reviewReady,
        int covered,
        int totalAutomatable,
        double coveragePercent,
        Path reviewDir,
        List<String> gaps) {
}

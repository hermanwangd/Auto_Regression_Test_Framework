package com.specdriven.regression.expectedresult;

import java.nio.file.Path;
import java.util.List;

public record ExpectedResultEligibilityReport(
        boolean eligible,
        String status,
        Path artifactPath,
        List<ExpectedResultGap> gaps) {
}

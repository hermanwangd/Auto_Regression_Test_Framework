package com.specdriven.regression.oracle;

import java.util.List;

public record OracleReadinessReport(boolean ready, List<OracleReadinessGap> gaps) {
}

package com.specdriven.regression.fixture;

import java.util.List;

public record FixtureLifecycleReport(
        boolean ready,
        boolean cleanupRequired,
        List<String> fixtureProviders,
        List<FixtureLifecycleGap> gaps) {
}

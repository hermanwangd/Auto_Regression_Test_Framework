package com.specdriven.regression.sit;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** Discovery by SPI; unknown or duplicate IDs are errors, never fallback selection. */
public final class SitReadinessRegistry {
    private final List<SitReadinessAdapter> adapters;

    public SitReadinessRegistry(List<SitReadinessAdapter> adapters) { this.adapters = List.copyOf(adapters); }

    public static SitReadinessRegistry discover(ClassLoader loader) {
        var adapters = new ArrayList<SitReadinessAdapter>();
        adapters.add(new FixtureSitReadinessAdapter());
        ServiceLoader.load(SitReadinessAdapter.class, loader).forEach(adapters::add);
        return new SitReadinessRegistry(adapters);
    }

    public SitReadinessAdapter resolve(String id) {
        var matches = adapters.stream().filter(adapter -> id.equals(adapter.id())).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Readiness adapter must resolve uniquely: " + id);
        return matches.get(0);
    }
}

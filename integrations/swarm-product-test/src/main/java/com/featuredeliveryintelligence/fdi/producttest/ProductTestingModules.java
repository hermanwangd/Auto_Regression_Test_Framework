package com.featuredeliveryintelligence.fdi.producttest;

import java.util.ArrayList;
import java.util.ServiceLoader;

public final class ProductTestingModules {
    private ProductTestingModules() {}
    public static ProductTestingModule resolve(String id, String version, FrameworkExecutionPort framework, ClassLoader loader) {
        var matches = new ArrayList<ProductTestingModuleProvider>();
        try {
            for (var provider : ServiceLoader.load(ProductTestingModuleProvider.class, loader))
                if (id.equals(provider.id())) matches.add(provider);
        } catch (java.util.ServiceConfigurationError e) { throw new IllegalArgumentException("Module discovery failed", e); }
        if (matches.isEmpty()) throw new IllegalArgumentException("Requested product-testing module is not installed: " + id);
        if (matches.size() != 1) throw new IllegalArgumentException("Duplicate product-testing module ID: " + id);
        var provider = matches.get(0);
        if (!version.equals(provider.version())) throw new IllegalArgumentException("Incompatible product-testing module version: " + version);
        var module = provider.create(framework);
        if (module == null || !id.equals(module.id()) || !version.equals(module.version()))
            throw new IllegalArgumentException("Module factory returned a mismatched identity");
        return module;
    }
}

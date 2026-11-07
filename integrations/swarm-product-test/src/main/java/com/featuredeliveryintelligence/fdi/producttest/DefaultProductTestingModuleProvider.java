package com.featuredeliveryintelligence.fdi.producttest;

public final class DefaultProductTestingModuleProvider implements ProductTestingModuleProvider {
    public String id() { return "product-test"; }
    public String version() { return "1"; }
    public ProductTestingModule create(FrameworkExecutionPort framework) { return new DefaultProductTestingModule(framework); }
}

package com.featuredeliveryintelligence.fdi.producttest;

/** ServiceLoader factory keeps the execution port explicitly constructor-injected. */
public interface ProductTestingModuleProvider {
    String id();
    String version();
    ProductTestingModule create(FrameworkExecutionPort framework);
}

package com.featuredeliveryintelligence.fdi.producttest;

public interface ProductTestingModule {
    String id();
    String version();
    ProductTestReceipt execute(ProductTestRequest request);
}

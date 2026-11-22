package com.featuredeliveryintelligence.fdi.producttest;

/** One narrow invocation boundary. Production ports check approved roots and independently pinned reviewed inputs before launch. */
@FunctionalInterface
public interface FrameworkExecutionPort {
    ProductTestReceipt execute(ProductTestRequest request);
}

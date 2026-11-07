package com.specdriven.regression.sit;

import com.specdriven.regression.producttest.ProductTestPolicy;

/** One fake owned namespace. Never serialize its token into evidence. */
public record SitExecutionScope(ProductTestPolicy.SharedState policy, String runId,
        LocalFakeSitOwnership store, LocalFakeSitOwnership.Token token) { }

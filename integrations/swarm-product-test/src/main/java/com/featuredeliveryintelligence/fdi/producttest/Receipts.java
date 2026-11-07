package com.featuredeliveryintelligence.fdi.producttest;

import java.util.Map;
import java.util.UUID;

final class Receipts {
    private Receipts() {}
    static ProductTestReceipt failure(ProductTestRequest request, ProductTestReceipt.Outcome outcome, String diagnostic) {
        return failure(request, UUID.randomUUID().toString(), outcome, diagnostic, "", null);
    }
    static ProductTestReceipt failure(ProductTestRequest request, String invocation, ProductTestReceipt.Outcome outcome,
                                      String diagnostic, String directory, Integer exitCode) {
        return new ProductTestReceipt("product-test-receipt.v1", "product-test", "1", invocation, request,
                outcome, "", "not_validated", exitCode, "", directory, Map.of(), false, "",
                diagnostic == null ? "Request or invocation rejected" : diagnostic);
    }
}

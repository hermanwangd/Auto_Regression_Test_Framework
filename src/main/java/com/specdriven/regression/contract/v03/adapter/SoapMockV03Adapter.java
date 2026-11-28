package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.provider.soap.SoapMockProviderRuntime;
import java.util.Set;

public class SoapMockV03Adapter extends RunScopedMockV03Adapter<SoapMockProviderRuntime> {
    private static final Set<String> OPERATIONS = Set.of(
            "load_soap_stub",
            "soap_request_received",
            "reset_mock");

    public SoapMockV03Adapter() {
        super(SoapMockProviderRuntime::new);
    }

    @Override
    public String providerType() {
        return "soap_mock";
    }

    @Override
    public boolean supports(String providerContract, String operation) {
        return "soap_mock.v0.3".equals(providerContract) && OPERATIONS.contains(operation);
    }
}

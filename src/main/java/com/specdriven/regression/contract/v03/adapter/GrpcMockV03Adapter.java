package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.provider.grpc.GrpcMockProviderRuntime;
import java.util.Set;

public class GrpcMockV03Adapter extends RunScopedMockV03Adapter<GrpcMockProviderRuntime> {
    private static final Set<String> OPERATIONS = Set.of(
            "load_grpc_stub",
            "grpc_request_received",
            "reset_mock");

    public GrpcMockV03Adapter() {
        super(GrpcMockProviderRuntime::new);
    }

    @Override
    public String providerType() {
        return "grpc_mock";
    }

    @Override
    public boolean supports(String providerContract, String operation) {
        return "grpc_mock.v0.3".equals(providerContract) && OPERATIONS.contains(operation);
    }
}

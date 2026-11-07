package com.specdriven.regression.producttest;

import com.specdriven.regression.provider.cli.ReviewedCliPolicy;
import com.specdriven.regression.provider.http.ReviewedLoopbackHttpPolicy;
import com.specdriven.regression.provider.nats.ReviewedLoopbackNatsPolicy;
import java.util.Map;
import com.specdriven.regression.provider.grpc.ReviewedLoopbackGrpcPolicy;

/** Exact native capability selected by the reviewed contract, independently of test level. */
public enum ReviewedNativeCapability {
    HTTP("rest_client.v0.3", "rest_client", "http_request"),
    CLI("cli_process.v0.3", "cli_process", "run_command"),
    NATS("nats.v0.3", "nats", "nats_publish"),
    GRPC("grpc_client.v0.3", "grpc_client", "unary_call");

    private final String contract, providerType, operation;
    ReviewedNativeCapability(String contract, String providerType, String operation) {
        this.contract = contract; this.providerType = providerType; this.operation = operation;
    }
    public String contract() { return contract; }
    public String providerType() { return providerType; }
    public String operation() { return operation; }
    public static ReviewedNativeCapability fromContract(String contract) {
        for (var capability : values()) if (capability.contract.equals(contract)) return capability;
        throw new IllegalArgumentException("unsupported_reviewed_native_contract: " + contract);
    }
    public void validateBindings(Map<String,?> values) {
        if (this == GRPC) ReviewedLoopbackGrpcPolicy.validateBindings(values);
        else if (this == CLI) ReviewedCliPolicy.validateBindings(values);
        else if (this == NATS) ReviewedLoopbackNatsPolicy.validateBindings(values);
        else {
            if (!values.keySet().equals(java.util.Set.of("base_url")))
                throw new IllegalArgumentException("reviewed HTTP requires exact base_url binding");
            ReviewedLoopbackHttpPolicy.validateOrigin(values.get("base_url"));
        }
    }
    public void validateInputs(Map<String,Object> values) {
        if (this == GRPC) ReviewedLoopbackGrpcPolicy.validateInputs(values);
        else if (this == CLI) ReviewedCliPolicy.validateInputs(values);
        else if (this == NATS) ReviewedLoopbackNatsPolicy.validateInputs(values);
        else ReviewedLoopbackHttpPolicy.validateInputs(values);
    }
}

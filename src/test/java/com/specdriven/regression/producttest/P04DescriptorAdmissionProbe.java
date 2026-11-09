package com.specdriven.regression.producttest;

import java.util.Map;

/** Dependency-free executable contract checks; also invoked by the JUnit wrapper. */
public final class P04DescriptorAdmissionProbe {
    private static int checks;
    public static void main(String[] args) {
        checks=0;
        var unary = ReviewedNativeCapability.fromContract("grpc_client.v0.3");
        for (String[] profile : new String[][] {
                {"swarm.producttest.EchoService", "Echo", "echo"},
                {"helloworld.Greeter", "SayHello", "greeter"},
                {"routeguide.RouteGuide", "GetFeature", "routeguide"}}) {
            unary.validateInputs(inputs(profile[0], profile[1], profile[2]));
            checks++;
        }
        unary.validateInputs(inputs("service.Test", "GetThingVersions", "service"));checks++;
        // Admission is now exact-profile only; live lease, byte pins and real rejection are checked by P04OwnedStreamingTest.
        rejects(() -> unary.validateInputs(inputs("routeguide.RouteGuide", "ListFeatures", "routeguide")));
        rejects(() -> unary.validateBindings(Map.of("target", "grpc://example.com:12345", "owner_run_id", "a".repeat(32))));
        rejects(() -> unary.validateBindings(Map.of("target", "grpc://127.0.0.1:12345")));
        unary.validateBindings(Map.of("target", "grpc://127.0.0.1:12345", "owner_run_id", "a".repeat(32)));
        checks++;
        System.out.println("Existing unary input/binding guard checks passed: " + checks);
        try {
            var admission = ReviewedNativeCapability.fromContract("grpc_descriptor_admission.v0.3");
            if (!"grpc_descriptor_admission".equals(admission.providerType()) || !"unary_call".equals(admission.operation()))
                throw new AssertionError("Descriptor admission selected a network-capable provider or wrong operation");
        } catch (IllegalArgumentException gap) {
            throw new AssertionError("P04 descriptor-only admission is unavailable before its provider can reject streaming", gap);
        }
        System.out.println("P04 descriptor-only capability admission passed");
    }
    private static Map<String,Object> inputs(String service, String method, String descriptor) {
        return Map.of("grpc.service", service, "grpc.method", method,
                "grpc.descriptor_ref", "artifact://fixtures/" + descriptor + ".desc.b64",
                "grpc.message", "artifact://fixtures/request.json");
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Unsafe unary input or binding was admitted");
    }
}

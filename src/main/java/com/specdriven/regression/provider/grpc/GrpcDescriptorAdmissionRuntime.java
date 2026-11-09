package com.specdriven.regression.provider.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.provider.runtime.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline terminal descriptor admission. This class has no channel, transport or network dependency. */
public final class GrpcDescriptorAdmissionRuntime {
    public ProviderOperationResult executeReviewed(ProviderExecutionContext context, ProviderOperationRequest request) {
        if(!"native".equals(context.runtimeMode()) || !ReviewedGrpcDescriptorPolicy.TYPE.equals(context.providerType())
                || !"unary_call".equals(request.operation())) throw new IllegalArgumentException("reviewed_descriptor: wrong runtime/operation");
        var inputs=new LinkedHashMap<String,Object>();
        for(var p:request.parameters()) {
            String key=String.valueOf(p.get("bind_as"));
            if(inputs.putIfAbsent(key,p.get("ref"))!=null)throw new IllegalArgumentException("reviewed_descriptor: duplicate input");
        }
        // The v0.3 resolver returns suite-relative artifact paths to provider runtimes.
        if("fixtures/service.desc.b64".equals(inputs.get("grpc.descriptor_ref")))inputs.put("grpc.descriptor_ref","artifact://fixtures/service.desc.b64");
        if("fixtures/request.json".equals(inputs.get("grpc.message")))inputs.put("grpc.message","artifact://fixtures/request.json");
        ReviewedGrpcDescriptorPolicy.validateInputs(inputs);
        var method=ReviewedGrpcDescriptorPolicy.fixture(context.suiteRoot(),context.bindingValues());
        String diagnostic;
        try {
            GrpcDescriptorSupport.requireUnary(method);
            throw new IllegalStateException("reviewed_descriptor: expected streaming rejection was not observed");
        } catch (IllegalArgumentException actualRejection) {
            diagnostic="gRPC unary_call failed: "+actualRejection.getMessage();
        }
        var proof=new LinkedHashMap<String,Object>();
        proof.put("schema","reviewed-grpc-descriptor-rejection.v1"); proof.put("evidence_type","grpc_request_response");
        proof.put("provider_type",ReviewedGrpcDescriptorPolicy.TYPE);proof.put("provider_id",context.providerId());
        proof.put("operation","unary_call");proof.put("run_id",context.runDir().getFileName().toString());
        proof.put("test_case_id",request.outputs().get("_test_case_id"));proof.put("step_id",request.outputs().get("_operation_id"));
        proof.put("source_revision",ReviewedGrpcDescriptorPolicy.SOURCE_REVISION);proof.put("source_sha256",ReviewedGrpcDescriptorPolicy.SOURCE_SHA);
        proof.put("descriptor_sha256",ReviewedGrpcDescriptorPolicy.DESCRIPTOR_SHA);proof.put("request_sha256",ReviewedGrpcDescriptorPolicy.REQUEST_SHA);
        proof.put("service",method.getService().getFullName());proof.put("method",method.getName());
        proof.put("input_type",method.getInputType().getFullName());proof.put("output_type",method.getOutputType().getFullName());
        proof.put("client_streaming",method.isClientStreaming());proof.put("server_streaming",method.isServerStreaming());
        proof.put("status","UNKNOWN");proof.put("operation_status","failed");proof.put("failure_code","OPERATION_FAILED");
        proof.put("diagnostic",diagnostic);proof.put("rpc_attempted",false);proof.put("channel_created",false);proof.put("cleanup_confirmed",true);
        proof.put("descriptor_generation","reviewed_descriptor_assembly");proof.put("compiler",null);
        try {
            var file=context.runDir().resolve(ReviewedGrpcDescriptorProof.PATH);Files.createDirectories(file.getParent());
            Files.write(file,new ObjectMapper().writeValueAsBytes(proof));
        } catch(IOException error){throw new UncheckedIOException(error);}
        return ProviderOperationResult.failed(Map.of("response.status","UNKNOWN"),
                List.of(new ProviderEvidence("grpc_request_response",ReviewedGrpcDescriptorProof.PATH,true)),
                ProviderFailure.of("OPERATION_FAILED","FRAMEWORK_ERROR",diagnostic,"Inspect the pinned descriptor-admission proof; no RPC was attempted."));
    }
}

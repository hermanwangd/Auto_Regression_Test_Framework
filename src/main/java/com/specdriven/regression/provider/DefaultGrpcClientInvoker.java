package com.specdriven.regression.provider;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

class DefaultGrpcClientInvoker implements GrpcClientInvoker {

    @Override
    public GrpcClientResult invoke(GrpcClientRequest request) throws IOException, InterruptedException {
        Descriptors.MethodDescriptor method = descriptorMethod(
                request.descriptorPath(),
                request.service(),
                request.method());
        DynamicMessage.Builder requestMessage = DynamicMessage.newBuilder(method.getInputType());
        JsonFormat.parser().merge(request.payloadJson(), requestMessage);

        ManagedChannel channel = channel(request).build();
        try {
            DynamicMessage response = ClientCalls.blockingUnaryCall(
                    channel,
                    grpcMethod(method),
                    CallOptions.DEFAULT.withDeadlineAfter(Math.max(1, request.timeoutSeconds()), TimeUnit.SECONDS),
                    requestMessage.build());
            return new GrpcClientResult(JsonFormat.printer()
                    .omittingInsignificantWhitespace()
                    .print(response));
        } catch (StatusRuntimeException e) {
            throw new GrpcClientException(
                    grpcFailureMessage(e),
                    e,
                    e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED);
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private ManagedChannelBuilder<?> channel(GrpcClientRequest request) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(request.endpoint());
        if (request.plaintext()) {
            builder.usePlaintext();
        }
        if (!request.authority().isBlank()) {
            builder.overrideAuthority(request.authority());
        }
        return builder;
    }

    private MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethod(Descriptors.MethodDescriptor method) {
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(
                        method.getService().getFullName(),
                        method.getName()))
                .setRequestMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.getDefaultInstance(method.getInputType())))
                .setResponseMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.getDefaultInstance(method.getOutputType())))
                .build();
    }

    private Descriptors.MethodDescriptor descriptorMethod(
            java.nio.file.Path descriptorPath,
            String serviceName,
            String methodName) throws IOException {
        Map<String, DescriptorProtos.FileDescriptorProto> protos = descriptorProtos(descriptorPath);
        Map<String, Descriptors.FileDescriptor> descriptors = new LinkedHashMap<>();
        for (DescriptorProtos.FileDescriptorProto proto : protos.values()) {
            buildDescriptor(proto, protos, descriptors);
        }
        Descriptors.ServiceDescriptor service = findService(descriptors, serviceName);
        Descriptors.MethodDescriptor method = service.findMethodByName(methodName);
        if (method == null) {
            throw new IOException("gRPC method `" + methodName + "` not found in service `" + serviceName + "`.");
        }
        return method;
    }

    private Map<String, DescriptorProtos.FileDescriptorProto> descriptorProtos(java.nio.file.Path descriptorPath)
            throws IOException {
        try (InputStream input = Files.newInputStream(descriptorPath)) {
            DescriptorProtos.FileDescriptorSet descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(input);
            Map<String, DescriptorProtos.FileDescriptorProto> protos = new LinkedHashMap<>();
            for (DescriptorProtos.FileDescriptorProto proto : descriptorSet.getFileList()) {
                protos.put(proto.getName(), proto);
            }
            return protos;
        }
    }

    private Descriptors.FileDescriptor buildDescriptor(
            DescriptorProtos.FileDescriptorProto proto,
            Map<String, DescriptorProtos.FileDescriptorProto> protos,
            Map<String, Descriptors.FileDescriptor> descriptors) throws IOException {
        Descriptors.FileDescriptor existing = descriptors.get(proto.getName());
        if (existing != null) {
            return existing;
        }
        List<Descriptors.FileDescriptor> dependencies = new ArrayList<>();
        for (String dependencyName : proto.getDependencyList()) {
            DescriptorProtos.FileDescriptorProto dependency = protos.get(dependencyName);
            if (dependency == null) {
                throw new IOException("Descriptor dependency `" + dependencyName
                        + "` is missing from descriptor set.");
            }
            dependencies.add(buildDescriptor(dependency, protos, descriptors));
        }
        try {
            Descriptors.FileDescriptor descriptor = Descriptors.FileDescriptor.buildFrom(
                    proto,
                    dependencies.toArray(Descriptors.FileDescriptor[]::new));
            descriptors.put(proto.getName(), descriptor);
            return descriptor;
        } catch (Descriptors.DescriptorValidationException e) {
            throw new IOException("Invalid gRPC descriptor `" + proto.getName() + "`.", e);
        }
    }

    private Descriptors.ServiceDescriptor findService(
            Map<String, Descriptors.FileDescriptor> descriptors,
            String serviceName) throws IOException {
        for (Descriptors.FileDescriptor descriptor : descriptors.values()) {
            for (Descriptors.ServiceDescriptor service : descriptor.getServices()) {
                if (service.getFullName().equals(serviceName) || service.getName().equals(serviceName)) {
                    return service;
                }
            }
        }
        throw new IOException("gRPC service `" + serviceName + "` not found in descriptor set.");
    }

    private String grpcFailureMessage(StatusRuntimeException e) {
        String description = e.getStatus().getDescription();
        if (description == null || description.isBlank()) {
            return "gRPC call failed with status " + e.getStatus().getCode();
        }
        return description;
    }
}

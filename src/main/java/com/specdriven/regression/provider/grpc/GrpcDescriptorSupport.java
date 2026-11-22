package com.specdriven.regression.provider.grpc;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared descriptor parsing and cardinality boundary; no channel or network API. */
public final class GrpcDescriptorSupport {
    private GrpcDescriptorSupport() {}
    public static final class StreamingDescriptorException extends IllegalArgumentException {
        private StreamingDescriptorException() {super("Unsupported streaming descriptor for gRPC unary_call; select a unary method.");}
    }
    public static void requireUnary(Descriptors.MethodDescriptor method) {
        if (method.isClientStreaming() || method.isServerStreaming())
            throw new StreamingDescriptorException();
    }
    public static Descriptors.MethodDescriptor method(Path descriptorPath, String serviceName, String methodName)
            throws IOException {
        Map<String, DescriptorProtos.FileDescriptorProto> protos = descriptorProtos(descriptorPath);
        return method(protos, serviceName, methodName);
    }

    public static Descriptors.MethodDescriptor method(byte[] bytes, String serviceName, String methodName) throws IOException {
        Map<String, DescriptorProtos.FileDescriptorProto> protos = new LinkedHashMap<>();
        for (var proto : DescriptorProtos.FileDescriptorSet.parseFrom(bytes).getFileList()) protos.put(proto.getName(), proto);
        return method(protos, serviceName, methodName);
    }

    private static Descriptors.MethodDescriptor method(Map<String, DescriptorProtos.FileDescriptorProto> protos,
            String serviceName, String methodName) throws IOException {
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

    private static Map<String, DescriptorProtos.FileDescriptorProto> descriptorProtos(Path descriptorPath) throws IOException {
        try (InputStream input = Files.newInputStream(descriptorPath)) {
            DescriptorProtos.FileDescriptorSet descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(input);
            Map<String, DescriptorProtos.FileDescriptorProto> protos = new LinkedHashMap<>();
            for (DescriptorProtos.FileDescriptorProto proto : descriptorSet.getFileList()) {
                protos.put(proto.getName(), proto);
            }
            return protos;
        }
    }

    private static Descriptors.FileDescriptor buildDescriptor(
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
                throw new IOException("Descriptor dependency `" + dependencyName + "` is missing.");
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

    private static Descriptors.ServiceDescriptor findService(
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

}

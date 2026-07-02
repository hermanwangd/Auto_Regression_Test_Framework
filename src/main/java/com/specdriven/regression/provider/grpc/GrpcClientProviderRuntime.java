package com.specdriven.regression.provider.grpc;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import com.specdriven.regression.provider.runtime.ProviderEvidence;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.specdriven.regression.provider.runtime.ProviderRuntime;
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class GrpcClientProviderRuntime implements ProviderRuntime {

    private static final String PROVIDER_TYPE = "grpc_client";

    @Override
    public ProviderOperationResult execute(ProviderExecutionContext context, ProviderOperationRequest request) {
        return switch (request.operation()) {
            case "unary_call" -> unaryCall(context, request);
            default -> ProviderOperationResult.failed(
                    Map.of(),
                    List.of(),
                    ProviderFailure.of(
                            "UNSUPPORTED_OPERATION",
                            "TARGET_RESOLUTION_FAILED",
                            "Unsupported gRPC client operation `" + request.operation() + "`.",
                            "Use an operation declared by the grpc_client Provider Contract."));
        };
    }

    private ProviderOperationResult unaryCall(ProviderExecutionContext context, ProviderOperationRequest request) {
        String target = stringValue(context.bindingValues().get("target"));
        if (target.isBlank() || target.startsWith("generated://")) {
            return ProviderOperationResult.failed(
                    Map.of(),
                    List.of(),
                    ProviderFailure.of(
                            "MISSING_BINDING_KEY",
                            "TARGET_RESOLUTION_FAILED",
                            "grpc_client target was not resolved.",
                            "Resolve generated target_uri from the upstream grpc_mock provider before unary_call."));
        }
        String service = parameterValue(request, "grpc.service", "");
        String methodName = parameterValue(request, "grpc.method", "");
        String descriptorRef = firstNonBlank(
                parameterValue(request, "grpc.descriptor_ref", ""),
                stringValue(context.bindingValues().get("descriptor_ref")));
        long started = System.nanoTime();
        ManagedChannel channel = null;
        try {
            Path descriptorPath = GrpcDescriptorMaterializer.materialize(
                    context.suiteRoot(),
                    context.runDir().resolve("grpc-client-descriptors"),
                    descriptorRef);
            String payload = requestPayload(context.suiteRoot(), parameterValue(request, "grpc.message", ""));
            int timeoutSeconds = timeoutSeconds(parameterValue(request, "grpc.timeout", ""), 10);
            channel = ManagedChannelBuilder.forTarget(stripGrpcScheme(target))
                    .usePlaintext()
                    .build();
            Descriptors.MethodDescriptor method = descriptorMethod(descriptorPath, service, methodName);
            DynamicMessage.Builder requestMessage = DynamicMessage.newBuilder(method.getInputType());
            JsonFormat.parser().merge(payload, requestMessage);
            DynamicMessage response = ClientCalls.blockingUnaryCall(
                    channel,
                    grpcMethod(method),
                    CallOptions.DEFAULT.withDeadlineAfter(Math.max(1, timeoutSeconds), TimeUnit.SECONDS),
                    requestMessage.build());
            long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            String responseJson = JsonFormat.printer().omittingInsignificantWhitespace().print(response);
            String maskedResponseJson = maskSensitiveText(responseJson);
            writeRequestEvidence(context, service, methodName, payload);
            writeResponseEvidence(context, "OK", maskedResponseJson, durationMs, "passed", "");
            return ProviderOperationResult.passed(
                    Map.of(
                            "response.message", maskedResponseJson,
                            "response.status", "OK",
                            "response.metadata", Map.of(),
                            "response.duration_ms", durationMs),
                    evidence(
                            "grpc_request", "provider-evidence/grpc-client/request.json",
                            "grpc_request_response", "provider-evidence/grpc-client/response.json"));
        } catch (StatusRuntimeException e) {
            String status = e.getStatus().getCode().name();
            String safeError = maskSensitiveText(safeMessage(e));
            writeResponseEvidence(context, status, "", 0, "failed", safeError);
            return ProviderOperationResult.failed(
                    Map.of("response.status", status),
                    evidence("grpc_request_response", "provider-evidence/grpc-client/response.json"),
                    ProviderFailure.of(
                            e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED
                                    ? "PROVIDER_TIMEOUT" : "OPERATION_FAILED",
                            e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED
                                    ? "PROVIDER_TIMEOUT" : "FRAMEWORK_ERROR",
                            "gRPC unary_call failed: " + safeError,
                            "Review provider-evidence/grpc-client/response.json and Env_Profile target."));
        } catch (Exception e) {
            String safeError = maskSensitiveText(safeMessage(e));
            writeResponseEvidence(context, "UNKNOWN", "", 0, "failed", safeError);
            return ProviderOperationResult.failed(
                    Map.of("response.status", "UNKNOWN"),
                    evidence("grpc_request_response", "provider-evidence/grpc-client/response.json"),
                    ProviderFailure.of(
                            "OPERATION_FAILED",
                            "FRAMEWORK_ERROR",
                            "gRPC unary_call failed: " + safeError,
                            "Review gRPC descriptor, request JSON, target binding, and provider evidence."));
        } finally {
            if (channel != null) {
                channel.shutdownNow();
                try {
                    channel.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
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

    private Descriptors.MethodDescriptor descriptorMethod(Path descriptorPath, String serviceName, String methodName)
            throws IOException {
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

    private Map<String, DescriptorProtos.FileDescriptorProto> descriptorProtos(Path descriptorPath) throws IOException {
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

    private String requestPayload(Path suiteRoot, String refOrValue) {
        if (refOrValue.isBlank()) {
            return "{}";
        }
        Path file = suiteRoot.resolve(refOrValue).normalize();
        if (Files.isRegularFile(file)) {
            try {
                return Files.readString(file).strip();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read gRPC request body ref `" + refOrValue + "`.", e);
            }
        }
        return refOrValue;
    }

    private String stripGrpcScheme(String target) {
        return target.startsWith("grpc://") ? target.substring("grpc://".length()) : target;
    }

    private int timeoutSeconds(String timeout, int fallback) {
        if (timeout == null || timeout.isBlank()) {
            return fallback;
        }
        try {
            return (int) Math.max(1, Duration.parse(timeout).toSeconds());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private String parameterValue(ProviderOperationRequest request, String bindAs, String fallback) {
        for (Map<String, Object> parameter : request.parameters()) {
            if (bindAs.equals(parameter.get("bind_as"))) {
                return stringValue(parameter.get("ref"));
            }
        }
        return fallback;
    }

    private void writeRequestEvidence(
            ProviderExecutionContext context,
            String service,
            String method,
            String body) {
        write(context.runDir().resolve("provider-evidence/grpc-client/request.json"), """
                {
                  "evidence_type": "grpc_request_response",
                  "provider_type": "%s",
                  "provider_id": "%s",
                  "service": "%s",
                  "method": "%s",
                  "body": %s,
                  "masking_applied": true
                }
                """.formatted(
                        PROVIDER_TYPE,
                        escape(context.providerId()),
                        escape(service),
                        escape(method),
                        toJson(maskSensitiveText(body))));
    }

    private void writeResponseEvidence(
            ProviderExecutionContext context,
            String status,
            String body,
            long durationMs,
            String operationStatus,
            String error) {
        write(context.runDir().resolve("provider-evidence/grpc-client/response.json"), """
                {
                  "evidence_type": "grpc_request_response",
                  "provider_type": "%s",
                  "provider_id": "%s",
                  "status": "%s",
                  "body": %s,
                  "duration_ms": %s,
                  "operation_status": "%s",
                  "error": %s,
                  "masking_applied": true
                }
                """.formatted(
                        PROVIDER_TYPE,
                        escape(context.providerId()),
                        escape(status),
                        toJson(body),
                        durationMs,
                        escape(operationStatus),
                        toJson(error)));
    }

    private List<ProviderEvidence> evidence(String type, String ref) {
        return List.of(new ProviderEvidence(type, ref, true));
    }

    private List<ProviderEvidence> evidence(String type1, String ref1, String type2, String ref2) {
        List<ProviderEvidence> evidence = new ArrayList<>();
        evidence.add(new ProviderEvidence(type1, ref1, true));
        evidence.add(new ProviderEvidence(type2, ref2, true));
        return List.copyOf(evidence);
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write gRPC client evidence: " + path, e);
        }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String maskSensitiveText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replaceAll("(?i)(\"(?:password|token|api_key|authorization|secret|credential)\"\\s*:\\s*\")[^\"]*(\")", "$1***MASKED***$2")
                .replaceAll("(?i)(authorization\\s*[:=]\\s*)[^\\s,}\\]]+", "$1***MASKED***")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1***MASKED***")
                .replaceAll("(?i)(jdbc:[^\\s,}\\]]+)", "***MASKED***")
                .replaceAll("(?i)(mongodb(?:\\+srv)?://[^\\s,}\\]]+)", "***MASKED***")
                .replaceAll("(?i)(nats://[^\\s,}\\]]+)", "***MASKED***")
                .replaceAll("(?i)((?:password|token|secret|credential|api_key)\\s*[=:]\\s*)[^\\s,}\\]]+", "$1***MASKED***")
                .replaceAll("-----BEGIN [^-]+ PRIVATE KEY-----[\\s\\S]*?-----END [^-]+ PRIVATE KEY-----", "***MASKED***");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}

package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultGrpcClientInvokerTest {

    @TempDir
    Path tempDir;

    @Test
    void invokesUnaryGrpcMethodFromDescriptorSet() throws Exception {
        DescriptorProtos.FileDescriptorProto proto = paymentFileDescriptorProto();
        Path descriptorPath = tempDir.resolve("payment.desc");
        Files.write(descriptorPath, DescriptorProtos.FileDescriptorSet.newBuilder()
                .addFile(proto)
                .build()
                .toByteArray());

        Descriptors.FileDescriptor fileDescriptor =
                Descriptors.FileDescriptor.buildFrom(proto, new Descriptors.FileDescriptor[0]);
        Descriptors.ServiceDescriptor service = fileDescriptor.findServiceByName("PaymentService");
        Descriptors.MethodDescriptor method = service.findMethodByName("SubmitPayment");
        Descriptors.FieldDescriptor requestPaymentId = method.getInputType().findFieldByName("payment_id");
        Descriptors.FieldDescriptor responseStatus = method.getOutputType().findFieldByName("status");
        AtomicReference<DynamicMessage> seenRequest = new AtomicReference<>();
        Server server = NettyServerBuilder.forPort(0)
                .directExecutor()
                .addService(ServerServiceDefinition.builder(service.getFullName())
                        .addMethod(grpcMethod(service, method), ServerCalls.asyncUnaryCall((request, observer) -> {
                            seenRequest.set(request);
                            DynamicMessage response = DynamicMessage.newBuilder(method.getOutputType())
                                    .setField(responseStatus, "approved")
                                    .build();
                            observer.onNext(response);
                            observer.onCompleted();
                        }))
                        .build())
                .build()
                .start();
        try {
            GrpcClientResult result = new DefaultGrpcClientInvoker().invoke(new GrpcClientRequest(
                    "127.0.0.1:" + server.getPort(),
                    descriptorPath,
                    "payment.PaymentService",
                    "SubmitPayment",
                    "{\"paymentId\":\"P-005\"}",
                    5,
                    true,
                    ""));

            assertThat(result.responseBody()).isEqualTo("{\"status\":\"approved\"}");
            assertThat(seenRequest.get().getField(requestPaymentId)).isEqualTo("P-005");
        } finally {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void reportsGrpcStatusFailuresAsClientException() throws Exception {
        Path descriptorPath = writePaymentDescriptor();
        int unusedPort = unusedLocalPort();

        assertThatThrownBy(() -> new DefaultGrpcClientInvoker().invoke(new GrpcClientRequest(
                        "127.0.0.1:" + unusedPort,
                        descriptorPath,
                        "payment.PaymentService",
                        "SubmitPayment",
                        "{\"paymentId\":\"P-006\"}",
                        1,
                true,
                "")))
                .isInstanceOf(GrpcClientException.class)
                .satisfies(error -> assertThat(((GrpcClientException) error).timeout()).isFalse());
    }

    @Test
    void invokesShortServiceNameWithAuthorityOverride() throws Exception {
        DescriptorProtos.FileDescriptorProto proto = paymentFileDescriptorProto();
        Path descriptorPath = writeDescriptorSet(proto);
        Descriptors.FileDescriptor fileDescriptor =
                Descriptors.FileDescriptor.buildFrom(proto, new Descriptors.FileDescriptor[0]);
        Descriptors.ServiceDescriptor service = fileDescriptor.findServiceByName("PaymentService");
        Descriptors.MethodDescriptor method = service.findMethodByName("SubmitPayment");
        Descriptors.FieldDescriptor responseStatus = method.getOutputType().findFieldByName("status");
        Server server = NettyServerBuilder.forPort(0)
                .directExecutor()
                .addService(ServerServiceDefinition.builder(service.getFullName())
                        .addMethod(grpcMethod(service, method), ServerCalls.asyncUnaryCall((request, observer) -> {
                            observer.onNext(DynamicMessage.newBuilder(method.getOutputType())
                                    .setField(responseStatus, "authority-ok")
                                    .build());
                            observer.onCompleted();
                        }))
                        .build())
                .build()
                .start();
        try {
            GrpcClientResult result = new DefaultGrpcClientInvoker().invoke(new GrpcClientRequest(
                    "127.0.0.1:" + server.getPort(),
                    descriptorPath,
                    "PaymentService",
                    "SubmitPayment",
                    "{\"paymentId\":\"P-007\"}",
                    5,
                    true,
                    "payment.local"));

            assertThat(result.responseBody()).isEqualTo("{\"status\":\"authority-ok\"}");
        } finally {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsMissingGrpcServiceAndMethodFromDescriptorSet() throws Exception {
        Path descriptorPath = writePaymentDescriptor();

        assertThatThrownBy(() -> new DefaultGrpcClientInvoker().invoke(new GrpcClientRequest(
                        "127.0.0.1:" + unusedLocalPort(),
                        descriptorPath,
                        "payment.MissingService",
                        "SubmitPayment",
                        "{\"paymentId\":\"P-008\"}",
                        1,
                        true,
                        "")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("gRPC service `payment.MissingService` not found");
        assertThatThrownBy(() -> new DefaultGrpcClientInvoker().invoke(new GrpcClientRequest(
                        "127.0.0.1:" + unusedLocalPort(),
                        descriptorPath,
                        "payment.PaymentService",
                        "MissingMethod",
                        "{\"paymentId\":\"P-008\"}",
                        1,
                        true,
                        "")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("gRPC method `MissingMethod` not found");
    }

    @Test
    void resolvesDescriptorDependenciesWhenDescriptorSetContainsImportedFiles() throws Exception {
        DescriptorProtos.FileDescriptorProto common = commonFileDescriptorProto();
        DescriptorProtos.FileDescriptorProto payment = paymentFileDescriptorProto()
                .toBuilder()
                .addDependency("common.proto")
                .build();
        Path descriptorPath = writeDescriptorSet(common, payment);
        Descriptors.FileDescriptor commonDescriptor =
                Descriptors.FileDescriptor.buildFrom(common, new Descriptors.FileDescriptor[0]);
        Descriptors.FileDescriptor paymentDescriptor =
                Descriptors.FileDescriptor.buildFrom(payment, new Descriptors.FileDescriptor[] {commonDescriptor});

        assertThat(paymentDescriptor.findServiceByName("PaymentService")).isNotNull();
        assertThatThrownBy(() -> new DefaultGrpcClientInvoker().invoke(new GrpcClientRequest(
                        "127.0.0.1:" + unusedLocalPort(),
                        descriptorPath,
                        "payment.PaymentService",
                        "MissingMethod",
                        "{\"paymentId\":\"P-009\"}",
                        1,
                        true,
                        "")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("gRPC method `MissingMethod` not found");
    }

    @Test
    void rejectsDescriptorSetWithMissingOrInvalidDependencies() throws Exception {
        DescriptorProtos.FileDescriptorProto missingDependency = paymentFileDescriptorProto()
                .toBuilder()
                .addDependency("missing.proto")
                .build();
        DescriptorProtos.FileDescriptorProto invalid = paymentFileDescriptorProto()
                .toBuilder()
                .clearMessageType()
                .build();
        Path missingDependencyPath = writeDescriptorSet("missing-dependency.desc", missingDependency);
        Path invalidPath = writeDescriptorSet("invalid.desc", invalid);

        assertThatThrownBy(() -> new DefaultGrpcClientInvoker().invoke(new GrpcClientRequest(
                        "127.0.0.1:" + unusedLocalPort(),
                        missingDependencyPath,
                        "payment.PaymentService",
                        "SubmitPayment",
                        "{\"paymentId\":\"P-010\"}",
                        1,
                        true,
                        "")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Descriptor dependency `missing.proto` is missing");
        assertThatThrownBy(() -> new DefaultGrpcClientInvoker().invoke(new GrpcClientRequest(
                        "127.0.0.1:" + unusedLocalPort(),
                        invalidPath,
                        "payment.PaymentService",
                        "SubmitPayment",
                        "{\"paymentId\":\"P-010\"}",
                        1,
                        true,
                        "")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Invalid gRPC descriptor `payment.proto`");
    }

    @Test
    void reportsGrpcStatusCodeWhenServerReturnsStatusWithoutDescription() throws Exception {
        DescriptorProtos.FileDescriptorProto proto = paymentFileDescriptorProto();
        Path descriptorPath = writeDescriptorSet(proto);
        Descriptors.FileDescriptor fileDescriptor =
                Descriptors.FileDescriptor.buildFrom(proto, new Descriptors.FileDescriptor[0]);
        Descriptors.ServiceDescriptor service = fileDescriptor.findServiceByName("PaymentService");
        Descriptors.MethodDescriptor method = service.findMethodByName("SubmitPayment");
        Server server = NettyServerBuilder.forPort(0)
                .directExecutor()
                .addService(ServerServiceDefinition.builder(service.getFullName())
                        .addMethod(grpcMethod(service, method), ServerCalls.asyncUnaryCall((request, observer) ->
                                observer.onError(Status.UNAVAILABLE.asRuntimeException())))
                        .build())
                .build()
                .start();
        try {
            assertThatThrownBy(() -> new DefaultGrpcClientInvoker().invoke(new GrpcClientRequest(
                            "127.0.0.1:" + server.getPort(),
                            descriptorPath,
                            "payment.PaymentService",
                            "SubmitPayment",
                            "{\"paymentId\":\"P-011\"}",
                            5,
                            true,
                            "")))
                    .isInstanceOf(GrpcClientException.class)
                    .hasMessage("gRPC call failed with status UNAVAILABLE");
        } finally {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethod(
            Descriptors.ServiceDescriptor service,
            Descriptors.MethodDescriptor method) {
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(service.getFullName(), method.getName()))
                .setRequestMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.getDefaultInstance(method.getInputType())))
                .setResponseMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.getDefaultInstance(method.getOutputType())))
                .build();
    }

    private DescriptorProtos.FileDescriptorProto paymentFileDescriptorProto() {
        return DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("payment.proto")
                .setPackage("payment")
                .setSyntax("proto3")
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                        .setName("SubmitPaymentRequest")
                        .addField(stringField("payment_id", 1))
                        .build())
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                        .setName("SubmitPaymentResponse")
                        .addField(stringField("status", 1))
                        .build())
                .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder()
                        .setName("PaymentService")
                        .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                                .setName("SubmitPayment")
                                .setInputType(".payment.SubmitPaymentRequest")
                                .setOutputType(".payment.SubmitPaymentResponse")
                                .build())
                        .build())
                .build();
    }

    private DescriptorProtos.FieldDescriptorProto stringField(String name, int number) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private DescriptorProtos.FileDescriptorProto commonFileDescriptorProto() {
        return DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("common.proto")
                .setPackage("common")
                .setSyntax("proto3")
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                        .setName("Audit")
                        .addField(stringField("trace_id", 1))
                        .build())
                .build();
    }

    private Path writePaymentDescriptor() throws Exception {
        return writeDescriptorSet(paymentFileDescriptorProto());
    }

    private Path writeDescriptorSet(DescriptorProtos.FileDescriptorProto... protos) throws Exception {
        return writeDescriptorSet("payment.desc", protos);
    }

    private Path writeDescriptorSet(String fileName, DescriptorProtos.FileDescriptorProto... protos) throws Exception {
        DescriptorProtos.FileDescriptorSet.Builder descriptorSet = DescriptorProtos.FileDescriptorSet.newBuilder();
        for (DescriptorProtos.FileDescriptorProto proto : protos) {
            descriptorSet.addFile(proto);
        }
        Path descriptorPath = tempDir.resolve(fileName);
        Files.write(descriptorPath, descriptorSet.build().toByteArray());
        return descriptorPath;
    }

    private int unusedLocalPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

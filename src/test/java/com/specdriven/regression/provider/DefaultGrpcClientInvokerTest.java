package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
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

    private Path writePaymentDescriptor() throws Exception {
        Path descriptorPath = tempDir.resolve("payment.desc");
        Files.write(descriptorPath, DescriptorProtos.FileDescriptorSet.newBuilder()
                .addFile(paymentFileDescriptorProto())
                .build()
                .toByteArray());
        return descriptorPath;
    }

    private int unusedLocalPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

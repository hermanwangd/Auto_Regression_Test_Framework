package com.specdriven.regression.provider.grpc;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.specdriven.regression.provider.runtime.*;
import io.grpc.*;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Portable protocol-peer contract checks, not public-product, auth/TLS or Swarm acceptance. */
@Timeout(15)
class GrpcMetadataDeadlineContractTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PUBLIC_SENTINEL = "PUBLIC_SYNTHETIC_SESSION_SENTINEL";
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"cookie", "set-cookie", "authorization"})
    void preservesPublicAsciiMetadataAndMasksCredentialHeaders(String sensitiveHeader) throws Exception {
        try (Peer peer = new Peer("unary", sensitiveHeader)) {
            var result = call(peer, "public-request", "PT1S", List.of(parameter("grpc.metadata.x-client-contract", "public-client")));
            assertThat(result.passed()).isTrue();
            assertThat(peer.calls.get()).isEqualTo(1);
            assertThat(peer.clientHeader.get()).isEqualTo("public-client");
            @SuppressWarnings("unchecked") Map<String, String> metadata = (Map<String, String>) result.outputs().get("response.metadata");
            assertThat(metadata).containsEntry("x-public-contract", "public-response-header");
            assertThat(metadata).containsEntry(sensitiveHeader, "***MASKED***");
            assertThat(JSON.writeValueAsString(result.outputs())).doesNotContain(PUBLIC_SENTINEL);
            Path response = peer.run.resolve("provider-evidence/grpc-client/response.json");
            assertThat(JSON.readTree(response.toFile()).path("metadata").path(sensitiveHeader).asText()).isEqualTo("***MASKED***");
            try (var files = Files.walk(peer.run)) {
                for (Path file : files.filter(Files::isRegularFile).toList())
                    assertThat(Files.readString(file)).as(file.getFileName().toString()).doesNotContain(PUBLIC_SENTINEL);
            }
        }
    }

    @Test
    void serverObservesSubsecondDeadlineAndCancellationAfterWarmup() throws Exception {
        try (Peer peer = new Peer("unary", "set-cookie")) {
            assertThat(call(peer, "warmup", "PT1S", List.of()).passed()).isTrue();
            long start = System.nanoTime();
            var result = call(peer, "slow", "PT0.05S", List.of());
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(result.passed()).isFalse();
            assertThat(result.outputs()).containsEntry("response.status", "DEADLINE_EXCEEDED");
            assertThat(peer.slowAdmitted.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(peer.deadlineNanos.get()).isPositive().isLessThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(100));
            assertThat(peer.cancelled.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(elapsed).isLessThan(500);
            JSON.writeValue(peer.root.resolve("deadline-observation.json").toFile(),
                    Map.of("elapsed_ms", elapsed, "server_remaining_nanos", peer.deadlineNanos.get(), "server_calls", peer.calls.get(), "cancelled", true));
        }
    }

    @Test
    void binaryMetadataRejectsBeforeAnyRpc() throws Exception {
        try (Peer peer = new Peer("unary", "set-cookie")) {
            var result = call(peer, "public-request", "PT1S", List.of(parameter("grpc.metadata.x-contract-bin", "public")));
            assertThat(result.passed()).isFalse();
            assertThat(result.failure().reason()).contains("Binary gRPC metadata");
            assertThat(peer.calls.get()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"client", "server", "both"})
    void streamingDescriptorRejectsBeforeAnyRpc(String mode) throws Exception {
        try (Peer peer = new Peer(mode, "set-cookie")) {
            var result = call(peer, "public-request", "PT1S", List.of());
            assertThat(result.passed()).isFalse();
            assertThat(result.failure().reason()).contains("Unsupported streaming descriptor");
            assertThat(peer.calls.get()).isZero();
        }
    }

    ProviderOperationResult call(Peer peer, String message, String timeout, List<Map<String,Object>> extra) throws Exception {
        List<Map<String,Object>> parameters = new ArrayList<>(List.of(
                parameter("grpc.service", "contract.grpc.EchoService"), parameter("grpc.method", "Echo"),
                parameter("grpc.descriptor_ref", "contract.desc.b64"),
                parameter("grpc.message", JSON.writeValueAsString(Map.of("message", message))), parameter("grpc.timeout", timeout)));
        parameters.addAll(extra);
        var result = new GrpcClientProviderRuntime().execute(
                new ProviderExecutionContext("peer", "grpc_client", "test", "native", peer.root, peer.run,
                        Map.of(), Map.of(), Map.of("target", "127.0.0.1:" + peer.server.getPort())),
                new ProviderOperationRequest("unary_call", parameters, Map.of()));
        // Synthetic peer evidence only. This is not a canonical product report.
        JSON.writeValue(peer.root.resolve("last-operation.json").toFile(),
                Map.of("status", result.status(), "outputs", result.outputs(), "server_calls", peer.calls.get()));
        return result;
    }
    static Map<String,Object> parameter(String key, Object value) { return Map.of("bind_as", key, "ref", value); }

    final class Peer implements AutoCloseable {
        final Path root, run;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> clientHeader = new AtomicReference<>();
        final AtomicLong deadlineNanos = new AtomicLong(-1);
        final CountDownLatch slowAdmitted = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        final ScheduledExecutorService delayed = Executors.newSingleThreadScheduledExecutor();
        final Server server;
        Peer(String streaming, String sensitiveHeader) throws Exception {
            root = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
            run = Files.createDirectory(root.resolve("run"));
            var request = DescriptorProtos.DescriptorProto.newBuilder().setName("Request")
                    .addField(DescriptorProtos.FieldDescriptorProto.newBuilder().setName("message").setNumber(1)
                            .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)).build();
            var response = request.toBuilder().setName("Response").build();
            var rpc = DescriptorProtos.MethodDescriptorProto.newBuilder().setName("Echo")
                    .setInputType(".contract.grpc.Request").setOutputType(".contract.grpc.Response")
                    .setClientStreaming(Set.of("client", "both").contains(streaming))
                    .setServerStreaming(Set.of("server", "both").contains(streaming));
            var proto = DescriptorProtos.FileDescriptorProto.newBuilder().setName("contract.proto").setSyntax("proto3")
                    .setPackage("contract.grpc").addMessageType(request).addMessageType(response)
                    .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder().setName("EchoService").addMethod(rpc)).build();
            Files.write(root.resolve("contract.desc.b64"), Base64.getEncoder().encode(
                    DescriptorProtos.FileDescriptorSet.newBuilder().addFile(proto).build().toByteArray()));
            var file = Descriptors.FileDescriptor.buildFrom(proto, new Descriptors.FileDescriptor[0]);
            var method = file.getServices().get(0).getMethods().get(0);
            var wire = MethodDescriptor.<DynamicMessage,DynamicMessage>newBuilder().setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName("contract.grpc.EchoService", "Echo"))
                    .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getInputType())))
                    .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getOutputType()))).build();
            ServerInterceptor headers = new ServerInterceptor() {
                @Override public <ReqT,RespT> ServerCall.Listener<ReqT> interceptCall(
                        ServerCall<ReqT,RespT> call, Metadata incoming, ServerCallHandler<ReqT,RespT> next) {
                    clientHeader.set(incoming.get(Metadata.Key.of("x-client-contract", Metadata.ASCII_STRING_MARSHALLER)));
                    return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                        @Override public void sendHeaders(Metadata outgoing) {
                            outgoing.put(Metadata.Key.of("x-public-contract", Metadata.ASCII_STRING_MARSHALLER), "public-response-header");
                            outgoing.put(Metadata.Key.of(sensitiveHeader, Metadata.ASCII_STRING_MARSHALLER), PUBLIC_SENTINEL);
                            super.sendHeaders(outgoing);
                        }
                    }, incoming);
                }
            };
            var definition = ServerServiceDefinition.builder("contract.grpc.EchoService").addMethod(wire,
                    ServerCalls.<DynamicMessage,DynamicMessage>asyncUnaryCall((message, observer) -> {
                        calls.incrementAndGet();
                        Runnable respond = () -> {
                            if (!Context.current().isCancelled()) {
                                observer.onNext(DynamicMessage.newBuilder(method.getOutputType())
                                        .setField(method.getOutputType().findFieldByName("message"), "public-response").build());
                                observer.onCompleted();
                            }
                        };
                        if (message.getField(method.getInputType().findFieldByName("message")).equals("slow")) {
                            Context ctx = Context.current();
                            deadlineNanos.set(ctx.getDeadline().timeRemaining(TimeUnit.NANOSECONDS));
                            ctx.addListener(ignored -> cancelled.countDown(), Runnable::run);
                            slowAdmitted.countDown();
                            delayed.schedule(ctx.wrap(respond), 750, TimeUnit.MILLISECONDS);
                        } else respond.run();
                    })).build();
            try {
                server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)).directExecutor()
                        .addService(ServerInterceptors.intercept(definition, headers)).build().start();
            } catch (Exception e) { delayed.shutdownNow(); throw e; }
        }
        @Override public void close() throws Exception {
            server.shutdownNow();
            delayed.shutdownNow();
            boolean serverStopped = server.awaitTermination(2, TimeUnit.SECONDS);
            boolean executorStopped = delayed.awaitTermination(2, TimeUnit.SECONDS);
            JSON.writeValue(root.resolve("cleanup.json").toFile(),
                    Map.of("server_terminated", serverStopped, "executor_terminated", executorStopped, "server_calls", calls.get()));
            assertThat(serverStopped).isTrue();
            assertThat(executorStopped).isTrue();
        }
    }

    @AfterEach void retainBoundedEvidence() throws Exception {
        Path output = Path.of("target/grpc-recovered-contract").resolve(UUID.randomUUID().toString());
        Files.createDirectories(output);
        long total = 0;
        try (var paths = Files.walk(temp)) {
            for (Path source : paths.filter(Files::isRegularFile).toList()) {
                total += Files.size(source);
                assertThat(total).isLessThan(1024 * 1024);
                Path destination = output.resolve(temp.relativize(source));
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
            }
        }
    }
}

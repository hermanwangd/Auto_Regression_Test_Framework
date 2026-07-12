package com.specdriven.regression.provider.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.specdriven.regression.cli.RegressionCommand;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrpcClientExternalEndpointSupportTest {

    private static final Path EXTERNAL_GRPC_SUITE =
            Path.of("samples/20-provider-capability-p0/rpc/grpc_client_external");

    @TempDir
    Path tempDir;

    @Test
    void consumesProjectProvidedExternalTargetThroughV03ExternalSuite() throws Exception {
        Path suite = mutableExternalGrpcSuite();
        Descriptors.FileDescriptor fileDescriptor = customerDescriptor(suite.getParent());
        Descriptors.ServiceDescriptor service = fileDescriptor.findServiceByName("CustomerService");
        Descriptors.MethodDescriptor method = service.findMethodByName("GetCustomer");
        Descriptors.FieldDescriptor requestCustomerId = method.getInputType().findFieldByName("customer_id");
        Descriptors.FieldDescriptor responseCustomerId = method.getOutputType().findFieldByName("customer_id");
        Descriptors.FieldDescriptor responseStatus = method.getOutputType().findFieldByName("status");
        AtomicReference<DynamicMessage> seenRequest = new AtomicReference<>();
        Server server = NettyServerBuilder.forPort(0)
                .directExecutor()
                .addService(ServerServiceDefinition.builder(service.getFullName())
                        .addMethod(grpcMethod(service, method), ServerCalls.asyncUnaryCall((request, observer) -> {
                            seenRequest.set(request);
                            observer.onNext(DynamicMessage.newBuilder(method.getOutputType())
                                    .setField(responseCustomerId, request.getField(requestCustomerId))
                                    .setField(responseStatus, "ACTIVE")
                                    .build());
                            observer.onCompleted();
                        }))
                        .build())
                .build()
                .start();
        try {
            Path envProfile = suite.getParent().resolve("env_profiles/external_native.yaml");
            Files.writeString(envProfile, Files.readString(envProfile)
                    .replace("target: env://GRPC_TARGET", "target: 127.0.0.1:" + server.getPort()));

            CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "external_native");

            assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
            assertThat(run.stdout())
                    .contains("run_status: passed")
                    .contains("provider_runtime_executed: true")
                    .contains("provider_type: grpc_client")
                    .contains("profile: external_native")
                    .doesNotContain("provider_type: grpc_mock");
            assertThat(seenRequest.get()).isNotNull();
            assertThat(seenRequest.get().getField(requestCustomerId)).isEqualTo("C-EXTERNAL-1001");

            Path resultJson = extractPath(run.stdout(), "result_json");
            Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
            assertThat(Files.readString(resultJson))
                    .contains("\"provider_type\": \"grpc_client\"")
                    .contains("\"profile\": \"external_native\"")
                    .contains("\"runtime_mode\": \"native\"")
                    .contains("\"response.status\": \"OK\"")
                    .doesNotContain("grpc_mock");
            assertThat(Files.readString(evidenceDir.resolve("provider-evidence/grpc-client/request.json")))
                    .contains("C-EXTERNAL-1001");
            assertThat(Files.readString(evidenceDir.resolve("provider-evidence/grpc-client/response.json")))
                    .contains("\"status\":\"OK\"");
        } finally {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void blocksExternalV03SuiteBeforeDispatchWhenTargetIsMissing() throws Exception {
        Path suite = mutableExternalGrpcSuite();
        Path envProfile = suite.getParent().resolve("env_profiles/external_native.yaml");
        String missingEnv = "REGRESS_TEST_MISSING_GRPC_TARGET";
        Files.writeString(envProfile, Files.readString(envProfile)
                .replace("env://GRPC_TARGET", "env://" + missingEnv));

        CommandResult dryRun = execute("run", "--suite", suite.toString(), "--profile", "external_native", "--dry-run");

        assertThat(dryRun.exit()).isEqualTo(1);
        assertThat(dryRun.stdout())
                .contains("run_status: blocked")
                .contains("missing_environment_value")
                .contains(missingEnv)
                .doesNotContain("provider_runtime_invoked: true");
    }

    private Path mutableExternalGrpcSuite() throws IOException {
        Path target = tempDir.resolve("grpc_client_external");
        copyDirectory(EXTERNAL_GRPC_SUITE, target);
        return target.resolve("suite_manifest.yaml");
    }

    private Descriptors.FileDescriptor customerDescriptor(Path suiteRoot) throws Exception {
        byte[] bytes = Base64.getMimeDecoder().decode(Files.readString(suiteRoot.resolve("proto/customer.desc.b64")));
        DescriptorProtos.FileDescriptorProto proto = DescriptorProtos.FileDescriptorSet.parseFrom(bytes).getFile(0);
        return Descriptors.FileDescriptor.buildFrom(proto, new Descriptors.FileDescriptor[0]);
    }

    private MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethod(
            Descriptors.ServiceDescriptor service,
            Descriptors.MethodDescriptor method) {
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(service.getFullName(), method.getName()))
                .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getInputType())))
                .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getOutputType())))
                .build();
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private Path extractPath(String stdout, String key) {
        return stdout.lines()
                .filter(line -> line.startsWith(key + ": "))
                .map(line -> Path.of(line.substring((key + ": ").length()).trim()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing path for " + key + " in:\n" + stdout));
    }

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, new PrintStream(stdout), new PrintStream(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}

package com.specdriven.regression.provider.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Contract fixtures only: no owner, server, channel or RPC is launched by these tests.
 * Full descriptor bytes come unchanged from grpc/grpc952c tracked route_guide_pb2.py.
 * The dummy historical lease is not an operational ownership proof.
 */
class RouteGuideProfileTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "11111111111111111111111111111111";
    private static final String ENDPOINT = "grpc://127.0.0.1:1";
    private static final String PROFILE = "routeguide.getfeature.v1";
    private static final String DESCRIPTOR = "artifact://fixtures/routeguide.desc.b64";
    private static final String REQUEST = "{\"latitude\":407838351,\"longitude\":-746143763}";
    private static final String SOURCE_FDP_SHA = "d8a8aef917b101b9374d015e9f1911edc98e5f94ff4653b0ee859ea047b4d549";
    private static final String SOURCE_FDP = "ChFyb3V0ZV9ndWlkZS5wcm90bxIKcm91dGVndWlkZSIsCgVQb2ludBIQCghsYXRpdHVkZRgBIAEoBRIRCglsb25naXR1ZGUYAiABKAUiSQoJUmVjdGFuZ2xlEh0KAmxvGAEgASgLMhEucm91dGVndWlkZS5Qb2ludBIdCgJoaRgCIAEoCzIRLnJvdXRlZ3VpZGUuUG9pbnQiPAoHRmVhdHVyZRIMCgRuYW1lGAEgASgJEiMKCGxvY2F0aW9uGAIgASgLMhEucm91dGVndWlkZS5Qb2ludCJBCglSb3V0ZU5vdGUSIwoIbG9jYXRpb24YASABKAsyES5yb3V0ZWd1aWRlLlBvaW50Eg8KB21lc3NhZ2UYAiABKAkiYgoMUm91dGVTdW1tYXJ5EhMKC3BvaW50X2NvdW50GAEgASgFEhUKDWZlYXR1cmVfY291bnQYAiABKAUSEAoIZGlzdGFuY2UYAyABKAUSFAoMZWxhcHNlZF90aW1lGAQgASgFMoUCCgpSb3V0ZUd1aWRlEjYKCkdldEZlYXR1cmUSES5yb3V0ZWd1aWRlLlBvaW50GhMucm91dGVndWlkZS5GZWF0dXJlIgASPgoMTGlzdEZlYXR1cmVzEhUucm91dGVndWlkZS5SZWN0YW5nbGUaEy5yb3V0ZWd1aWRlLkZlYXR1cmUiADABEj4KC1JlY29yZFJvdXRlEhEucm91dGVndWlkZS5Qb2ludBoYLnJvdXRlZ3VpZGUuUm91dGVTdW1tYXJ5IgAoARI/CglSb3V0ZUNoYXQSFS5yb3V0ZWd1aWRlLlJvdXRlTm90ZRoVLnJvdXRlZ3VpZGUuUm91dGVOb3RlIgAoATABQjYKG2lvLmdycGMuZXhhbXBsZXMucm91dGVndWlkZUIPUm91dGVHdWlkZVByb3RvUAGiAgNSVEdiBnByb3RvMw==";
    @TempDir Path root;

    @Test void exactGetFeatureInputTupleIsAdmitted() {
        assertThatCode(() -> ReviewedLoopbackGrpcPolicy.validateInputs(inputs("routeguide.RouteGuide", "GetFeature", DESCRIPTOR)))
                .doesNotThrowAnyException();
    }

    @Test void fullTrackedDescriptorAdmitsPointAndPreservesAllMethodsMessagesAndOptions() throws Exception {
        prepare(sourceProto(), REQUEST);
        var fixture = assertDoesNotThrow(() -> ReviewedLoopbackGrpcPolicy.fixture(root, bindings(), false));
        var method = fixture.method();
        assertThat(method.getFullName()).isEqualTo("routeguide.RouteGuide.GetFeature");
        assertThat(method.isClientStreaming()).isFalse();
        assertThat(method.isServerStreaming()).isFalse();
        assertThat(method.getService().getMethods()).extracting(Descriptors.MethodDescriptor::getName)
                .containsExactly("GetFeature", "ListFeatures", "RecordRoute", "RouteChat");
        assertThat(method.getFile().getMessageTypes()).hasSize(5);
        assertThat(ReviewedLoopbackGrpcPolicy.digest(method.getFile().toProto().toByteArray())).isEqualTo(SOURCE_FDP_SHA);
        assertThat(method.getInputType().getFullName()).isEqualTo("routeguide.Point");
        assertThat(method.getInputType().findFieldByName("latitude").getJavaType()).isEqualTo(Descriptors.FieldDescriptor.JavaType.INT);
        assertThat(method.getInputType().findFieldByName("longitude").getJavaType()).isEqualTo(Descriptors.FieldDescriptor.JavaType.INT);
        assertThat(method.getOutputType().getFullName()).isEqualTo("routeguide.Feature");
        assertThat(method.getOutputType().findFieldByName("location").getMessageType().getFullName()).isEqualTo("routeguide.Point");
        assertThat(method.getFile().getOptions().getObjcClassPrefix()).isEqualTo("RTG");
        assertThat(method.getFile().getOptions().getJavaPackage()).isEqualTo("io.grpc.examples.routeguide");
        assertThat(JSON.readTree(fixture.requestJson())).isEqualTo(JSON.readTree(REQUEST));
        assertThat(fixture.lease().get("profile")).isEqualTo(PROFILE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"latitude\":900000000,\"longitude\":1800000000}",
            "{\"latitude\":-900000000,\"longitude\":-1800000000}",
            "{\"latitude\":0,\"longitude\":0}"
    })
    void inclusiveGeographicIntegerBoundsAreAdmitted(String request) throws Exception {
        prepare(sourceProto(), request);
        assertDoesNotThrow(() -> ReviewedLoopbackGrpcPolicy.fixture(root, bindings(), false));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"latitude\":1}", "{\"longitude\":1}",
            "{\"latitude\":1,\"longitude\":2,\"altitude\":3}",
            "{\"latitude\":\"1\",\"longitude\":2}",
            "{\"latitude\":1.5,\"longitude\":2}",
            "{\"latitude\":true,\"longitude\":2}",
            "{\"latitude\":null,\"longitude\":2}",
            "{\"latitude\":2147483648,\"longitude\":2}",
            "{\"latitude\":1,\"longitude\":-2147483649}",
            "{\"latitude\":900000001,\"longitude\":2}",
            "{\"latitude\":-900000001,\"longitude\":2}",
            "{\"latitude\":1,\"longitude\":1800000001}",
            "{\"latitude\":1,\"longitude\":-1800000001}"
    })
    void nonIntegralIncompleteUnknownOverflowAndOutOfRangePointRequestsReject(String request) throws Exception {
        prepare(sourceProto(), request);
        assertThatThrownBy(() -> ReviewedLoopbackGrpcPolicy.fixture(root, bindings(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"removed-stream", "extra-service", "changed-target", "changed-field-type", "changed-options"})
    void anyChangeToTheWholeTrackedDescriptorRejects(String mutation) throws Exception {
        var proto = sourceProto().toBuilder();
        switch (mutation) {
            case "removed-stream" -> proto.getServiceBuilder(0).removeMethod(1);
            case "extra-service" -> proto.addServiceBuilder().setName("Unexpected");
            case "changed-target" -> proto.getServiceBuilder(0).getMethodBuilder(0).setName("Other");
            case "changed-field-type" -> proto.getMessageTypeBuilder(0).getFieldBuilder(0)
                    .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING);
            case "changed-options" -> proto.getOptionsBuilder().setObjcClassPrefix("OTHER");
            default -> throw new AssertionError(mutation);
        }
        prepare(proto.build(), REQUEST);
        assertThatThrownBy(() -> ReviewedLoopbackGrpcPolicy.fixture(root, bindings(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidTargets")
    void streamingForeignCrossProfileUnknownAndTraversalTargetsReject(String service, String method, String descriptor) {
        assertThatThrownBy(() -> ReviewedLoopbackGrpcPolicy.validateInputs(inputs(service, method, descriptor)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidTargets() {
        return Stream.of(
                Arguments.of("routeguide.RouteGuide", "ListFeatures", DESCRIPTOR),
                Arguments.of("routeguide.RouteGuide", "RecordRoute", DESCRIPTOR),
                Arguments.of("routeguide.RouteGuide", "RouteChat", DESCRIPTOR),
                Arguments.of("routeguide.Other", "GetFeature", DESCRIPTOR),
                Arguments.of("helloworld.Greeter", "SayHello", DESCRIPTOR),
                Arguments.of("routeguide.RouteGuide", "GetFeature", "artifact://fixtures/greeter.desc.b64"),
                Arguments.of("routeguide.RouteGuide", "GetFeature", "artifact://fixtures/unknown.desc.b64"),
                Arguments.of("routeguide.RouteGuide", "GetFeature", "artifact://fixtures/../routeguide.desc.b64"));
    }

    private static Map<String, Object> inputs(String service, String method, String descriptor) {
        return Map.of("grpc.service", service, "grpc.method", method, "grpc.descriptor_ref", descriptor,
                "grpc.message", ReviewedLoopbackGrpcPolicy.REQUEST_REF, "grpc.timeout", "PT5S");
    }
    private static Map<String, Object> bindings() {
        return Map.of("target", ENDPOINT, "owner_run_id", OWNER);
    }
    private static DescriptorProtos.FileDescriptorProto sourceProto() throws Exception {
        byte[] source = Base64.getDecoder().decode(SOURCE_FDP);
        assertThat(ReviewedLoopbackGrpcPolicy.digest(source)).isEqualTo(SOURCE_FDP_SHA);
        return DescriptorProtos.FileDescriptorProto.parseFrom(source);
    }
    private void prepare(DescriptorProtos.FileDescriptorProto proto, String request) throws Exception {
        Path fixtures = Files.createDirectories(root.resolve("fixtures"));
        byte[] encoded = Base64.getEncoder().encode(DescriptorProtos.FileDescriptorSet.newBuilder().addFile(proto).build().toByteArray());
        byte[] requestBytes = request.getBytes(StandardCharsets.UTF_8);
        Files.write(fixtures.resolve("routeguide.desc.b64"), encoded);
        Files.write(fixtures.resolve("request.json"), requestBytes);
        Map<String, Object> lease = new LinkedHashMap<>();
        lease.put("version", "reviewed-grpc-lease.v1"); lease.put("profile", PROFILE);
        lease.put("endpoint", ENDPOINT); lease.put("owner_run_id", OWNER);
        lease.put("pid", 1L); lease.put("process_start_instant", "2000-01-01T00:00:00Z");
        lease.put("issued_at", "2000-01-01T00:00:00Z"); lease.put("expires_at", "2000-01-01T00:05:00Z");
        lease.put("request_sha256", ReviewedLoopbackGrpcPolicy.digest(requestBytes));
        lease.put("descriptor_sha256", ReviewedLoopbackGrpcPolicy.digest(encoded));
        Files.write(fixtures.resolve("lease.json"), JSON.writeValueAsBytes(lease));
    }
}

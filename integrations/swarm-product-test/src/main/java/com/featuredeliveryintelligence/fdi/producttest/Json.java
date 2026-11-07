package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.nio.file.Path;

final class Json {
    static final ObjectMapper MAPPER = mapper(262144);
    private static final ObjectMapper GRPC_PAYLOAD_MAPPER = mapper(1024 * 1024);
    private static ObjectMapper mapper(int maximumStringLength) {
        var mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100).maxStringLength(maximumStringLength).build()).build())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        for (var shape : new CoercionInputShape[]{CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean})
            mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        return mapper;
    }
    private Json() {}
    static JsonNode readMetadataUtf8(byte[] raw) throws IOException {
        String decoded=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString();
        return MAPPER.readTree(decoded);
    }
    static JsonNode read(Path file) throws IOException { return MAPPER.readTree(LocalFiles.read(file, 2 * 1024 * 1024)); }
    /** Only a digest-bound finite gRPC handoff admits the larger canonical payload budget. */
    static boolean reviewedGrpc(ProductTestRequest request) throws IOException {
        if (!"integration".equals(request.testLevel())) return false;
        byte[] handoffBytes = LocalFiles.read(Path.of(request.reviewedHandoff()), 2 * 1024 * 1024);
        if (!request.expectedHandoffSha256().equals(LocalFiles.sha256(handoffBytes)))
            throw new IOException("Reviewed handoff digest mismatch");
        JsonNode contracts = MAPPER.readTree(handoffBytes).path("reviewed_contracts");
        return contracts.isArray() && contracts.size() == 1
                && "grpc_client.v0.3".equals(contracts.get(0).path("provider_contract").asText());
    }
    static JsonNode readCanonical(Path file, ProductTestRequest request) throws IOException {
        return reviewedGrpc(request)
                ? GRPC_PAYLOAD_MAPPER.readTree(LocalFiles.read(file, 8 * 1024 * 1024)) : read(file);
    }
    static JsonNode readGrpcProof(Path file) throws IOException {
        return GRPC_PAYLOAD_MAPPER.readTree(LocalFiles.read(file, 2 * 1024 * 1024));
    }
    static String required(JsonNode node, String name) {
        if (!node.path(name).isTextual() || node.path(name).asText().isBlank())
            throw new IllegalArgumentException("Missing textual field: " + name);
        return node.path(name).asText();
    }
}

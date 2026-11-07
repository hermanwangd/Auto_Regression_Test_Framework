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
    static final ObjectMapper MAPPER = mapper();
    private static ObjectMapper mapper() {
        var mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100).maxStringLength(262144).build()).build())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        for (var shape : new CoercionInputShape[]{CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean})
            mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        return mapper;
    }
    private Json() {}
    static JsonNode read(Path file) throws IOException { return MAPPER.readTree(LocalFiles.read(file, 2 * 1024 * 1024)); }
    static String required(JsonNode node, String name) {
        if (!node.path(name).isTextual() || node.path(name).asText().isBlank())
            throw new IllegalArgumentException("Missing textual field: " + name);
        return node.path(name).asText();
    }
}

package com.specdriven.regression.provider.mongodb;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

/** Accept ordinary bounded JSON objects, including nested arrays, as BSON fixture data. */
public final class MongoFixtureDocument {
    private static final int MAX_BYTES = 64 * 1024;
    private static final Pattern CRITERION_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,63}");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private MongoFixtureDocument() {}

    public static BsonDocument read(Path path) throws IOException {
        byte[] bytes;
        try (var stream = Files.newInputStream(path)) {
            bytes = stream.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("MongoDB document exceeds 64 KiB.");
        JsonNode document = JSON.readTree(bytes);
        if (document == null || !document.isObject()) throw new IllegalArgumentException("Expected a JSON object.");
        validate(document);
        return BsonDocument.parse(document.toString());
    }

    /** Compare each top-level payload field on the server, including its BSON type. */
    public static BsonDocument equalityExpression(BsonDocument criteria) {
        if (criteria.isEmpty() || criteria.size() > 16) throw new IllegalArgumentException("Expected 1–16 criteria.");
        var comparisons = new BsonArray();
        criteria.forEach((field, value) -> {
            if (!CRITERION_FIELD.matcher(field).matches() || value.isDocument() || value.isArray()) {
                throw new IllegalArgumentException("Expected named scalar equality criteria.");
            }
            var path = new BsonString("$payload." + field);
            var literal = new BsonDocument("$literal", value);
            // MongoDB normally compares numeric types by value. Exact type also distinguishes missing from null.
            comparisons.add(equal(new BsonDocument("$type", path), new BsonDocument("$type", literal)));
            comparisons.add(equal(path, literal));
        });
        return new BsonDocument("$and", comparisons);
    }

    private static BsonDocument equal(BsonValue left, BsonValue right) {
        return new BsonDocument("$eq", new BsonArray(List.of(left, right)));
    }

    private static void validate(JsonNode value) {
        for (Iterator<String> keys = value.fieldNames(); keys.hasNext();) {
            String key = keys.next();
            if (key.startsWith("$") || key.contains(".") || key.indexOf(0) >= 0) {
                throw new IllegalArgumentException("MongoDB fixture field name is not supported.");
            }
        }
        if ((value.isIntegralNumber() && !value.canConvertToLong())
                || (value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue()))) {
            throw new IllegalArgumentException("MongoDB fixture number is outside the supported range.");
        }
        value.forEach(MongoFixtureDocument::validate);
    }
}

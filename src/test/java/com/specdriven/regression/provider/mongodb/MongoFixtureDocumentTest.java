package com.specdriven.regression.provider.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MongoFixtureDocumentTest {
    @TempDir Path root;

    @Test void readsBoundedOrdinaryJsonIncludingNestedDataAndNulls() throws Exception {
        var json = Files.writeString(root.resolve("fixture"), "{\"items\":[{\"enabled\":false},null],\"id\":4294967296}");
        var payload = MongoFixtureDocument.read(json);
        assertThat(payload.getArray("items").get(1).isNull()).isTrue();
        assertThat(payload.getInt64("id").getValue()).isEqualTo(4294967296L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{\"a.b\":1}", "{\"x\":{\"$set\":12}}", "{\"x\":1,\"x\":2}",
            "{} {}", "{\"x\":1234567890123456789012345}", "{\"x\":2e1000}"})
    void rejectsInvalidOrAmbiguousDocuments(String content) throws Exception {
        var json = Files.writeString(root.resolve("fixture"), content);
        assertThatThrownBy(() -> MongoFixtureDocument.read(json)).isInstanceOfAny(IllegalArgumentException.class, java.io.IOException.class);
    }

    @Test void rejectsDeepAndOversizedDocuments() throws Exception {
        var json = Files.writeString(root.resolve("fixture"), "{\"nested\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}");
        assertThatThrownBy(() -> MongoFixtureDocument.read(json)).isInstanceOf(java.io.IOException.class);
        Files.writeString(json, "{\"s\":\"" + "x".repeat(65536) + "\"}");
        assertThatThrownBy(() -> MongoFixtureDocument.read(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void acceptsExactlySixteenPredicatesAndRejectsASeventeenth() {
        var criteria = new BsonDocument();
        for (int index = 0; index < 16; index++) {
            criteria.put("field" + index, new BsonInt32(index));
        }
        assertThat(MongoFixtureDocument.equalityExpression(criteria).getArray("$and").size()).isEqualTo(32);
        criteria.put("extra", new BsonInt32(0));
        assertThatThrownBy(() -> MongoFixtureDocument.equalityExpression(criteria))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

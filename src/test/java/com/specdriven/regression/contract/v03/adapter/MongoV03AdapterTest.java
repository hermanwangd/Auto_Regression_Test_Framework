package com.specdriven.regression.contract.v03.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ResolvedTarget;
import com.specdriven.regression.contract.v03.ref.V03ReferenceResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class MongoV03AdapterTest {
    private static final String URI = "mongodb://client:credential-marker@localhost:27017/fixture";
    @TempDir Path root;
    MongoClient client = mock();
    MongoDatabase database = mock();
    MongoCollection<BsonDocument> collection = mock();
    FindIterable<BsonDocument> query = mock();
    MongoV03Adapter adapter = new MongoV03Adapter(settings -> client);

    @BeforeEach void prepare() throws Exception {
        Files.writeString(root.resolve("order.json"), "{\"name\":\"payload-marker\",\"items\":[1,2]}");
        when(client.getDatabase("fixture")).thenReturn(database);
        when(database.getCollection("swarm_fixture_documents", BsonDocument.class)).thenReturn(collection);
        when(collection.find(any(Bson.class))).thenReturn(query);
        when(query.projection(any(Bson.class))).thenReturn(query);
    }

    @Test void retriesUseTheSameOwnedFilterAndAnotherRunUsesADifferentDocument() throws Exception {
        var ctx = context("run-one", "env://MONGODB_URI", Map.of("MONGODB_URI", URI));
        var seed = step("mongo_seed", Map.of("key", "order", "document_ref", "artifact://fixtures/order.json"));
        when(collection.replaceOne(any(Bson.class), any(BsonDocument.class), any(ReplaceOptions.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        var first = adapter.execute(seed, ctx);
        assertThat(first.outputs()).containsEntry("acknowledged", true);
        assertThat(adapter.execute(seed, ctx).passed()).isTrue();
        assertThat(adapter.execute(seed, context("run-two", "env://MONGODB_URI", Map.of("MONGODB_URI", URI))).passed()).isTrue();
        var filters = ArgumentCaptor.forClass(BsonDocument.class);
        var docs = ArgumentCaptor.forClass(BsonDocument.class);
        var options = ArgumentCaptor.forClass(ReplaceOptions.class);
        verify(collection, times(3)).replaceOne(filters.capture(), docs.capture(), options.capture());
        assertThat(options.getAllValues()).allMatch(ReplaceOptions::isUpsert);
        var firstFilter = filters.getAllValues().get(0);
        assertThat(firstFilter.keySet()).containsExactlyInAnyOrder("_id", "owner");
        assertThat(filters.getAllValues().get(1)).isEqualTo(firstFilter);
        assertThat(filters.getAllValues().get(2).get("_id")).isNotEqualTo(firstFilter.get("_id"));
        var stored = docs.getAllValues().get(0);
        assertThat(first.outputs().get("document_id")).isEqualTo(stored.getString("_id").getValue());
        assertThat(stored.get("owner")).isEqualTo(firstFilter.get("owner"));
        assertThat(stored.getDocument("payload").getString("name").getValue()).isEqualTo("payload-marker");

        when(query.first()).thenReturn(stored);
        var read = step("mongo_read", Map.of("key", "order", "expected_ref", "artifact://fixtures/order.json"));
        var readResult = adapter.execute(read, ctx);
        assertThat(readResult.passed()).isTrue();
        assertThat(readResult.outputs()).containsEntry("matched", true);
        var exists = adapter.execute(step("mongo_exists", Map.of("key", "order")), ctx);
        assertThat(exists.outputs()).containsEntry("found", true);
        verify(collection, times(2)).find(firstFilter);

        when(collection.deleteOne(firstFilter)).thenReturn(DeleteResult.acknowledged(1L), DeleteResult.acknowledged(0L));
        var cleanup = step("mongo_cleanup", Map.of("key", "order"));
        assertThat(adapter.execute(cleanup, ctx).outputs()).containsEntry("deleted_count", 1L);
        assertThat(adapter.execute(cleanup, ctx).outputs()).containsEntry("deleted_count", 0L);
        verify(client, times(7)).close();
        for (var receipt : List.of(first, readResult, exists)) {
            assertThat(receipt.evidenceRefs().get(0)).startsWith("provider-evidence/mongodb/");
            String evidence = Files.readString(ctx.runDir().resolve(receipt.evidenceRefs().get(0)));
            assertThat(evidence).contains("swarm-").doesNotContain("credential-marker", "payload-marker", "localhost");
        }
    }

    @Test void differentiatesAbsentDocumentAndWrongBsonPayloadAndRedactsDriverErrors() throws Exception {
        var ctx = context("run", "env://MONGODB_URI", Map.of("MONGODB_URI", URI));
        var read = step("mongo_read", Map.of("key", "order", "expected_ref", "artifact://fixtures/order.json"));
        when(query.first()).thenReturn(BsonDocument.parse("{payload:{items:[2,1]}}")).thenReturn(null);
        var mismatch = adapter.execute(read, ctx);
        assertThat(mismatch.failureCode()).isEqualTo("MONGO_DOCUMENT_MISMATCH");
        assertThat(mismatch.outputs()).containsEntry("found", true).containsEntry("matched", false);
        var missing = adapter.execute(step("mongo_exists", Map.of("key", "order")), ctx);
        assertThat(missing.failureCode()).isEqualTo("MONGO_DOCUMENT_MISSING");
        when(collection.deleteOne(any(Bson.class))).thenThrow(new MongoException("credential-marker"));
        var failed = adapter.execute(step("mongo_cleanup", Map.of("key", "order")), ctx);
        assertThat(failed.failureCode()).isEqualTo("MONGO_OPERATION_FAILED");
        assertThat(failed.message()).doesNotContain("credential-marker");
        assertThat(Files.readString(ctx.runDir().resolve(failed.evidenceRefs().get(0)))).doesNotContain("credential-marker");
    }

    @Test void evaluatesTypedLiteralCriteriaInMongoWithBothOwnershipKeys() throws Exception {
        Files.writeString(root.resolve("criteria.json"), "{\"status\":\"$literal-marker\",\"quantity\":3,\"note\":null}");
        var find = step("mongo_find", Map.of("key", "order", "criteria_ref", "artifact://fixtures/criteria.json"));
        var ctx = context("run", "env://MONGODB_URI", Map.of("MONGODB_URI", URI));
        when(collection.countDocuments(any(Bson.class), any(CountOptions.class))).thenReturn(1L, 0L);
        var hit = adapter.execute(find, ctx);
        assertThat(hit.outputs()).containsExactlyEntriesOf(Map.of("matched", true));
        var other = context("other-run", "env://MONGODB_URI", Map.of("MONGODB_URI", URI));
        var miss = adapter.execute(find, other);
        assertThat(miss.passed()).isTrue();
        assertThat(miss.outputs()).containsExactlyEntriesOf(Map.of("matched", false));
        var filters = ArgumentCaptor.forClass(BsonDocument.class);
        var options = ArgumentCaptor.forClass(CountOptions.class);
        verify(collection, times(2)).countDocuments(filters.capture(), options.capture());
        var sent = filters.getAllValues().get(0);
        var otherFilter = filters.getAllValues().get(1);
        assertThat(sent.keySet()).containsExactlyInAnyOrder("_id", "owner", "$expr");
        assertThat(sent.get("_id")).isNotEqualTo(otherFilter.get("_id"));
        assertThat(sent.get("owner")).isNotEqualTo(otherFilter.get("owner"));
        assertThat(sent.get("$expr")).isEqualTo(BsonDocument.parse("""
                {"$and":[
                  {"$eq":[{"$type":"$payload.status"},{"$type":{"$literal":"$literal-marker"}}]},
                  {"$eq":["$payload.status",{"$literal":"$literal-marker"}]},
                  {"$eq":[{"$type":"$payload.quantity"},{"$type":{"$literal":3}}]},
                  {"$eq":["$payload.quantity",{"$literal":3}]},
                  {"$eq":[{"$type":"$payload.note"},{"$type":{"$literal":null}}]},
                  {"$eq":["$payload.note",{"$literal":null}]}
                ]}
                """));
        assertThat(options.getValue().getLimit()).isEqualTo(1);
        assertThat(options.getValue().getCollation().getLocale()).isEqualTo("simple");
        assertThat(Files.readString(ctx.runDir().resolve(hit.evidenceRefs().get(0)))).doesNotContain("$literal-marker");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"x\":[1]}", "{\"x\":{\"y\":1}}", "{\"x\":{\"$gt\":1}}",
            "{\"$where\":true}", "{\"score.value\":1}", "{\"\":0}", "{\"x\":1,\"x\":2}", "{incomplete"})
    void rejectsEmptyMalformedNestedOrOperatorCriteriaBeforeConnecting(String contents) throws Exception {
        Files.writeString(root.resolve("criteria.json"), contents);
        var find = step("mongo_find", Map.of("key", "order", "criteria_ref", "artifact://fixtures/criteria.json"));
        var ctx = context("run", "env://MONGODB_URI", Map.of("MONGODB_URI", URI));
        assertThat(adapter.execute(find, ctx).failureCode()).isEqualTo("MONGO_INPUT_INVALID");
        verify(client, never()).getDatabase(any(String.class));
    }

    @Test void rejectsUntrustedEndpointsKeysAndOperatorsBeforeOpeningAnyConnection() throws Exception {
        var exists = step("mongo_exists", Map.of("key", "order"));
        assertThat(adapter.execute(exists, context("run", URI, Map.of())).failureCode()).isEqualTo("SECRET_RESOLUTION_ERROR");
        assertThat(adapter.execute(exists, context("run", "env://OTHER_DATABASE", Map.of("OTHER_DATABASE", URI))).failureCode())
                .isEqualTo("SECRET_RESOLUTION_ERROR");
        assertThat(adapter.execute(exists, context("run", "env://MONGODB_URI", Map.of())).failureCode()).isEqualTo("SECRET_RESOLUTION_ERROR");
        var ctx = context("run", "env://MONGODB_URI", Map.of("MONGODB_URI", URI));
        assertThat(adapter.execute(step("mongo_cleanup", Map.of("key", "order", "filter", Map.of())), ctx).failureCode())
                .isEqualTo("MONGO_INPUT_INVALID");
        assertThat(adapter.execute(step("mongo_cleanup", Map.of("key", Map.of("$ne", "other"))), ctx).failureCode())
                .isEqualTo("MONGO_INPUT_INVALID");
        Files.writeString(root.resolve("order.json"), "{\"nested\":[{\"$gt\":1}]}");
        var seed = step("mongo_seed", Map.of("document_ref", "artifact://fixtures/order.json", "key", "order"));
        assertThat(adapter.execute(seed, ctx).failureCode()).isEqualTo("MONGO_INPUT_INVALID");
        verify(client, never()).getDatabase(any(String.class));
    }

    private V03ExecutionContext context(String run, String connectionRef, Map<String, String> env) {
        var target = new V03ResolvedTarget("store", "mongodb.v0.3", "mongodb", "external", "native",
                Map.of("connection.secret_ref", connectionRef));
        return new V03ExecutionContext(root, root.resolve(run), "external", Map.of("store", target),
                Map.of("fixtures", root), Map.of(), Map.of(), List.of(), Map.of(), env, new V03ReferenceResolver(), Instant.now());
    }

    private V03ExecutionStep step(String operation, Map<String, Object> inputs) {
        String phase = switch (operation) {
            case "mongo_seed" -> "setup";
            case "mongo_read", "mongo_find" -> "execute";
            case "mongo_cleanup" -> "cleanup";
            default -> "verify";
        };
        return new V03ExecutionStep("case", phase, operation, "store", "mongodb.v0.3", "mongodb",
                "external", "native", operation, inputs, "");
    }
}

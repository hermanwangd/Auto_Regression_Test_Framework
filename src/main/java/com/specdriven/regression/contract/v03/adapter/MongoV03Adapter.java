package com.specdriven.regression.contract.v03.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03PlanCanonicalizer;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.contract.v03.ref.V03Reference;
import com.specdriven.regression.contract.v03.ref.V03ReferenceParser;
import com.specdriven.regression.provider.mongodb.MongoFixtureDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;

/** Operates on one framework-owned document in the database selected by the runner. */
public final class MongoV03Adapter implements V03ProviderRuntimeAdapter {
    private static final String COLLECTION = "swarm_fixture_documents";
    private static final String ENV = "MONGODB_URI";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Map<String, Set<String>> INPUTS = Map.of(
            "mongo_seed", Set.of("key", "document_ref"),
            "mongo_read", Set.of("key", "expected_ref"),
            "mongo_find", Set.of("key", "criteria_ref"),
            "mongo_exists", Set.of("key"),
            "mongo_cleanup", Set.of("key"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private final V03PlanCanonicalizer canonicalizer = new V03PlanCanonicalizer();
    private final V03ReferenceParser references = new V03ReferenceParser();
    private final Function<MongoClientSettings, MongoClient> clients;

    public MongoV03Adapter() {
        this(MongoClients::create);
    }

    MongoV03Adapter(Function<MongoClientSettings, MongoClient> clients) {
        this.clients = clients;
    }

    @Override public String providerType() { return "mongodb"; }

    @Override public boolean supports(String contract, String operation) {
        return "mongodb.v0.3".equals(contract) && INPUTS.containsKey(operation);
    }

    @Override public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        if (!supports(step.providerContract(), step.operation()) || !"native".equals(step.runtimeMode())) {
            return failure(step, "MONGO_INPUT_INVALID", "Use an operation from the native MongoDB contract.");
        }
        V03StepResult result;
        String documentId = "";
        try {
            Object key = step.inputs().get("key");
            if (!INPUTS.get(step.operation()).equals(step.inputs().keySet())
                    || !(key instanceof String label) || !KEY.matcher(label).matches()) {
                throw new IllegalArgumentException();
            }
            BsonDocument payload = switch (step.operation()) {
                case "mongo_seed" -> fixture("document_ref", step, context);
                case "mongo_read" -> fixture("expected_ref", step, context);
                default -> null;
            };
            String runId = context.runDir().getFileName().toString();
            String owner = canonicalizer.digest(List.of(runId, step.testCaseId(), step.target()));
            documentId = "swarm-" + canonicalizer.digest(List.of(owner, label));
            // Both keys are strings constructed by the provider. A colliding foreign _id cannot be updated or deleted.
            BsonDocument filter = new BsonDocument("_id", new BsonString(documentId)).append("owner", new BsonString(owner));
            if ("mongo_find".equals(step.operation())) {
                BsonDocument criteria = fixture("criteria_ref", step, context);
                filter.append("$expr", MongoFixtureDocument.equalityExpression(criteria));
            }
            result = invoke(step, context, filter, payload);
        } catch (IllegalArgumentException | IOException | UncheckedIOException error) {
            result = failure(step, "MONGO_INPUT_INVALID", "Check the fixture key and bounded JSON artifact.");
        }
        return record(step, context, documentId, result);
    }

    private BsonDocument fixture(String name, V03ExecutionStep step, V03ExecutionContext context) throws IOException {
        if (!(references.parse(step.inputs().get(name)) instanceof V03Reference.Artifact artifact)
                || !artifact.jsonPointer().isEmpty()) {
            throw new IllegalArgumentException();
        }
        var path = context.referenceResolver().artifactPath(artifact, context.referenceContext(step.testCaseId()));
        return MongoFixtureDocument.read(path);
    }

    private V03StepResult invoke(V03ExecutionStep step, V03ExecutionContext context, BsonDocument filter,
            BsonDocument payload) {
        var target = context.targets().get(step.target());
        if (target == null || !Map.of("connection.secret_ref", "env://" + ENV).equals(target.bindings())) {
            return failure(step, "SECRET_RESOLUTION_ERROR", "Bind the MongoDB URI using env://MONGODB_URI.");
        }
        String uri = context.environment().get(ENV);
        if (uri == null || uri.isBlank()) {
            return failure(step, "SECRET_RESOLUTION_ERROR", "Set the environment variable for the MongoDB connection.");
        }
        try {
            ConnectionString endpoint = new ConnectionString(uri);
            if (endpoint.getDatabase() == null || endpoint.getDatabase().isBlank()) {
                return failure(step, "MONGO_CONFIGURATION_INVALID", "Include the fixture database in the MongoDB URI.");
            }
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(endpoint)
                    .timeout(5, TimeUnit.SECONDS)
                    .applyToConnectionPoolSettings(pool -> pool.minSize(0).maxSize(1))
                    .writeConcern(WriteConcern.ACKNOWLEDGED)
                    .readPreference(ReadPreference.primary())
                    .build();
            try (MongoClient client = clients.apply(settings)) {
                var collection = client.getDatabase(endpoint.getDatabase()).getCollection(COLLECTION, BsonDocument.class);
                return operate(step, collection, filter, payload);
            }
        } catch (MongoException error) {
            return failure(step, "MONGO_OPERATION_FAILED", "Check MongoDB availability and the fixture database permissions.");
        } catch (IllegalArgumentException error) {
            return failure(step, "MONGO_CONFIGURATION_INVALID", "Check the runner-supplied MongoDB URI and database.");
        }
    }

    private static V03StepResult operate(V03ExecutionStep step, MongoCollection<BsonDocument> collection,
            BsonDocument filter, BsonDocument payload) {
        return switch (step.operation()) {
            case "mongo_seed" -> {
                var written = collection.replaceOne(filter, filter.clone().append("payload", payload),
                        new ReplaceOptions().upsert(true));
                yield written.wasAcknowledged() ? passed(step, Map.of("acknowledged", true,
                                "document_id", filter.getString("_id").getValue()))
                        : failure(step, "MONGO_OPERATION_FAILED", "MongoDB did not acknowledge the write.");
            }
            case "mongo_read" -> {
                var actual = collection.find(filter).projection(new BsonDocument("payload", new BsonInt32(1))).first();
                boolean matched = actual != null && payload.equals(actual.get("payload"));
                var outputs = Map.<String, Object>of("found", actual != null, "matched", matched);
                yield matched ? passed(step, outputs)
                        : new V03StepResult(step.id(), "failed", outputs, List.of(), "MONGO_DOCUMENT_MISMATCH",
                                "The owned document is missing or differs from the expected JSON fixture.");
            }
            case "mongo_find" -> {
                var options = new CountOptions().limit(1).collation(Collation.builder().locale("simple").build());
                yield passed(step, Map.of("matched", collection.countDocuments(filter, options) == 1));
            }
            case "mongo_exists" -> {
                boolean found = collection.find(filter).projection(new BsonDocument("_id", new BsonInt32(1))).first() != null;
                var outputs = Map.<String, Object>of("found", found);
                yield found ? passed(step, outputs)
                        : new V03StepResult(step.id(), "failed", outputs, List.of(), "MONGO_DOCUMENT_MISSING",
                                "The owned MongoDB document was not found.");
            }
            case "mongo_cleanup" -> {
                var deletion = collection.deleteOne(filter);
                yield deletion.wasAcknowledged() ? passed(step, Map.of("deleted_count", deletion.getDeletedCount()))
                        : failure(step, "MONGO_OPERATION_FAILED", "MongoDB did not acknowledge cleanup.");
            }
            default -> throw new IllegalArgumentException();
        };
    }

    private V03StepResult record(V03ExecutionStep step, V03ExecutionContext context, String documentId, V03StepResult result) {
        String ref = "provider-evidence/mongodb/" + canonicalizer.digest(List.of(step.testCaseId(), step.target(), step.id())) + ".json";
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("operation", step.operation());
        evidence.put("document_id", documentId);
        evidence.put("status", result.status());
        evidence.put("outputs", result.outputs());
        evidence.put("failure_code", result.failureCode());
        try {
            var path = context.runDir().resolve(ref);
            Files.createDirectories(path.getParent());
            JSON.writeValue(path.toFile(), evidence);
        } catch (IOException error) {
            return failure(step, "EVIDENCE_INVALID", "Could not write the MongoDB operation evidence.");
        }
        return new V03StepResult(step.id(), result.status(), result.outputs(), List.of(ref), result.failureCode(), result.message());
    }

    private static V03StepResult passed(V03ExecutionStep step, Map<String, Object> outputs) {
        return new V03StepResult(step.id(), "passed", outputs, List.of(), "", "");
    }

    private static V03StepResult failure(V03ExecutionStep step, String code, String reason) {
        return new V03StepResult(step.id(), "failed", Map.of(), List.of(), code, reason);
    }
}

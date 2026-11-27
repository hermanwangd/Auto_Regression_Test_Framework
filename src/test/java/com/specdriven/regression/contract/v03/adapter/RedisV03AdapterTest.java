package com.specdriven.regression.contract.v03.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ResolvedTarget;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.contract.v03.ref.V03ReferenceResolver;
import com.specdriven.regression.provider.redis.OwnedRedisStrings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;

class RedisV03AdapterTest {
    private static final String ENV = "FRAMEWORK_REDIS_URI";
    private static final Map<String, Object> BINDING = Map.of("connection.secret_ref", "env://" + ENV);
    private final RedisV03Adapter adapter = new RedisV03Adapter();
    @TempDir Path run;

    @Test void rejectsUnknownCommandsAndUnboundedOrNonliteralInputsBeforeConnecting() {
        var context = context(BINDING, Map.of());
        assertThat(adapter.supports("redis.v0.3", "redis_set")).isTrue();
        assertThat(adapter.supports("redis.v0.3", "redis_absent")).isTrue();
        assertThat(adapter.supports("redis.v0.3", "flushdb")).isFalse();
        assertThat(adapter.supports("redis.v0.2", "redis_get")).isFalse();
        for (Object seconds : List.of(-1, 0, 1.5, "1", 3601)) {
            assertThat(step(context, "redis_set", Map.of("key", "x", "value", "data", "ttl_seconds", seconds)).failureCode())
                    .isEqualTo("REDIS_INVALID_INPUT");
        }
        assertThat(step(context, "redis_get", Map.of("key", "*", "expected_value", "x")).failureCode()).isEqualTo("REDIS_INVALID_INPUT");
        assertThat(step(context, "redis_get", Map.of("key", "x", "expected_value", "界".repeat(6000))).failureCode())
                .isEqualTo("REDIS_INVALID_INPUT");
        assertThat(step(context, "redis_delete", Map.of("key", "x", "pattern", "*")).failureCode()).isEqualTo("REDIS_INVALID_INPUT");
    }

    @Test void admitsOnlyTheFixedRunnerUriAndKeepsCredentialsOutOfFailuresAndEvidence() throws Exception {
        String secret = "synthetic-private-credential";
        String unsafe = "http://user:" + secret + "@localhost:6379/";
        var invalid = step(context(BINDING, Map.of(ENV, unsafe)), "redis_delete", Map.of("key", "a"));
        assertThat(invalid.failureCode()).isEqualTo("REDIS_CONNECTION_CONFIG");
        assertThat(invalid.message()).doesNotContain(secret, unsafe);
        assertThat(Files.readString(run.resolve(invalid.evidenceRefs().get(0)))).doesNotContain(secret, unsafe);
        var swapped = context(Map.of("connection.secret_ref", "env://OTHER_URI"), Map.of("OTHER_URI", "redis://127.0.0.1:6379/0"));
        assertThat(step(swapped, "redis_delete", Map.of("key", "a")).failureCode()).isEqualTo("REDIS_CONNECTION_CONFIG");
        for (String uri : List.of("redis://host:0", "redis://host:65536", "redis://user@host:1", "redis://host:1234/-1", "redis://host:1/0?command=del")) {
            assertThat(RedisV03Adapter.endpoint(uri)).isNull();
        }
        assertThat(RedisV03Adapter.endpoint("rediss://example.org:6379/0")).isNotNull();
    }

    @Test void runTestTargetAndAliasEachSeparatePhysicalKeys() {
        var current = new OwnedRedisStrings("run-1", "case-1", "cache", "x");
        assertThat(current.physicalKey()).isEqualTo(new OwnedRedisStrings("run-1", "case-1", "cache", "x").physicalKey());
        assertThat(current.physicalKey()).isNotIn(
                new OwnedRedisStrings("run-2", "case-1", "cache", "x").physicalKey(),
                new OwnedRedisStrings("run-1", "case-2", "cache", "x").physicalKey(),
                new OwnedRedisStrings("run-1", "case-1", "other", "x").physicalKey(),
                new OwnedRedisStrings("run-1", "case-1", "cache", "y").physicalKey());
    }

    @Test @EnabledIfEnvironmentVariable(named = ENV, matches = ".+")
    void checksExactUtf8ValueTtlOwnershipAndIdempotentCleanupOnStandaloneRedis() throws Exception {
        try (var observer = independentClient()) {
            var context = context(BINDING, Map.of(ENV, System.getenv(ENV)));
            String key = new OwnedRedisStrings(run.getFileName().toString(), "case", "cache", "order").physicalKey();
            String value = "資料-😀-private-test-data";
            Map<String,Object> create = Map.of("key", "order", "value", value, "ttl_seconds", 90);
            Map<String,Object> alias = Map.of("key", "order");
            // The independent connection verifies real string and TTL semantics on the wire.
            var write = step(context, "redis_set", create);
            assertThat(write.passed()).as(write.message()).isTrue();
            assertThat(write.outputs()).containsEntry("physical_key", key);
            try {
                assertThat(observer.get(key)).isEqualTo(value);
                assertThat(step(context, "redis_set", create).failureCode()).isEqualTo("REDIS_OWNERSHIP_CONFLICT");
                var match = step(context, "redis_get", Map.of("key", "order", "expected_value", value));
                assertThat(match.outputs()).containsEntry("matched", true);
                assertThat(Files.readString(run.resolve(match.evidenceRefs().get(0)))).doesNotContain(value);
                assertThat(step(context, "redis_get", Map.of("key", "order", "expected_value", "資料")).failureCode())
                        .isEqualTo("REDIS_VALUE_MISMATCH");
                var ttl = step(context, "redis_ttl", Map.of("key", "order", "max_ttl_seconds", 90));
                assertThat((Long) ttl.outputs().get("remaining_ms")).isBetween(0L, 90_000L);
                assertThat(step(context, "redis_ttl", Map.of("key", "order", "max_ttl_seconds", 1)).failureCode())
                        .isEqualTo("REDIS_TTL_MISMATCH");

                observer.setex(key, 90, "replacement");
                assertThat(step(context, "redis_delete", alias).failureCode()).isEqualTo("REDIS_OWNERSHIP_CONFLICT");
                assertThat(observer.get(key)).isEqualTo("replacement");
                observer.setex(key, 90, value);
                assertThat(step(context, "redis_delete", alias).outputs()).containsEntry("deleted", true);
                assertThat(observer.exists(key)).isFalse();
                assertThat(step(context, "redis_delete", alias).outputs()).containsEntry("deleted", false).containsEntry("absent", true);
                assertThat(step(context, "redis_get", Map.of("key", "order", "expected_value", "")).failureCode())
                        .isEqualTo("REDIS_KEY_MISSING");
            } finally {
                // The server still expires the data and ownership record if this test aborts.
                step(context, "redis_delete", alias);
            }
        }
    }

    @Test @EnabledIfEnvironmentVariable(named = ENV, matches = ".+")
    void detectsApplicationInvalidationAndCleansTheRemainingOwnerRecord() {
        var context = context(BINDING, Map.of(ENV, System.getenv(ENV)));
        Map<String, Object> alias = Map.of("key", "cached_order");
        try (var application = independentClient()) {
            var setup = step(context, "redis_set", Map.of("key", "cached_order", "value", "cached", "ttl_seconds", 120));
            assertThat(setup.passed()).as(setup.message()).isTrue();
            String physicalKey = (String) setup.outputs().get("physical_key");
            String ownerKey = physicalKey.substring(0, physicalKey.lastIndexOf(':') + 1) + "owner";
            try {
                var present = step(context, "redis_absent", alias);
                assertThat(present.failureCode()).isEqualTo("REDIS_KEY_PRESENT");
                assertThat(present.outputs()).containsEntry("absent", false);
                assertThat(application.del(physicalKey)).isEqualTo(1L);
                var absent = step(context, "redis_absent", alias);
                assertThat(absent.passed()).as(absent.message()).isTrue();
                assertThat(absent.outputs()).containsEntry("absent", true);
                assertThat(application.exists(ownerKey)).isTrue();
                assertThat(step(context, "redis_delete", alias).outputs()).containsEntry("absent", true);
                assertThat(application.exists(ownerKey)).isFalse();
                assertThat(step(context, "redis_absent", alias).outputs()).containsEntry("absent", true);
            } finally {
                step(context, "redis_delete", alias);
            }
        }
    }

    private static Jedis independentClient() {
        var uri = RedisV03Adapter.endpoint(System.getenv(ENV));
        assertThat(uri).isNotNull();
        var tls = new javax.net.ssl.SSLParameters();
        tls.setEndpointIdentificationAlgorithm("HTTPS");
        return new Jedis(uri, 2000, 2000, null, tls, null);
    }

    private V03StepResult step(V03ExecutionContext context, String operation, Map<String,Object> inputs) {
        String phase = switch (operation) {
            case "redis_delete" -> "cleanup";
            case "redis_absent" -> "verify";
            default -> "execute";
        };
        return adapter.execute(new V03ExecutionStep("case", phase, operation, "cache", "redis.v0.3", "redis",
                "external_redis", "native", operation, inputs, ""), context);
    }

    private V03ExecutionContext context(Map<String,Object> binding, Map<String,String> environment) {
        var target = new V03ResolvedTarget("cache", "redis.v0.3", "redis", "external_redis", "native", binding);
        return new V03ExecutionContext(run, run, "external_redis", Map.of("cache", target), Map.of(), Map.of(), Map.of(),
                List.of(), Map.of(), environment, new V03ReferenceResolver(ignored -> Map.of()), Instant.now());
    }
}

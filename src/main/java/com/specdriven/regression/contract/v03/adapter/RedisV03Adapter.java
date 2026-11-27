package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03PlanCanonicalizer;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.ProviderDataYaml;
import com.specdriven.regression.provider.redis.OwnedRedisStrings;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.net.ssl.SSLParameters;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;

public final class RedisV03Adapter implements V03ProviderRuntimeAdapter {
    private static final V03PlanCanonicalizer HASH = new V03PlanCanonicalizer();
    private static final String ENV = "FRAMEWORK_REDIS_URI";
    private static final int TIMEOUT_MS = 2000;
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Map<String, Set<String>> INPUTS = Map.of(
            "redis_set", Set.of("key", "value", "ttl_seconds"),
            "redis_get", Set.of("key", "expected_value"),
            "redis_ttl", Set.of("key", "max_ttl_seconds"),
            "redis_absent", Set.of("key"),
            "redis_delete", Set.of("key"));

    @Override public String providerType() { return "redis"; }
    @Override public boolean supports(String contract, String operation) {
        return "redis.v0.3".equals(contract) && INPUTS.containsKey(operation);
    }

    @Override public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        if (!supports(step.providerContract(), step.operation()) || !"native".equals(step.runtimeMode())) {
            return failure(step, "REDIS_INVALID_INPUT", "Use a declared native Redis operation.");
        }
        OwnedRedisStrings fixture = null;
        V03StepResult result;
        try {
            Map<String, Object> inputs = step.inputs();
            if (!INPUTS.get(step.operation()).equals(inputs.keySet()) || !(inputs.get("key") instanceof String alias)
                    || !ALIAS.matcher(alias).matches()) throw new IllegalArgumentException();
            String value = switch (step.operation()) {
                case "redis_set" -> value(inputs.get("value"));
                case "redis_get" -> value(inputs.get("expected_value"));
                default -> "";
            };
            int seconds = switch (step.operation()) {
                case "redis_set" -> seconds(inputs.get("ttl_seconds"));
                case "redis_ttl" -> seconds(inputs.get("max_ttl_seconds"));
                default -> 0;
            };
            fixture = new OwnedRedisStrings(context.runDir().getFileName().toString(), step.testCaseId(), step.target(), alias);
            var target = context.targets().get(step.target());
            URI uri = target != null && Map.of("connection.secret_ref", "env://" + ENV).equals(target.bindings())
                    ? endpoint(context.environment().get(ENV)) : null;
            if (uri == null) {
                result = failure(step, "REDIS_CONNECTION_CONFIG", "Supply a standalone Redis URI in " + ENV + " through its fixed environment binding.");
            } else {
                SSLParameters tls = new SSLParameters();
                tls.setEndpointIdentificationAlgorithm("HTTPS");
                try (Jedis redis = new Jedis(uri, TIMEOUT_MS, TIMEOUT_MS, null, tls, null)) {
                    result = run(step, redis, fixture, value, seconds);
                }
            }
        } catch (JedisConnectionException error) {
            result = failure(step, "PROVIDER_UNAVAILABLE", "Redis connection failed or timed out.");
        } catch (JedisException error) {
            result = failure(step, "REDIS_OPERATION_FAILED", "Redis rejected the fixture operation. Check server permissions and availability.");
        } catch (IllegalArgumentException error) {
            result = failure(step, "REDIS_INVALID_INPUT", "Check the key alias, value size and integer TTL bounds.");
        }
        return evidence(step, context, fixture, result);
    }

    static URI endpoint(String raw) {
        if (raw == null || raw.length() > 4096) return null;
        try {
            URI uri = URI.create(raw);
            if (!("redis".equals(uri.getScheme()) || "rediss".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getUserInfo() != null && !uri.getUserInfo().contains(":"))
                    || !uri.getRawPath().matches("(/([0-9]{1,5})?)?")) return null;
            return uri;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String value(Object raw) {
        if (!(raw instanceof String text) || text.length() > OwnedRedisStrings.MAX_VALUE_BYTES
                || text.getBytes(StandardCharsets.UTF_8).length > OwnedRedisStrings.MAX_VALUE_BYTES) {
            throw new IllegalArgumentException();
        }
        return text;
    }

    private static int seconds(Object raw) {
        if (!(raw instanceof Integer || raw instanceof Long)) throw new IllegalArgumentException();
        long value = ((Number) raw).longValue();
        if (value < 1 || value > 3600) throw new IllegalArgumentException();
        return (int) value;
    }

    private static V03StepResult run(V03ExecutionStep step, Jedis redis, OwnedRedisStrings fixture, String value, int seconds) {
        long observed = switch (step.operation()) {
            case "redis_set" -> fixture.create(redis, value, seconds);
            case "redis_get" -> fixture.matches(redis, value);
            case "redis_ttl", "redis_absent" -> fixture.remainingTtl(redis);
            case "redis_delete" -> fixture.delete(redis);
            default -> throw new IllegalArgumentException();
        };
        if (observed == OwnedRedisStrings.NOT_OWNED) {
            return failure(step, "REDIS_OWNERSHIP_CONFLICT", "The key exists already or its ownership or stored value changed.");
        }
        if ("redis_absent".equals(step.operation())) {
            if (observed == OwnedRedisStrings.MISSING) return passed(step, Map.of("absent", true));
            return new V03StepResult(step.id(), "failed", Map.of("absent", false), List.of(),
                    "REDIS_KEY_PRESENT", "The run-owned data key is still present.");
        }
        if (observed == OwnedRedisStrings.MISSING && !"redis_delete".equals(step.operation())) {
            return failure(step, "REDIS_KEY_MISSING", "The run-owned key is absent or has expired.");
        }
        return switch (step.operation()) {
            case "redis_set" -> passed(step, Map.of("created", true, "physical_key", fixture.physicalKey()));
            case "redis_get" -> observed == 1 ? passed(step, Map.of("matched", true))
                    : failure(step, "REDIS_VALUE_MISMATCH", "The stored value differs from the expected value.");
            case "redis_ttl" -> observed >= 0 && observed <= seconds * 1000L
                    ? passed(step, Map.of("remaining_ms", observed, "within_bound", true))
                    : failure(step, "REDIS_TTL_MISMATCH", "The observed time to live falls outside the requested bound.");
            case "redis_delete" -> passed(step, Map.of("deleted", observed == 1, "absent", true));
            default -> throw new IllegalArgumentException();
        };
    }

    private static V03StepResult passed(V03ExecutionStep step, Map<String, Object> outputs) {
        return new V03StepResult(step.id(), "passed", outputs, List.of(), "", "");
    }

    private static V03StepResult failure(V03ExecutionStep step, String code, String message) {
        return new V03StepResult(step.id(), "failed", Map.of(), List.of(), code, message);
    }

    private static V03StepResult evidence(V03ExecutionStep step, V03ExecutionContext context, OwnedRedisStrings fixture, V03StepResult result) {
        String suffix = HASH.digest(List.of(step.testCaseId(), step.id()));
        String name = "redis_delete".equals(step.operation()) ? "cleanup" : step.operation();
        String ref = "provider-evidence/redis/" + name + "-" + suffix + ".yaml";
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("operation", step.operation());
        fields.put("status", result.status());
        if (fixture != null) fields.put("physical_key", fixture.physicalKey());
        fields.putAll(result.outputs());
        if (!result.passed()) fields.put("failure_code", result.failureCode());
        try {
            ProviderDataYaml.writeEvidence(context.runDir().resolve(ref), fields);
            return new V03StepResult(step.id(), result.status(), result.outputs(), List.of(ref), result.failureCode(), result.message());
        } catch (IOException error) {
            return failure(step, "EVIDENCE_INVALID", "Redis fixture evidence could not be written.");
        }
    }
}

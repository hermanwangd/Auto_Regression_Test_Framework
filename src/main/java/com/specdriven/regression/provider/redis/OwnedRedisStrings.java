package com.specdriven.regression.provider.redis;

import com.specdriven.regression.contract.v03.V03PlanCanonicalizer;
import java.util.List;
import java.util.UUID;
import redis.clients.jedis.Jedis;

/** Fixed server-side operations keep each ownership check atomic with its read or mutation. */
public final class OwnedRedisStrings {
    public static final int MAX_VALUE_BYTES = 16 * 1024;
    public static final long NOT_OWNED = -3;
    public static final long MISSING = -2;

    private static final V03PlanCanonicalizer HASH = new V03PlanCanonicalizer();
    private static final UUID PROCESS_NONCE = UUID.randomUUID();
    private static final String CREATE = """
            if redis.call('exists', KEYS[1], KEYS[2]) ~= 0 then return -3 end
            redis.call('set', KEYS[2], ARGV[1] .. ARGV[2], 'EX', ARGV[3])
            redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3])
            return 1
            """;
    private static final String CHECK_OWNER = """
            local present = redis.call('exists', KEYS[1])
            if redis.call('exists', KEYS[2]) == 0 then
                if present == 0 then return -2 end
                return -3
            end
            if redis.call('type', KEYS[2]).ok ~= 'string'
                or redis.call('getrange', KEYS[2], 0, #ARGV[1] - 1) ~= ARGV[1] then return -3 end
            """;
    private static final String MATCH = CHECK_OWNER + """
            if present == 0 then return -2 end
            if redis.call('type', KEYS[1]).ok ~= 'string' then return 0 end
            if redis.call('getrange', KEYS[1], 0, #ARGV[2]) == ARGV[2] then return 1 end
            return 0
            """;
    private static final String TTL = CHECK_OWNER + """
            return redis.call('pttl', KEYS[1])
            """;
    private static final String DELETE = CHECK_OWNER + """
            if present == 1 then
                if redis.call('type', KEYS[1]).ok ~= 'string' then return -3 end
                local original = redis.call('getrange', KEYS[2], #ARGV[1], #ARGV[1] + tonumber(ARGV[2]))
                if #original > tonumber(ARGV[2])
                    or redis.call('getrange', KEYS[1], 0, #original) ~= original then return -3 end
            end
            redis.call('del', KEYS[1], KEYS[2])
            return present
            """;

    private final String physicalKey;
    private final List<String> keys;
    private final String owner;

    public OwnedRedisStrings(String runId, String testId, String target, String alias) {
        String identity = HASH.digest(List.of(runId, testId, target, alias));
        String base = "sdr:redis:{" + identity + "}:";
        physicalKey = base + "value";
        keys = List.of(physicalKey, base + "owner");
        owner = HASH.digest(List.of(PROCESS_NONCE.toString(), identity)) + ":";
    }

    public String physicalKey() { return physicalKey; }

    public long create(Jedis redis, String value, int ttlSeconds) {
        return (long) redis.eval(CREATE, keys, List.of(owner, value, Integer.toString(ttlSeconds)));
    }

    public long matches(Jedis redis, String expectedValue) {
        return (long) redis.eval(MATCH, keys, List.of(owner, expectedValue));
    }

    public long remainingTtl(Jedis redis) {
        return (long) redis.eval(TTL, keys, List.of(owner));
    }

    /** Both keys expiring together is also successful, idempotent cleanup. */
    public long delete(Jedis redis) {
        return (long) redis.eval(DELETE, keys, List.of(owner, Integer.toString(MAX_VALUE_BYTES)));
    }
}

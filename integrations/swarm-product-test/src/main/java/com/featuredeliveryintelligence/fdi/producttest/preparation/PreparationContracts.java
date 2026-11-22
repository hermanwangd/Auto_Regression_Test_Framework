package com.featuredeliveryintelligence.fdi.producttest.preparation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

final class PreparationContracts {
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(50).maxStringLength(262144).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new com.fasterxml.jackson.databind.module.SimpleModule().addSerializer(java.time.Instant.class,
                new JsonSerializer<java.time.Instant>() { @Override public void serialize(java.time.Instant value, com.fasterxml.jackson.core.JsonGenerator out, SerializerProvider provider) throws java.io.IOException { out.writeString(value.toString()); } }));
    private PreparationContracts() {}
    static final class Blocked extends IllegalArgumentException {
        final ContextAcquisition.Reason reason;
        Blocked(ContextAcquisition.Reason reason) { super(reason.name()); this.reason = reason; }
    }
    static Blocked fail(ContextAcquisition.Reason reason) { return new Blocked(reason); }
    static void require(boolean value, ContextAcquisition.Reason reason) { if (!value) throw fail(reason); }
    static String ref(String value) {
        require(value != null && !value.isBlank() && value.length() <= 256 && value.equals(value.trim())
                && value.codePoints().noneMatch(Character::isISOControl), ContextAcquisition.Reason.INVALID_INPUT);
        return value;
    }
    static String owner(String value) {
        require(value != null && !value.isBlank() && !"UNBOUND".equalsIgnoreCase(value), ContextAcquisition.Reason.SOURCE_OWNER_UNBOUND);
        return ref(value);
    }
    static String qualified(String value) {
        ref(value);
        require(value.matches("[^@#\\s]+@[a-f0-9]{40}(?:[a-f0-9]{24})?:[^@#\\s]+#[^@#\\s]+"), ContextAcquisition.Reason.INVALID_INPUT);
        String path = value.substring(value.indexOf(':', value.indexOf('@')) + 1, value.lastIndexOf('#'));
        require(!path.startsWith("/") && !path.contains("\\") && Arrays.stream(path.split("/", -1))
                .noneMatch(v -> v.isEmpty() || v.equals(".") || v.equals("..")), ContextAcquisition.Reason.INVALID_INPUT);
        return value;
    }
    static String sha(String value) { require(value != null && value.matches("[a-f0-9]{64}"), ContextAcquisition.Reason.INVALID_INPUT); return value; }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    private static final class OutputBudgetExceeded extends java.io.IOException {}
    static byte[] bytes(Object value, int limit) {
        require(limit>0&&limit<=4*1024*1024,ContextAcquisition.Reason.INVALID_INPUT);
        var received=new java.io.ByteArrayOutputStream(Math.min(limit,4096));
        var bounded=new java.io.OutputStream() {
            @Override public void write(int value) throws java.io.IOException {
                if(received.size()>=limit)throw new OutputBudgetExceeded();received.write(value);
            }
            @Override public void write(byte[] bytes,int offset,int length) throws java.io.IOException {
                if((long)received.size()+length>limit)throw new OutputBudgetExceeded();received.write(bytes,offset,length);
            }
        };
        try { JSON.writeValue(bounded,value);return received.toByteArray(); }
        catch (Blocked e) { throw e; }
        catch (Exception e) {
            Throwable cause=e;
            for(int depth=0;cause!=null&&depth<20;depth++,cause=cause.getCause())
                if(cause instanceof OutputBudgetExceeded)throw fail(ContextAcquisition.Reason.OUTPUT_LIMIT);
            throw fail(ContextAcquisition.Reason.INVALID_INPUT);
        }
    }
    static JsonNode read(byte[] bytes, int limit) {
        require(bytes != null && bytes.length > 0 && bytes.length <= limit, ContextAcquisition.Reason.OUTPUT_LIMIT);
        try {
            String decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode value = JSON.readTree(decoded); require(value != null && value.isObject(), ContextAcquisition.Reason.INVALID_INPUT); return value;
        } catch (Blocked e) { throw e; } catch (Exception e) { throw fail(ContextAcquisition.Reason.INVALID_INPUT); }
    }
    static JsonNode canonical(JsonNode value) {
        if (value.isObject()) { var sorted = JSON.createObjectNode(); var keys = new TreeSet<String>(); value.fieldNames().forEachRemaining(keys::add); keys.forEach(k -> sorted.set(k, canonical(value.get(k)))); return sorted; }
        if (value.isArray()) { var out = JSON.createArrayNode(); value.forEach(v -> out.add(canonical(v))); return out; }
        return value.deepCopy();
    }
    static String canonicalHash(JsonNode value) { return hash(bytes(canonical(value), 4 * 1024 * 1024)); }
}

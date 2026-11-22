package com.specdriven.regression.contract;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.report.LegacySuiteSummaryReportAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** Result documents only. Metadata, configuration, evidence-index and request readers keep their own limits. */
public final class ResultDocumentReader {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int MAX_STRING_LENGTH = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(MAX_STRING_LENGTH)
                    .maxNestingDepth(100).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Document(String text, Map<String, Object> fields) {}
    private ResultDocumentReader() {}

    /** Strict JSON plus the existing recognized legacy YAML result formats. */
    public static Document read(Path file) throws IOException {
        byte[] bytes = bounded(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        try { return new Document(text, object(JSON.readValue(bytes, Object.class))); }
        catch (JsonProcessingException error) {
            // A malformed JSON object must not shed modern/duplicate markers through a permissive YAML parse.
            if (jsonShaped(text)) throw new IOException("Invalid or oversized result JSON", error);
            Object value;
            try { value = new Yaml().load(text); }
            catch (RuntimeException invalid) { throw new IOException("Invalid legacy result document", invalid); }
            Map<String, Object> fields = object(value);
            boolean oldMarkers = !fields.containsKey("result_contract_version") && !fields.containsKey("product_test");
            boolean legacy = oldMarkers && ("v0.2".equals(fields.get("dsl_version"))
                    || (!fields.containsKey("dsl_version") && new LegacySuiteSummaryReportAdapter().supports(fields)));
            if (!legacy) throw new IOException("Modern result documents require strict JSON", error);
            return new Document(text, fields);
        }
    }

    /** Canonical projection never falls back to legacy YAML. */
    public static Document readJson(Path file) throws IOException {
        byte[] bytes = bounded(file);
        return new Document(new String(bytes, StandardCharsets.UTF_8), object(JSON.readValue(bytes, Object.class)));
    }

    private static byte[] bounded(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES)
            throw new IOException("Missing or oversized result JSON");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Result JSON exceeds byte limit");
            return bytes;
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
    private static boolean jsonShaped(String text) {
        String start = text.stripLeading();
        if (start.startsWith("\ufeff")) start = start.substring(1).stripLeading();
        if (start.startsWith("[")) return true;
        if (!start.startsWith("{")) return false;
        String object = start.substring(1).stripLeading();
        return object.startsWith("\"") || object.startsWith("}");
    }
}

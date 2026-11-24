package com.specdriven.regression.contract.v03;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.specdriven.regression.contract.SecurityFieldNames;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Applies Provider Contract output sensitivity before values leave runtime memory. */
public final class V03OutputRedactor {
    public static final String MASKED = "[MASKED]";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectReader JSON_LABEL = JSON.readerFor(String.class);
    private static final int MAX_EMBEDDED_JSON_DEPTH = 8;
    private static final int MAX_DIAGNOSTIC_CHARS = 64 * 1024;

    private static final String WORD = "[\\p{L}\\p{M}\\p{N}\\p{So}\\p{Pc}\\p{Cf}.．\\-－﹣]++";
    private static final String SEPARATOR = "[:=：＝﹕﹦]";
    private static final String SPACING = "[\\h\\s]";
    private static final String ASSIGN = SPACING + "*" + SEPARATOR + SPACING + "*";
    private static final Pattern SINGLE_WORD = Pattern.compile(WORD);
    private static final Pattern MESSAGE_LABEL = Pattern.compile("(?<quote>[\"'＂＇])|(?<separator>" + SEPARATOR + ")|" + WORD);
    private static final Pattern ASSIGNMENT = Pattern.compile(ASSIGN);
    private static final Pattern WORD_THEN_SPACE = Pattern.compile("(" + WORD + ")" + SPACING + "++");
    private static final Pattern SPACE = Pattern.compile(SPACING + "++");
    private static final Pattern TOKEN_CONTINUATION = Pattern.compile(
            "[\\t ]*\\r?\\n[\\t ]+[A-Za-z0-9._~+/-]++=*+(?=[\\t ]*(?:\\r?$)|[\\t ]+[\\w.-]+[:=])",
            Pattern.MULTILINE);
    private static final Pattern RAW_VALUE = Pattern.compile("[^\\s,;\"'}]++");
    private static final Pattern RAW_ADDRESS = Pattern.compile("[^\\s,;]++");
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN ((?:[A-Z0-9-]++ )*?PRIVATE KEY)-----.*?(?:-----END \\1-----|\\z)", Pattern.DOTALL);
    private static final Pattern AUTHORITY = Pattern.compile("[/／]{2}([^/\\s]++)");

    private static final Set<String> USER_INFO_SCHEMES = Set.of("http", "https", "grpc", "grpcs", "kafka", "amqp", "amqps");

    public Map<String, Object> redact(V03ExecutionPlan plan, V03ExecutionStep step, Map<String, Object> outputs) {
        V03ProviderContract contract = plan.providerContracts().get(step.providerContract());
        V03ProviderContract.V03OperationDefinition operation = contract == null
                ? null : contract.operations().get(step.operation());
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> output : outputs.entrySet()) {
            if (operation == null || !operation.outputDefinitions().containsKey(output.getKey())) {
                continue;
            }
            V03OutputDefinition definition = operation.outputDefinitions().get(output.getKey());
            result.put(output.getKey(), definition.sensitivity() == V03Sensitivity.PUBLIC && !SecurityFieldNames.isCredential(output.getKey())
                    ? redactValue(output.getValue())
                    : MASKED);
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public Object redactAssertionValue(
            V03ExecutionPlan plan,
            V03ExecutionStep assertion,
            Object raw,
            Object value,
            Map<V03StepOutputKey, V03ProducedOutput> stepOutputs) {
        if (raw instanceof String ref && ref.startsWith("step://")) {
            int path = ref.indexOf('/', "step://".length());
            if (path >= 0 && java.util.Arrays.stream(ref.substring(path + 1).split("[./#]+"))
                    .anyMatch(SecurityFieldNames::isCredential)) return MASKED;
        }
        V03OutputDefinition definition = outputDefinitionForReference(assertion, raw, stepOutputs);
        if (definition != null && definition.sensitivity() != V03Sensitivity.PUBLIC) {
            return MASKED;
        }
        return redactValue(value);
    }

    private V03OutputDefinition outputDefinitionForReference(
            V03ExecutionStep assertion,
            Object raw,
            Map<V03StepOutputKey, V03ProducedOutput> stepOutputs) {
        if (!(raw instanceof String reference)) return null;
        if (reference.startsWith("step://")) {
            String body = reference.substring("step://".length());
            int slash = body.indexOf('/');
            if (slash < 1) return null;
            String stepId = body.substring(0, slash);
            String output = body.substring(slash + 1).split("#", 2)[0];
            V03ProducedOutput produced = producedOutput(stepOutputs, assertion.testCaseId(), stepId, output);
            if (produced != null) return new V03OutputDefinition(
                    produced.valueType(), produced.sensitivity(), produced.bindable(), true);
            return null;
        }
        return null;
    }

    private V03ProducedOutput producedOutput(
            Map<V03StepOutputKey, V03ProducedOutput> stepOutputs, String testCaseId, String stepId, String outputPath) {
        try {
            return new V03OutputDefinitionResolver().resolveProducedPath(
                    stepOutputs.entrySet().stream()
                            .filter(entry -> entry.getKey().testCaseId().equals(testCaseId))
                            .filter(entry -> entry.getKey().stepId().equals(stepId))
                            .map(Map.Entry::getValue).toList(),
                    outputPath).output();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public String redactMessage(String message) {
        return maskMessage(message, ContractFields.NONE);
    }

    String redactMessage(String message, Set<String> contractKeys) {
        return maskMessage(message, ContractFields.of(contractKeys));
    }

    private static String maskMessage(String message, ContractFields fields) {
        return maskMessage(message, fields, MASKED);
    }

    /** Uses the provider evidence marker while applying the same diagnostic field boundaries. */
    public static String redactProviderDiagnostic(String message) {
        return maskMessage(message, ContractFields.NONE, "***MASKED***");
    }

    private static String maskMessage(String message, ContractFields fields, String marker) {
        if (message == null) return "";
        return maskMessage(message, fields, marker, 0);
    }

    private static String maskMessage(String message, ContractFields fields, String marker, int depth) {
        if (message.length() > MAX_DIAGNOSTIC_CHARS) return marker;
        if (looksLikeJsonContainer(message) && (message.contains("\\\"") || message.contains("\\u"))) {
            message = maskEmbeddedJson(message, fields, marker, depth);
        }
        if (message.contains("-----BEGIN ")) message = PRIVATE_KEY_BLOCK.matcher(message).replaceAll(marker);
        Matcher labels = MESSAGE_LABEL.matcher(message);
        Matcher assignment = ASSIGNMENT.matcher(message);
        StringBuilder masked = new StringBuilder();
        int copied = 0;
        int lastSeparator = 0;
        // Bound overlapping quoted-label scans, including escaped nested JSON envelopes.
        long lookaheadBudget = 32L * message.length();
        while (labels.find()) {
            String quoted = null;
            int after = labels.end();
            if (labels.group("quote") != null) {
                int end = endOfLabel(message, after, message.charAt(labels.start()));
                lookaheadBudget -= end - after;
                if (lookaheadBudget < 0) return marker;
                if (end == message.length() || message.charAt(end) != message.charAt(labels.start())
                        || !assignment.region(end + 1, message.length()).lookingAt()) continue;
                after = end + 1;
                quoted = message.substring(labels.start(), after);
            }
            String key = SecurityFieldNames.canonical(quoted == null ? labels.group() : quotedLabel(quoted));
            Span value = null;
            String replacement = marker;
            if (labels.group("separator") != null) {
                if (fields.matchesUnquotedSuffix(message, copied, lastSeparator, labels.start())) {
                    value = assignmentValue(message, matchEnd(SPACE, message, after), false);
                }
                lastSeparator = after;
            } else if (assignment.region(after, message.length()).lookingAt()
                    && (SecurityFieldNames.endsWithCredentialLabel(key) || fields.paths().contains(key))) {
                int start = assignment.end();
                int scalarEnd = quoted != null && quoted.charAt(0) == '"' ? jsonScalarEnd(message, start) : start;
                if (quoted != null && start < message.length() && (message.charAt(start) == '[' || message.charAt(start) == '{')) {
                    value = new Span(start, endOfContainer(message, start));
                    replacement = "\"" + marker + "\"";
                } else if (scalarEnd > start) {
                    value = new Span(start, scalarEnd);
                    replacement = "\"" + marker + "\"";
                } else if (SecurityFieldNames.isCookieLabel(key) && start < message.length()) {
                    if (quoted != null && isQuote(message.charAt(start))) {
                        value = rawValue(message, start);
                    } else {
                        // A Cookie header may contain multiple pairs, quoted values and folded lines.
                        value = new Span(start, headerEnd(message, start));
                    }
                } else {
                    value = assignmentValue(message, start, key.endsWith("authorization"));
                }
            } else if ("bearer".equals(key)) {
                int token = matchEnd(SPACE, message, after);
                if (token > after) value = rawValue(message, token);
            } else if (after < message.length() && ":：﹕".indexOf(message.charAt(after)) >= 0) {
                value = addressValue(key, message, labels.start(), after + 1);
            }
            if (value == null || value.start() == value.end()) {
                if (quoted != null) labels.region(after, message.length());
                continue;
            }
            masked.append(message, copied, value.start()).append(replacement);
            copied = value.end();
            labels.region(copied, message.length());
        }
        return copied == 0 ? message : masked.append(message, copied, message.length()).toString();
    }

    private record Span(int start, int end) {}

    private static int endOfLabel(String text, int start, char quote) {
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == quote || c == '\r' || c == '\n') break;
            end++;
            if (quote == '"' && c == '\\' && end < text.length()
                    && text.charAt(end) != '\r' && text.charAt(end) != '\n') end++;
        }
        return end;
    }

    private static int jsonScalarEnd(String text, int start) {
        int end = start;
        while (end < text.length() && "-+.0123456789eE".indexOf(text.charAt(end)) >= 0) end++;
        if (end == start) {
            for (String literal : List.of("true", "false", "null")) {
                if (text.startsWith(literal, start)) { end += literal.length(); break; }
            }
        }
        int delimiter = end;
        while (delimiter < text.length() && Character.isWhitespace(text.charAt(delimiter))) delimiter++;
        return delimiter < text.length() && ",}]".indexOf(text.charAt(delimiter)) >= 0 ? end : start;
    }

    private static int headerEnd(String text, int start) {
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == '\r' || c == '\n') {
                int next = end + 1;
                if (c == '\r' && next < text.length() && text.charAt(next) == '\n') next++;
                if (next == text.length() || (text.charAt(next) != ' ' && text.charAt(next) != '\t')) break;
                end = next;
            } else {
                end++;
            }
        }
        return end;
    }

    private static boolean looksLikeJsonContainer(String text) {
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        if (i == text.length()) return false;
        char opener = text.charAt(i++);
        if (opener != '{' && opener != '[') return false;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        if (i == text.length()) return false;
        char first = text.charAt(i);
        return opener == '{' ? first == '"' || first == '}' : "\"{[]-0123456789tfn".indexOf(first) >= 0;
    }

    /** An HTTP response can contain another serialized JSON document as a string value. */
    private static String maskEmbeddedJson(String text, ContractFields fields, String marker, int depth) {
        StringBuilder result = new StringBuilder();
        int copied = 0;
        try (JsonParser parser = JSON.createParser(text)) {
            while (parser.nextToken() != null) {
                if (!parser.hasToken(JsonToken.VALUE_STRING)) continue;
                String value = parser.getText();
                if (!looksLikeJsonContainer(value)) continue;
                String safe = depth >= MAX_EMBEDDED_JSON_DEPTH ? marker : maskMessage(value, fields, marker, depth + 1);
                if (safe.equals(value)) continue;
                int start = Math.toIntExact(parser.currentTokenLocation().getCharOffset());
                int end = Math.toIntExact(parser.currentLocation().getCharOffset());
                result.append(text, copied, start).append(JSON.writeValueAsString(safe));
                copied = end;
            }
        } catch (IOException invalidOrOversizedJson) {
            // An ambiguous encoded envelope must not reach diagnostic evidence with raw inner values.
            return marker;
        }
        return copied == 0 ? text : result.append(text, copied, text.length()).toString();
    }

    private static String quotedLabel(String quoted) {
        if (quoted.charAt(0) == '"' && quoted.indexOf('\\') >= 0) {
            try {
                // Compare the decoded JSON name while keeping verbatim evidence offsets and spelling.
                return JSON_LABEL.readValue(quoted);
            } catch (JsonProcessingException ignored) {
                // Diagnostics may use arbitrary quoted labels outside JSON.
            }
        }
        return quoted.substring(1, quoted.length() - 1);
    }

    private static Span assignmentValue(String text, int start, boolean authorization) {
        if (authorization && start < text.length()) {
            char first = text.charAt(start);
            if (isQuote(first)) return rawValue(text, start);
            if (text.startsWith("***MASKED***", start)) return new Span(start, start + "***MASKED***".length());
            if (text.startsWith(MASKED, start)) return new Span(start, start + MASKED.length());
        }
        Matcher scheme = WORD_THEN_SPACE.matcher(text).region(start, text.length());
        if (scheme.lookingAt()) {
            String name = SecurityFieldNames.canonical(scheme.group(1));
            if (!"bearer".equals(name) && !(authorization && ("basic".equals(name) || "negotiate".equals(name)))) {
                return authorization ? new Span(start, headerEnd(text, start)) : rawValue(text, start);
            }
            Span token = rawValue(text, scheme.end());
            int end = token.end();
            if (end < text.length() && isQuote(text.charAt(scheme.end()))) end++;
            if (authorization) {
                Matcher continuation = TOKEN_CONTINUATION.matcher(text);
                while (continuation.region(end, text.length()).lookingAt()) end = continuation.end();
            }
            return new Span(start, end);
        }
        return authorization ? new Span(start, headerEnd(text, start)) : rawValue(text, start);
    }

    // Only the label is normalized. Compatibility punctuation and spaces within raw values are data.
    private static Span rawValue(String text, int start) {
        if (start < text.length() && isQuote(text.charAt(start))) {
            return new Span(start + 1, endOfQuotedValue(text, start + 1, text.charAt(start)));
        }
        return new Span(start, matchEnd(RAW_VALUE, text, start));
    }

    private static Span addressValue(String scheme, String text, int labelStart, int address) {
        if ("jdbc".equals(scheme) || ("nats".equals(scheme) && startsWithSlashes(text, address))) {
            int end = matchEnd(RAW_ADDRESS, text, address);
            return end == address ? null : new Span(labelStart, end);
        }
        if (!USER_INFO_SCHEMES.contains(scheme)) return null;
        Matcher authority = AUTHORITY.matcher(text).region(address, text.length());
        if (!authority.lookingAt()) return null;
        String raw = authority.group(1);
        int at = Math.max(raw.lastIndexOf('@'), raw.lastIndexOf('＠'));
        return at < 1 ? null : new Span(authority.start(1), authority.start(1) + at);
    }

    private static int endOfContainer(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isQuote(c)) i = endOfQuotedValue(text, i + 1, c);
            else if (c == '[' || c == '{') depth++;
            else if ((c == ']' || c == '}') && --depth == 0) return i + 1;
        }
        return text.length();
    }

    private static boolean startsWithSlashes(String text, int from) {
        return from + 1 < text.length() && "/／".indexOf(text.charAt(from)) >= 0
                && "/／".indexOf(text.charAt(from + 1)) >= 0;
    }

    private static boolean isQuote(char c) {
        return "\"'＂＇".indexOf(c) >= 0;
    }

    private static int endOfQuotedValue(String text, int start, char quote) {
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == quote) {
                if (i + 1 >= text.length() || text.charAt(i + 1) != quote) return i;
                i++;
            }
            if (c == '\\') i++;
        }
        return text.length();
    }

    private static int matchEnd(Pattern pattern, String text, int start) {
        Matcher matcher = pattern.matcher(text).region(start, text.length());
        return matcher.lookingAt() ? matcher.end() : start;
    }

    public Object redactValue(Object value) {
        return redactNested(value);
    }

    public Object redactEvidenceValue(Object value, Set<String> contractKeys) {
        return redactEvidenceNested(value, ContractFields.of(contractKeys), "");
    }

    private record ContractFields(Set<String> paths, List<String> compound, int lookbehind, boolean containsSeparator) {
        private static final ContractFields NONE = new ContractFields(Set.of(), List.of(), 0, false);

        static ContractFields of(Set<String> keys) {
            if (keys.isEmpty()) return NONE;
            Set<String> paths = keys.stream().map(SecurityFieldNames::canonical).collect(Collectors.toSet());
            List<String> compound = paths.stream().filter(k -> !k.isEmpty() && !SINGLE_WORD.matcher(k).matches()).toList();
            // Each original code point contributes at least one decomposed code point and takes at most two UTF-16 units.
            int lookbehind = compound.stream().map(k -> Normalizer.normalize(k, Normalizer.Form.NFKD))
                    .mapToInt(k -> 2 * k.length()).max().orElse(0);
            boolean containsSeparator = compound.stream().anyMatch(k -> k.indexOf(':') >= 0 || k.indexOf('=') >= 0);
            return new ContractFields(paths, compound, lookbehind, containsSeparator);
        }

        boolean matchesUnquotedSuffix(String text, int copied, int previousSeparator, int end) {
            if (compound.isEmpty()) return false;
            int lowerBound = containsSeparator ? copied : Math.max(previousSeparator, copied);
            while (end > lowerBound && isLabelSpacing(text.charAt(end - 1))) end--;
            int start = Math.max(lowerBound, end - lookbehind);
            String candidate = SecurityFieldNames.canonical(text.substring(start, end));
            return compound.stream().anyMatch(candidate::endsWith);
        }

        private static boolean isLabelSpacing(char c) {
            return (c >= '\t' && c <= '\r') || c == '\u180e' || Character.getType(c) == Character.SPACE_SEPARATOR;
        }
    }

    private Object redactEvidenceNested(Object value, ContractFields fields, String path) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                String fieldPath = path.isBlank() ? name : path + "." + name;
                result.put(name, SecurityFieldNames.isCredential(name) || fields.paths().contains(SecurityFieldNames.canonical(fieldPath)) ? MASKED
                        : redactEvidenceNested(item, fields, fieldPath));
            });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> redactEvidenceNested(item, fields, path)).toList();
        return value instanceof String text ? maskMessage(text, fields) : value;
    }

    @SuppressWarnings("unchecked")
    private Object redactNested(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), SecurityFieldNames.isCredential(String.valueOf(key)) ? MASKED : redactNested(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(redactNested(item)));
            return java.util.Collections.unmodifiableList(result);
        }
        if (value instanceof String text) return redactMessage(text);
        return value;
    }
}

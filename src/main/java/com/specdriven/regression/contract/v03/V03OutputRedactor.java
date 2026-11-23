package com.specdriven.regression.contract.v03;

import com.specdriven.regression.contract.SecurityFieldNames;
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

    private static final String WORD = "[\\p{L}\\p{M}\\p{N}\\p{So}\\p{Pc}.．\\-－﹣]++";
    private static final String SEPARATOR = "[:=：＝﹕﹦]";
    private static final String SPACING = "[\\h\\s]";
    private static final String ASSIGN = SPACING + "*" + SEPARATOR + SPACING + "*";
    private static final Pattern SINGLE_WORD = Pattern.compile(WORD);
    private static final Pattern MESSAGE_LABEL = Pattern.compile(
            "(?<quoted>\"[^\"\\r\\n]++\"|'[^'\\r\\n]++'|＂[^＂\\r\\n]++＂|＇[^＇\\r\\n]++＇)(?=" + ASSIGN
                    + ")|(?<separator>" + SEPARATOR + ")|" + WORD);
    private static final Pattern ASSIGNMENT = Pattern.compile(ASSIGN);
    private static final Pattern WORD_THEN_SPACE = Pattern.compile("(" + WORD + ")" + SPACING + "++");
    private static final Pattern SPACE = Pattern.compile(SPACING + "++");
    private static final Pattern HEADER_CONTINUATION = Pattern.compile("[\\t ]*\\r?\\n[\\t ]+[^\\r\\n]*");
    private static final Pattern RAW_VALUE = Pattern.compile("[^\\s,;\"'}]++");
    private static final Pattern RAW_ADDRESS = Pattern.compile("[^\\s,;]++");
    private static final Pattern AUTHORITY = Pattern.compile("[/／]{2}([^/\\s]++)");

    private static final Set<String> USER_INFO_SCHEMES = Set.of("http", "https", "grpc", "grpcs");

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
            result.put(output.getKey(), definition.sensitivity() == V03Sensitivity.PUBLIC
                    ? redactValue(output.getValue())
                    : MASKED);
        }
        return Map.copyOf(result);
    }

    public Object redactAssertionValue(
            V03ExecutionPlan plan,
            V03ExecutionStep assertion,
            Object raw,
            Object value,
            Map<V03StepOutputKey, V03ProducedOutput> stepOutputs) {
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

    private String maskMessage(String message, ContractFields fields) {
        if (message == null) return "";
        Matcher labels = MESSAGE_LABEL.matcher(message);
        Matcher assignment = ASSIGNMENT.matcher(message);
        StringBuilder masked = new StringBuilder();
        int copied = 0;
        int lastSeparator = 0;
        while (labels.find()) {
            String quoted = labels.group("quoted");
            String key = SecurityFieldNames.canonical(quoted == null ? labels.group() : quoted.substring(1, quoted.length() - 1));
            int after = labels.end();
            Span value = null;
            if (labels.group("separator") != null) {
                if (fields.matchesUnquotedSuffix(message, copied, lastSeparator, labels.start())) {
                    value = assignmentValue(message, matchEnd(SPACE, message, after), false);
                }
                lastSeparator = after;
            } else if (assignment.region(after, message.length()).lookingAt()
                    && (SecurityFieldNames.endsWithCredentialLabel(key) || fields.paths().contains(key))) {
                value = assignmentValue(message, assignment.end(), key.endsWith("authorization"));
            } else if ("bearer".equals(key)) {
                int token = matchEnd(SPACE, message, after);
                if (token > after) value = rawValue(message, token);
            } else if (after < message.length() && ":：﹕".indexOf(message.charAt(after)) >= 0) {
                value = addressValue(key, message, labels.start(), after + 1);
            }
            if (value == null || value.start() == value.end()) continue;
            masked.append(message, copied, value.start()).append(MASKED);
            copied = value.end();
            labels.region(copied, message.length());
        }
        return copied == 0 ? message : masked.append(message, copied, message.length()).toString();
    }

    private record Span(int start, int end) {}

    private static Span assignmentValue(String text, int start, boolean authorization) {
        Matcher scheme = WORD_THEN_SPACE.matcher(text).region(start, text.length());
        if (scheme.lookingAt()) {
            String name = SecurityFieldNames.canonical(scheme.group(1));
            if (!"bearer".equals(name) && !(authorization && "basic".equals(name))) return rawValue(text, start);
            Span token = rawValue(text, scheme.end());
            int end = token.end();
            if (end < text.length() && isQuote(text.charAt(scheme.end()))) end++;
            if (authorization) {
                Matcher continuation = HEADER_CONTINUATION.matcher(text);
                while (continuation.region(end, text.length()).lookingAt()) end = continuation.end();
            }
            return new Span(start, end);
        }
        return rawValue(text, start);
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
            return List.copyOf(result);
        }
        if (value instanceof String text) return redactMessage(text);
        return value;
    }
}

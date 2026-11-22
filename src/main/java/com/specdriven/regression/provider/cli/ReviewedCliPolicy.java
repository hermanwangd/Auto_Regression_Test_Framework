package com.specdriven.regression.provider.cli;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finite literal authorization inputs for an explicitly pinned local executable. */
public final class ReviewedCliPolicy {
    public static final int DEFAULT_TIMEOUT_MS = 5000;
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 262144;
    public static final int MAX_STDIN_BYTES = 65536;
    private static final Set<String> INPUTS = Set.of("process.argv", "process.stdin", "process.cwd",
            "process.timeout_ms", "process.max_output_bytes");

    private ReviewedCliPolicy() { }

    public static void validateBindings(Map<String,?> bindings) {
        require(bindings != null && bindings.keySet().equals(Set.of("executable", "executable_sha256")),
                "bindings must contain exactly executable and executable_sha256");
        String executable = literal(bindings.get("executable"), "executable", 4096);
        Path path = path(executable);
        require(path.isAbsolute(), "executable must be an absolute path");
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.isExecutable(path), "executable must be a regular nonsymlink executable file");
        require(bindings.get("executable_sha256") instanceof String hash && hash.matches("[a-f0-9]{64}"),
                "executable_sha256 must contain 64 lowercase hexadecimal characters");
    }

    public static void validateInputs(Map<String,?> inputs) {
        require(inputs != null && inputs.containsKey("process.argv") && INPUTS.containsAll(inputs.keySet()),
                "only argv and optional stdin, cwd, timeout_ms and max_output_bytes are supported");
        require(inputs.get("process.argv") instanceof List<?>, "argv must be a literal string list");
        List<?> argv = (List<?>) inputs.get("process.argv");
        require(argv.size() <= 64, "argv exceeds 64 arguments");
        for (Object argument : argv) literal(argument, "argv element", 4096);
        if (inputs.containsKey("process.stdin")) {
            String stdin = literal(inputs.get("process.stdin"), "stdin", MAX_STDIN_BYTES, false);
            require(utf8(stdin).length <= MAX_STDIN_BYTES, "stdin exceeds 65536 UTF-8 bytes");
        }
        if (inputs.containsKey("process.cwd")) {
            String cwd = literal(inputs.get("process.cwd"), "cwd", 4096);
            Path relative = path(cwd);
            require(!cwd.isBlank() && !relative.isAbsolute() && !relative.normalize().startsWith("..")
                    && (".".equals(cwd) || relative.normalize().toString().equals(cwd)),
                    "cwd must be a normalized contained relative path");
        }
        integer(inputs, "process.timeout_ms", 1, 10000);
        integer(inputs, "process.max_output_bytes", 1, 1048576);
    }

    static byte[] utf8(String value) {
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            byte[] encoded = new byte[bytes.remaining()];
            bytes.get(encoded);
            return encoded;
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("reviewed_cli: literal contains invalid UTF-8 text", error);
        }
    }

    private static String literal(Object value, String field, int limit) {
        return literal(value, field, limit, true);
    }

    private static String literal(Object value, String field, int limit, boolean rejectNul) {
        require(value instanceof String, field + " must be a literal string");
        String text = (String) value;
        require(text.length() <= limit && (!rejectNul || text.indexOf('\0') < 0), field + " exceeds its bound or contains forbidden NUL");
        require(!text.matches("(?s)^(?:artifact|step|generated|env)://.*") && !text.contains("${") && !text.contains("{{"),
                field + " must not contain a dynamic reference or template");
        utf8(text);
        return text;
    }

    private static void integer(Map<String,?> inputs, String key, int minimum, int maximum) {
        if (!inputs.containsKey(key)) return;
        Object value = inputs.get(key);
        require(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long,
                key + " must be an integer");
        long number = ((Number)value).longValue();
        require(number >= minimum && number <= maximum, key + " is outside its finite range");
    }

    private static Path path(String value) {
        try { return Path.of(value); }
        catch (InvalidPathException error) { throw new IllegalArgumentException("reviewed_cli: invalid path", error); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("reviewed_cli: " + message);
    }
}

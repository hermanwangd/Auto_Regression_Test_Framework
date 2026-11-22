package com.specdriven.regression.provider.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.specdriven.regression.provider.runtime.ProviderRuntime;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliProcessProviderRuntimeTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesLiteralArgumentsAndCapturesSeparateStreamsForCompletedNonzeroExit() throws Exception {
        String literal = "a; touch SHOULD_NOT_EXIST $(echo expansion) `echo expansion` | > & *";
        ProviderOperationResult result = run("streams", Map.of("process.argv", args("streams", literal)));
        assertThat(result.passed()).isTrue();
        assertThat(result.outputs()).containsEntry("exit_code", 7).containsEntry("stdout", literal + "\n")
                .containsEntry("stderr", "diagnostic\n");
        assertThat(result.outputs().get("duration_ms")).isInstanceOf(Long.class);
        assertThat(Files.exists(temp.resolve("SHOULD_NOT_EXIST"))).isFalse();
        Map<String,Object> proof = proof(result);
        assertThat(proof).containsEntry("operation_status", "passed").containsEntry("cleanup_status", "complete")
                .containsEntry("executable_sha256", sha(javaBinary())).containsEntry("pin_scope", "executable_only");
        assertThat(proof.get("argv_sha256")).isEqualTo(sha(mapper.writeValueAsBytes(args("streams", literal))));
        assertThat(proof).doesNotContainKeys("argv", "stdin");
    }

    @Test
    void feedsExactUtf8StdinThenClosesItAndDoesNotExposeSuiteOrInheritedEnvironment() throws Exception {
        Files.writeString(temp.resolve("oracle.txt"), "private expected output");
        String input = "line one\n雪\n";
        ProviderOperationResult result = run("stdin", Map.of("process.argv", args("stdin"), "process.stdin", input,
                "process.cwd", "nested/working"));
        assertThat(result.passed()).isTrue();
        assertThat(result.outputs()).containsEntry("stdout", input).containsEntry("stderr", "C/C/false/false/false/false");
        assertThat(proof(result)).containsEntry("stdin_sha256", sha(input.getBytes(StandardCharsets.UTF_8)))
                .containsEntry("environment_policy", "fixed:LANG=C,LC_ALL=C");
        Path cwd = Path.of(String.valueOf(proof(result).get("cwd")));
        assertThat(cwd).startsWith(temp.resolve("runs/stdin"));
        assertThat(cwd.getFileName().toString()).isEqualTo("working");
        assertThat(Files.isDirectory(cwd)).isTrue();
    }

    @Test
    void suppliesImmediateEofWhenStdinIsOmitted() throws Exception {
        ProviderOperationResult result = run("eof", Map.of("process.argv", args("stdin")));
        assertThat(result.passed()).isTrue();
        assertThat(result.outputs()).containsEntry("stdout", "").containsEntry("exit_code", 0);
    }

    @Test
    void failsCombinedOutputOverflowWithoutReturningTruncatedSuccessfulOutput() throws Exception {
        ProviderOperationResult result = run("overflow", Map.of("process.argv", args("combined"), "process.max_output_bytes", 100));
        assertFailed(result, "OPERATION_FAILED");
        assertThat(result.failure().reason()).contains("output");
        assertThat(proof(result)).containsEntry("cleanup_status", "complete");
    }

    @Test
    void usesOneDeadlineForAProcessThatDoesNotReadItsStdin() throws Exception {
        long start = System.nanoTime();
        ProviderOperationResult result = run("blocked-input", Map.of("process.argv", args("hang"),
                "process.stdin", "x".repeat(65536), "process.timeout_ms", 400));
        assertFailed(result, "PROVIDER_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        assertThat(proof(result)).containsEntry("cleanup_status", "complete");
    }

    @Test
    void rejectsInvalidUtf8RatherThanReplacingBytesInSuccessfulObservation() throws Exception {
        ProviderOperationResult result = run("invalid-encoding", Map.of("process.argv", args("invalid")));
        assertFailed(result, "OPERATION_FAILED");
        assertThat(result.failure().reason()).contains("UTF-8");
    }

    @Test
    void failsSecretLikeOutputAndPersistsOnlyMaskedText() throws Exception {
        ProviderOperationResult result = run("secret", Map.of("process.argv", args("secret")));
        assertFailed(result, "SECRET_RESOLUTION_ERROR");
        String evidence = Files.readString(evidenceFile(result));
        assertThat(evidence).contains("***MASKED***").doesNotContain("secret-value-123");
        assertThat(mapper.writeValueAsString(result)).doesNotContain("secret-value-123");
    }

    @Test
    void failsOutputThatTheFrameworkWouldMaskBeforePersistingIt() throws Exception {
        for (String sensitive : List.of("private_key=private-value", "http://username:password@public.invalid/",
                "grpc://username:password@public.invalid/", "jdbc:postgresql://public.invalid/database",
                "nats://username:password@public.invalid")) {
            ProviderOperationResult result = run("sensitive-" + Math.abs(sensitive.hashCode()),
                    Map.of("process.argv", args("secret", sensitive)));
            assertFailed(result, "SECRET_RESOLUTION_ERROR");
            assertThat(Files.readString(evidenceFile(result))).doesNotContain(sensitive);
        }
    }

    @Test
    void acceptsNulAsAnExactUtf8StdinByte() throws Exception {
        String stdin = "a\0雪";
        assertThatCode(() -> validate("validateInputs", Map.of("process.argv", List.of(), "process.stdin", stdin)))
                .doesNotThrowAnyException();
        ProviderOperationResult result = run("nul-stdin", Map.of("process.argv", args("stdin"), "process.stdin", stdin));
        assertThat(result.passed()).isTrue();
        assertThat(result.outputs()).containsEntry("stdout", stdin);
        assertThat(proof(result)).containsEntry("stdin_sha256", sha(stdin.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsExecutableTamperBeforeLaunch() throws Exception {
        Path executable = temp.resolve("tampered");
        Files.writeString(executable, "not executable product content");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        ProviderExecutionContext context = context("tamper", Map.of("executable", executable.toString(),
                "executable_sha256", "0".repeat(64)));
        ProviderOperationResult result = runtime().execute(context, request(Map.of("process.argv", List.of())));
        assertFailed(result, "PROVIDER_UNAVAILABLE");
        assertThat(proof(result)).containsEntry("launched", false);
    }

    @Test
    void rejectsSymlinkExecutableAndInvalidInputBeforeLaunch() throws Exception {
        Path link = temp.resolve("java-link");
        Files.createSymbolicLink(link, javaBinary());
        ProviderOperationResult linked = runtime().execute(context("symlink", Map.of("executable", link.toString(),
                "executable_sha256", sha(javaBinary()))), request(Map.of("process.argv", List.of())));
        assertFailed(linked, "EVIDENCE_INVALID");
        for (Map<String,Object> inputs : List.<Map<String,Object>>of(
                Map.of("process.argv", List.of("bad\0argument")), Map.of("process.argv", List.of("env://PATH")),
                Map.of("process.argv", List.of("${PATH}")), Map.of("process.argv", List.of(), "process.cwd", "../escape"),
                Map.of("process.argv", List.of(), "process.timeout_ms", 1.5),
                Map.of("process.argv", List.of(), "process.timeout_ms", 10001),
                Map.of("process.argv", List.of(), "process.max_output_bytes", 1048577),
                Map.of("process.argv", List.of(), "process.stdin", "雪".repeat(22000)))) {
            ProviderOperationResult result = run("invalid-" + Math.abs(inputs.hashCode()), inputs);
            assertFailed(result, "EVIDENCE_INVALID");
            assertThat(proof(result)).containsEntry("launched", false);
        }
    }

    @Test
    void stopsTrackedChildWhenParentExitsWithInheritedPipes() throws Exception {
        ProviderOperationResult result = run("orphan", Map.of("process.argv", args("orphan"), "process.timeout_ms", 2000));
        assertThat(result.passed()).isFalse();
        Map<String,Object> evidence = proof(result);
        assertThat(evidence).containsEntry("cleanup_status", "complete");
        @SuppressWarnings("unchecked") List<Number> children = (List<Number>) evidence.get("descendant_pids");
        assertThat(children).isNotEmpty();
        for (Number child : children) assertThat(ProcessHandle.of(child.longValue()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void interruptionCleansUpAndAllowsASeparateRecoveryRun() throws Exception {
        AtomicReference<ProviderOperationResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { result.set(run("interrupted", Map.of("process.argv", args("hang"), "process.timeout_ms", 10000))); }
            catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        Thread.sleep(300);
        worker.interrupt();
        worker.join(4000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertFailed(result.get(), "OPERATION_FAILED");
        assertThat(proof(result.get())).containsEntry("cleanup_status", "complete");
        assertThat(run("recovery", Map.of("process.argv", args("stdin"))).passed()).isTrue();
    }

    @Test
    void reportsEvidenceWriteFailureWithoutAProviderPass() throws Exception {
        Path file = temp.resolve("run-is-file");
        Files.writeString(file, "existing");
        ProviderExecutionContext context = new ProviderExecutionContext("cli", "cli_process", "local", "native",
                temp, file, Map.of(), Map.of(), bindings());
        ProviderOperationResult result = runtime().execute(context, request(Map.of("process.argv", args("stdin"))));
        assertFailed(result, "EVIDENCE_INVALID");
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void policyRequiresExactBindingsAndFiniteLiteralInputShape() throws Exception {
        assertThatCode(() -> validate("validateBindings", bindings())).doesNotThrowAnyException();
        assertThatCode(() -> validate("validateInputs", Map.of("process.argv", List.of("literal", "$HOME", "$(x)"))))
                .doesNotThrowAnyException();
        for (Map<String,Object> invalid : List.<Map<String,Object>>of(Map.of(),
                Map.of("executable", "relative", "executable_sha256", "a".repeat(64)),
                Map.of("executable", javaBinary().toString(), "executable_sha256", "A".repeat(64)),
                Map.of("executable", javaBinary().toString(), "executable_sha256", sha(javaBinary()), "extra", "x"))) {
            assertThatThrownBy(() -> validate("validateBindings", invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> validate("validateInputs", Map.of("process.argv", List.of(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate("validateInputs", Map.of("process.argv", java.util.Collections.nCopies(65, "x"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate("validateInputs", Map.of("process.argv", List.of("x".repeat(4097)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ProviderOperationResult run(String run, Map<String,Object> inputs) throws Exception {
        return runtime().execute(context(run, bindings()), request(inputs));
    }

    private ProviderRuntime runtime() throws Exception {
        Class<?> type;
        try { type = Class.forName("com.specdriven.regression.provider.cli.CliProcessProviderRuntime"); }
        catch (ClassNotFoundException error) { throw new AssertionError("Missing cli_process provider implementation", error); }
        return (ProviderRuntime) type.getConstructor().newInstance();
    }

    private void validate(String method, Map<String,?> values) throws Exception {
        try {
            Class.forName("com.specdriven.regression.provider.cli.ReviewedCliPolicy").getMethod(method, Map.class).invoke(null, values);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw error;
        }
    }

    private ProviderExecutionContext context(String run, Map<String,Object> bindings) {
        return new ProviderExecutionContext("cli", "cli_process", "local", "native", temp,
                temp.resolve("runs").resolve(run), Map.of(), Map.of(), bindings);
    }

    private Map<String,Object> bindings() throws Exception {
        return Map.of("executable", javaBinary().toString(), "executable_sha256", sha(javaBinary()));
    }

    private ProviderOperationRequest request(Map<String,Object> inputs) {
        List<Map<String,Object>> parameters = new ArrayList<>();
        inputs.forEach((key,value) -> parameters.add(Map.of("name", key, "bind_as", key, "ref", value)));
        return new ProviderOperationRequest("run_command", parameters, Map.of("_operation_id", "command"));
    }

    private Path javaBinary() throws IOException { return Path.of(System.getProperty("java.home"), "bin/java").toRealPath(); }

    private List<String> args(String mode, String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of("-Xmx32m", "-XX:MaxMetaspaceSize=48m", "-cp",
                Path.of(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().toString(),
                Fixture.class.getName(), mode));
        args.addAll(List.of(extra));
        return args;
    }

    private String sha(Path path) throws Exception { return sha(Files.readAllBytes(path)); }
    private String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private Path evidenceFile(ProviderOperationResult result) {
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).evidenceType()).isEqualTo("cli_process");
        String ref = result.evidence().get(0).ref();
        try (var paths = Files.walk(temp.resolve("runs"))) {
            return paths.filter(path -> path.toString().endsWith(ref)).findFirst().orElseThrow();
        } catch (IOException error) { throw new AssertionError(error); }
    }
    @SuppressWarnings("unchecked") private Map<String,Object> proof(ProviderOperationResult result) throws Exception {
        return mapper.readValue(Files.readAllBytes(evidenceFile(result)), LinkedHashMap.class);
    }
    private void assertFailed(ProviderOperationResult result, String code) {
        assertThat(result).isNotNull();
        assertThat(result.passed()).isFalse();
        assertThat(result.failure().code()).isEqualTo(code);
        assertThat(result.outputs()).isEmpty();
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "streams" -> { System.out.println(args[1]); System.err.println("diagnostic"); System.exit(7); }
                case "stdin" -> {
                    System.out.write(System.in.readAllBytes());
                    System.err.print(System.getenv("LANG") + "/" + System.getenv("LC_ALL") + "/"
                            + System.getenv().containsKey("HOME") + "/" + System.getenv().containsKey("PATH") + "/"
                            + System.getenv().containsKey("USER") + "/" + Files.exists(Path.of("oracle.txt")));
                }
                case "combined" -> { System.out.print("a".repeat(60)); System.out.flush(); System.err.print("b".repeat(60)); }
                case "invalid" -> { System.out.write(new byte[]{(byte)0xc3, 0x28}); System.out.flush(); }
                case "secret" -> System.out.print(args.length > 1 ? args[1] : "Authorization: Bearer secret-value-123");
                case "hang" -> Thread.sleep(20000);
                case "orphan" -> {
                    new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Xmx32m", "-cp",
                            System.getProperty("java.class.path"), Fixture.class.getName(), "hang").inheritIO().start();
                    Thread.sleep(300);
                }
                default -> throw new IllegalArgumentException("unknown fixture");
            }
        }
    }
}

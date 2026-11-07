package com.specdriven.regression.provider.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.provider.runtime.ProviderEvidence;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.specdriven.regression.provider.runtime.ProviderRuntime;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Direct argv execution with finite I/O and evidence; this is not an OS sandbox. */
public final class CliProcessProviderRuntime implements ProviderRuntime {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MASK = "***MASKED***";
    private static final Pattern SECRET = Pattern.compile("(?i)(?:bearer\\s+\\S+|[\"']?(?:password|passwd|(?:access_)?token|secret|credential|api[_-]?key|private[_-]?key|authorization|session|cookie)[\"']?\\s*[:=]\\s*\\S+"
            + "|-----BEGIN[^\\r\\n]*PRIVATE KEY-----|(?:https?|grpc|grpcs)://[^/@\\s]+@|jdbc:[^\\s,;]+|nats://[^\\s,;]+)");

    @Override
    public ProviderOperationResult execute(ProviderExecutionContext context, ProviderOperationRequest request) {
        String ownedId = UUID.randomUUID().toString();
        String evidenceRef = "provider-evidence/cli_process/" + ownedId + ".json";
        Map<String,Object> evidence = new LinkedHashMap<>();
        evidence.put("evidence_type", "cli_process");
        evidence.put("provider_type", "cli_process");
        evidence.put("provider_id", context.providerId());
        evidence.put("operation", "run_command");
        evidence.put("pin_scope", "executable_only");
        evidence.put("environment_policy", "fixed:LANG=C,LC_ALL=C");
        evidence.put("cwd_policy", "new_owned_directory_under_run_dir");
        evidence.put("masking_applied", true);
        evidence.put("launched", false);
        Process process = null;
        ExecutorService workers = null;
        List<Future<?>> pending = new ArrayList<>();
        Map<Long,ProcessHandle> descendants = new LinkedHashMap<>();
        Map<String,Object> outputs = Map.of();
        Problem problem = null;
        boolean interrupted = false;
        long started = System.nanoTime();
        try {
            if (!"run_command".equals(request.operation()) || !"native".equals(context.runtimeMode())
                    || !"cli_process".equals(context.providerType())) {
                throw new IllegalArgumentException("reviewed_cli: only native cli_process run_command is supported");
            }
            Map<String,Object> inputs = inputs(request);
            ReviewedCliPolicy.validateBindings(context.bindingValues());
            ReviewedCliPolicy.validateInputs(inputs);
            @SuppressWarnings("unchecked") List<String> argv = List.copyOf((List<String>) inputs.get("process.argv"));
            byte[] stdin = ReviewedCliPolicy.utf8((String) inputs.getOrDefault("process.stdin", ""));
            int timeout = ((Number) inputs.getOrDefault("process.timeout_ms", ReviewedCliPolicy.DEFAULT_TIMEOUT_MS)).intValue();
            int maximum = ((Number) inputs.getOrDefault("process.max_output_bytes", ReviewedCliPolicy.DEFAULT_MAX_OUTPUT_BYTES)).intValue();
            Path executable = Path.of((String) context.bindingValues().get("executable"));
            evidence.put("executable", executable.toString());
            evidence.put("executable_sha256", context.bindingValues().get("executable_sha256"));
            evidence.put("argv_sha256", sha(JSON.writeValueAsBytes(argv)));
            evidence.put("stdin_sha256", sha(stdin));
            evidence.put("stdin_bytes", stdin.length);
            evidence.put("timeout_ms", timeout);
            evidence.put("max_output_bytes", maximum);
            Path cwd = ownedWorkingDirectory(context.runDir(), (String) inputs.getOrDefault("process.cwd", "."));
            evidence.put("cwd", cwd.toString());
            // Hash by streaming the final nonsymlink file immediately before constructing and starting the process.
            BasicFileAttributes identity = attributes(executable);
            String observedHash = hashExecutable(executable);
            evidence.put("observed_executable_sha256", observedHash);
            if (!context.bindingValues().get("executable_sha256").equals(observedHash)
                    || !sameFile(identity, attributes(executable)))
                throw new Problem("PROVIDER_UNAVAILABLE", "Pinned executable identity or SHA-256 changed before launch.");
            List<String> command = new ArrayList<>(argv.size() + 1);
            command.add(executable.toString());
            command.addAll(argv);
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
            builder.environment().clear();
            builder.environment().put("LANG", "C");
            builder.environment().put("LC_ALL", "C");
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            process = builder.start();
            evidence.put("launched", true);
            evidence.put("pid", process.pid());
            final Process launched = process;
            workers = Executors.newFixedThreadPool(3, task -> {
                Thread thread = new Thread(task, "cli-process-io-" + ownedId);
                thread.setDaemon(true);
                return thread;
            });
            OutputBudget budget = new OutputBudget(maximum);
            AtomicReference<Problem> ioFailure = new AtomicReference<>();
            Future<byte[]> stdout = workers.submit(() -> drain(launched.getInputStream(), budget, ioFailure));
            Future<byte[]> stderr = workers.submit(() -> drain(launched.getErrorStream(), budget, ioFailure));
            Future<?> input = workers.submit(() -> {
                try (var stream = launched.getOutputStream()) { stream.write(stdin); }
                catch (IOException error) {
                    Problem failed = new Problem("OPERATION_FAILED", "CLI stdin delivery did not complete.");
                    ioFailure.compareAndSet(null, failed);
                    throw failed;
                }
            });
            pending.addAll(List.of(stdout, stderr, input));
            while (true) {
                track(process, descendants);
                if (ioFailure.get() != null) throw ioFailure.get();
                long remaining = remaining(deadline);
                if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS)) break;
            }
            track(process, descendants);
            if (descendants.values().stream().anyMatch(ProcessHandle::isAlive))
                throw new Problem("OPERATION_FAILED", "CLI parent exited with a tracked live descendant.");
            await(input, deadline);
            String out = decode(await(stdout, deadline));
            String err = decode(await(stderr, deadline));
            remaining(deadline);
            evidence.put("stdout_bytes", budget.stdoutBytes(out));
            evidence.put("stderr_bytes", ReviewedCliPolicy.utf8(err).length);
            if (SECRET.matcher(out).find() || SECRET.matcher(err).find()) {
                evidence.put("stdout", out.isEmpty() ? "" : MASK);
                evidence.put("stderr", err.isEmpty() ? "" : MASK);
                throw new Problem("SECRET_RESOLUTION_ERROR", "CLI output contains secret-like text; observations were withheld.");
            }
            long duration = elapsed(started);
            outputs = Map.of("exit_code", process.exitValue(), "stdout", out, "stderr", err, "duration_ms", duration);
            evidence.put("exit_code", process.exitValue());
            evidence.put("stdout", out);
            evidence.put("stderr", err);
        } catch (Problem error) {
            problem = error;
        } catch (TimeoutException error) {
            problem = new Problem("PROVIDER_TIMEOUT", "CLI whole-operation deadline exceeded before complete input, exit and output.");
        } catch (InterruptedException error) {
            interrupted = true;
            problem = new Problem("OPERATION_FAILED", "CLI execution was interrupted.");
        } catch (IllegalArgumentException error) {
            problem = new Problem("EVIDENCE_INVALID", "CLI binding or literal input policy validation failed.");
        } catch (IOException error) {
            problem = new Problem(process == null ? "PROVIDER_UNAVAILABLE" : "OPERATION_FAILED",
                    process == null ? "CLI executable or owned working directory could not be opened or launched."
                            : "CLI process I/O did not complete.");
        } catch (RuntimeException error) {
            problem = new Problem("OPERATION_FAILED", "CLI execution could not complete safely.");
        } finally {
            // Cleanup gets a separate finite allowance; it never converts an incomplete execution to a pass.
            interrupted |= Thread.interrupted();
            boolean clean = cleanup(process, descendants, workers, pending, problem != null);
            evidence.put("cleanup_status", clean ? "complete" : "uncertain");
            evidence.put("descendant_pids", List.copyOf(descendants.keySet()));
            if (!clean) problem = new Problem("OPERATION_FAILED", "CLI owned process or I/O cleanup is incomplete or uncertain.");
        }
        evidence.put("duration_ms", elapsed(started));
        evidence.put("operation_status", problem == null ? "passed" : "failed");
        evidence.put("failure_code", problem == null ? "" : problem.code);
        evidence.put("error", problem == null ? "" : problem.getMessage());
        if (problem != null) outputs = Map.of();
        try {
            writeEvidence(context.runDir(), evidenceRef, evidence);
        } catch (IOException | RuntimeException error) {
            if (interrupted) Thread.currentThread().interrupt();
            return ProviderOperationResult.failed(Map.of(), List.of(), failure("EVIDENCE_INVALID", "CLI evidence could not be persisted safely."));
        }
        if (interrupted) Thread.currentThread().interrupt();
        List<ProviderEvidence> artifacts = List.of(new ProviderEvidence("cli_process", evidenceRef, true));
        return problem == null ? ProviderOperationResult.passed(outputs, artifacts)
                : ProviderOperationResult.failed(Map.of(), artifacts, failure(problem.code, problem.getMessage()));
    }

    private static Map<String,Object> inputs(ProviderOperationRequest request) {
        Map<String,Object> inputs = new LinkedHashMap<>();
        for (Map<String,Object> parameter : request.parameters()) {
            if (!(parameter.get("bind_as") instanceof String key) || inputs.containsKey(key))
                throw new IllegalArgumentException("reviewed_cli: inputs must have unique literal names");
            inputs.put(key, parameter.get("ref"));
        }
        return inputs;
    }

    private static Path ownedWorkingDirectory(Path runDir, String relative) throws IOException {
        Files.createDirectories(runDir);
        if (!Files.isDirectory(runDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(runDir))
            throw new IOException("run directory must be nonsymlink");
        Path owned = Files.createTempDirectory(runDir.toRealPath(), "cli-work-");
        Path cwd = owned.resolve(relative).normalize();
        if (!cwd.startsWith(owned)) throw new IOException("cwd escaped owned root");
        Path current = owned;
        for (Path part : owned.relativize(cwd)) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current);
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("cwd must be nonsymlink directory");
        }
        return cwd;
    }

    private static BasicFileAttributes attributes(Path file) throws IOException {
        BasicFileAttributes value = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!value.isRegularFile() || value.isSymbolicLink() || !Files.isExecutable(file)) throw new IOException("invalid executable");
        return value;
    }

    private static boolean sameFile(BasicFileAttributes first, BasicFileAttributes second) {
        return java.util.Objects.equals(first.fileKey(), second.fileKey()) && first.size() == second.size()
                && first.lastModifiedTime().equals(second.lastModifiedTime());
    }

    private static String hashExecutable(Path file) throws IOException {
        MessageDigest digest = digest();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                InputStream stream = Channels.newInputStream(channel)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] drain(InputStream stream, OutputBudget budget, AtomicReference<Problem> failure) throws IOException {
        try (stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                budget.claim(count);
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        } catch (Problem error) {
            failure.compareAndSet(null, error);
            throw error;
        } catch (IOException error) {
            failure.compareAndSet(null, new Problem("OPERATION_FAILED", "CLI output pipe did not complete."));
            throw error;
        }
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) { throw new Problem("OPERATION_FAILED", "CLI output is not valid UTF-8."); }
    }

    private static <T> T await(Future<T> pending, long deadline) throws InterruptedException, TimeoutException {
        try { return pending.get(remaining(deadline), TimeUnit.NANOSECONDS); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof Problem failed) throw failed;
            throw new Problem("OPERATION_FAILED", "CLI input or output pipe did not complete.");
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long left = deadline - System.nanoTime();
        if (left <= 0) throw new TimeoutException();
        return left;
    }

    private static void track(Process process, Map<Long,ProcessHandle> descendants) {
        try (var children = process.descendants()) { children.forEach(child -> descendants.putIfAbsent(child.pid(), child)); }
        catch (RuntimeException error) { throw new Problem("OPERATION_FAILED", "CLI process-tree observation is unavailable."); }
    }

    private static boolean cleanup(Process process, Map<Long,ProcessHandle> descendants, ExecutorService workers,
            List<Future<?>> pending, boolean failed) {
        if (process == null) return true;
        boolean clean = true;
        try {
            clean &= trackForCleanup(process, descendants);
            if (failed || descendants.values().stream().anyMatch(ProcessHandle::isAlive) || process.isAlive()) {
                descendants.values().forEach(handle -> { if (handle.isAlive()) handle.destroy(); });
                process.destroy();
                long gentle = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150);
                while (System.nanoTime() < gentle && alive(process, descendants)) {
                    clean &= trackForCleanup(process, descendants);
                    TimeUnit.MILLISECONDS.sleep(10);
                }
                clean &= trackForCleanup(process, descendants);
                descendants.values().forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
                if (process.isAlive()) process.destroyForcibly();
                long forced = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000);
                while (System.nanoTime() < forced && alive(process, descendants)) TimeUnit.MILLISECONDS.sleep(10);
                clean &= !alive(process, descendants);
            }
            Thread closer = new Thread(() -> {
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
            }, "cli-process-close-" + process.pid());
            closer.setDaemon(true);
            closer.start();
            closer.join(300);
            clean &= !closer.isAlive();
            pending.forEach(future -> future.cancel(true));
            if (workers != null) {
                workers.shutdownNow();
                clean &= workers.awaitTermination(500, TimeUnit.MILLISECONDS);
            }
            return clean && !alive(process, descendants);
        } catch (InterruptedException error) {
            descendants.values().forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
            process.destroyForcibly();
            if (workers != null) workers.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException error) {
            if (workers != null) workers.shutdownNow();
            return false;
        }
    }

    private static boolean alive(Process process, Map<Long,ProcessHandle> descendants) {
        return process.isAlive() || descendants.values().stream().anyMatch(ProcessHandle::isAlive);
    }

    private static boolean trackForCleanup(Process process, Map<Long,ProcessHandle> descendants) {
        try { track(process, descendants); return true; }
        catch (Problem error) { return false; }
    }

    private static void writeEvidence(Path runDir, String ref, Map<String,Object> evidence) throws IOException {
        Files.createDirectories(runDir);
        if (Files.isSymbolicLink(runDir) || !Files.isDirectory(runDir, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("run directory must be nonsymlink");
        Path current = runDir;
        for (String name : List.of("provider-evidence", "cli_process")) {
            current = current.resolve(name);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current);
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("evidence path must be nonsymlink");
        }
        Files.write(runDir.resolve(ref), JSON.writeValueAsBytes(evidence), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static ProviderFailure failure(String code, String reason) {
        return ProviderFailure.of(code, code, reason, "Review cli_process evidence, pinned executable and literal finite inputs.");
    }
    private static long elapsed(long start) { return Duration.ofNanos(System.nanoTime() - start).toMillis(); }
    private static String sha(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
    private static final class OutputBudget {
        private final int maximum;
        private int used;
        private OutputBudget(int maximum) { this.maximum = maximum; }
        private synchronized void claim(int count) {
            if (count > maximum - used) throw new Problem("OPERATION_FAILED", "CLI combined output exceeds its byte budget.");
            used += count;
        }
        private int stdoutBytes(String output) { return ReviewedCliPolicy.utf8(output).length; }
    }
    private static final class Problem extends RuntimeException {
        private final String code;
        private Problem(String code, String reason) { super(reason); this.code = code; }
    }
}

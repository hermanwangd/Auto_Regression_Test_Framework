package com.featuredeliveryintelligence.fdi.producttest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class FrameworkCliTransport implements FrameworkExecutionPort {
    private final FrameworkBinding binding;
    public FrameworkCliTransport(FrameworkBinding binding) { this.binding = binding; }
    @Override public ProductTestReceipt execute(ProductTestRequest request) {
        String invocation = UUID.randomUUID().toString();
        Path output = null;
        Integer runExit = null;
        long deadline = System.nanoTime() + binding.totalTimeout().toNanos();
        Instant started = Instant.now();
        var consumedOutput = new AtomicInteger();
        var cleanup = new CleanupState();
        try {
            require(binding.approvedHandoffSha256().equals(request.expectedHandoffSha256()), "Request handoff pin differs from independent approved binding");
            DefaultProductTestingModule.validateStructure(request);
            Path approved = LocalFiles.real(binding.approvedArtifactRoot());
            Path suite = LocalFiles.contained(approved, Path.of(request.suiteManifest()));
            Path artifactRoot = LocalFiles.real(Path.of(request.artifactRoot()));
            require(artifactRoot.startsWith(approved) && suite.startsWith(artifactRoot), "Artifact root is not approved");
            DefaultProductTestingModule.validateReviewedInputs(request);
            Path handoff = LocalFiles.contained(suite.getParent(), Path.of(request.reviewedHandoff()));
            require(LocalFiles.sha256(LocalFiles.read(handoff, 2 * 1024 * 1024)).equals(binding.approvedHandoffSha256()), "Reviewed handoff digest mismatch");
            require(LocalFiles.sha256(binding.frameworkJar()).equals(binding.frameworkSha256()), "Configured framework digest mismatch");
            Path parent = LocalFiles.real(binding.outputParent());
            output = Files.createTempDirectory(parent, "product-test-" + invocation + "-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (Exception e) {
            return Receipts.failure(request, invocation, ProductTestReceipt.Outcome.REJECTED, message(e), "", null);
        }
        try {
            var run = invoke(List.of("run", "--suite", request.suiteManifest(), "--profile", request.profile(),
                    "--root", output.toString()), request, output, deadline, consumedOutput, cleanup);
            runExit = run.exitCode();
            Files.writeString(output.resolve("run.stdout.log"), run.stdout());
            Files.writeString(output.resolve("run.stderr.log"), run.stderr());
            if (runExit != 0 && !run.stdout().contains("result_json: "))
                return Receipts.failure(request, invocation, ProductTestReceipt.Outcome.FRAMEWORK_REJECTED,
                        "Framework rejected execution before producing a canonical result; inspect run.stdout.log", output.toString(), runExit);
            var evidence = CanonicalEvidence.inspect(request, output, run.stdout(), started);
            checkDeadline(deadline);
            var validation = invoke(List.of("validate-evidence", "--result", evidence.result().toString(), "--root", output.toString()),
                    request, output, deadline, consumedOutput, cleanup);
            Files.writeString(output.resolve("validation.stdout.log"), validation.stdout());
            Files.writeString(output.resolve("validation.stderr.log"), validation.stderr());
            require(validation.exitCode() == 0, "Framework evidence validation failed");
            var report = invoke(List.of("report", "--result", evidence.result().toString(), "--format", "json", "--root", output.toString()),
                    request, output, deadline, consumedOutput, cleanup);
            Files.writeString(output.resolve("report.stderr.log"), report.stderr());
            require(report.exitCode() == 0, "Framework report validation failed");
            Path reportPath = output.resolve("framework-report.json");
            Files.writeString(reportPath, report.stdout());
            evidence.verifyReport(reportPath);
            evidence.verifyUnchanged();
            require(LocalFiles.sha256(binding.frameworkJar()).equals(binding.frameworkSha256()), "Configured framework digest changed");
            require((runExit == 0) == "passed".equals(evidence.verdict()), "Framework exit code contradicts canonical verdict");
            checkDeadline(deadline);
            var artifacts = new java.util.LinkedHashMap<>(evidence.artifacts());
            artifacts.put("framework_report", new ProductTestReceipt.Artifact(reportPath.toString(), LocalFiles.sha256(reportPath)));
            return new ProductTestReceipt("product-test-receipt.v1", "product-test", "1", invocation, request,
                    ProductTestReceipt.Outcome.COMPLETED, evidence.verdict(), "validated", runExit, evidence.runId(),
                    output.toString(), artifacts, false, "framework_verification_only", "");
        } catch (DeadlineException e) {
            return Receipts.failure(request, invocation, ProductTestReceipt.Outcome.TIMED_OUT,
                    "Total framework invocation deadline exceeded; no automatic retry. " + cleanup.description(), output.toString(), runExit);
        } catch (OutputLimitException e) {
            return Receipts.failure(request, invocation, ProductTestReceipt.Outcome.OUTPUT_LIMIT,
                    "Framework process output exceeded the configured bound. " + cleanup.description(), output.toString(), runExit);
        } catch (LaunchException e) {
            return Receipts.failure(request, invocation, ProductTestReceipt.Outcome.FAILED_TO_LAUNCH, message(e), output.toString(), runExit);
        } catch (Exception e) {
            return Receipts.failure(request, invocation, ProductTestReceipt.Outcome.INVALID_EVIDENCE, message(e), output.toString(), runExit);
        }
    }

    private ProcessResult invoke(List<String> command, ProductTestRequest request, Path output, long deadline,
                                 AtomicInteger consumed, CleanupState cleanup) throws Exception {
        checkDeadline(deadline);
        require(LocalFiles.sha256(binding.frameworkJar()).equals(binding.frameworkSha256()), "Configured framework digest mismatch");
        Path java;
        try { java = binding.javaExecutable().toRealPath(); }
        catch (IOException e) { throw new LaunchException("Cannot resolve configured Java executable", e); }
        var argv = new ArrayList<>(List.of(java.toString(), "-Xmx512m", "-XX:MaxMetaspaceSize=256m",
                "-Djava.io.tmpdir=" + output, "-jar", binding.frameworkJar().toRealPath().toString()));
        argv.addAll(command);
        var builder = new ProcessBuilder(argv).directory(output.toFile());
        builder.environment().clear();
        builder.environment().put("REGRESS_REVIEWED_HANDOFF_SHA256", binding.approvedHandoffSha256());
        Process process;
        try { process = builder.start(); }
        catch (IOException e) { throw new LaunchException("Cannot launch configured framework executable", e); }
        cleanup.pid = process.pid(); cleanup.terminated = false; cleanup.streamsClosed = false;
        var stdout = new BoundedReader(process.getInputStream(), consumed, binding.maximumOutputBytes());
        var stderr = new BoundedReader(process.getErrorStream(), consumed, binding.maximumOutputBytes());
        Thread outThread = new Thread(stdout, "product-test-stdout"); outThread.setDaemon(true); outThread.start();
        Thread errThread = new Thread(stderr, "product-test-stderr"); errThread.setDaemon(true); errThread.start();
        try {
            while (!process.waitFor(20, TimeUnit.MILLISECONDS)) {
                checkDeadline(deadline);
                if (consumed.get() > binding.maximumOutputBytes()) throw new OutputLimitException();
            }
            while (outThread.isAlive() || errThread.isAlive()) {
                checkDeadline(deadline);
                if (consumed.get() > binding.maximumOutputBytes()) throw new OutputLimitException();
                outThread.join(10); errThread.join(10);
            }
            if (consumed.get() > binding.maximumOutputBytes()) throw new OutputLimitException();
            if (stdout.failed || stderr.failed) throw new IOException("Cannot capture framework output");
            return new ProcessResult(process.exitValue(), stdout.text(), stderr.text());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new DeadlineException();
        } finally {
            cleanup(process, outThread, errThread, cleanup);
        }
    }

    private static void cleanup(Process process, Thread outThread, Thread errThread, CleanupState state) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        boolean interrupted = Thread.interrupted();
        try {
            if (process.isAlive()) {
                try { process.descendants().limit(64).forEach(ProcessHandle::destroyForcibly); }
                catch (RuntimeException ignored) { /* Restricted hosts may forbid process enumeration. */ }
                try { process.toHandle().destroyForcibly(); }
                catch (RuntimeException ignored) { /* Report unconfirmed termination without hiding the primary outcome. */ }
                try { process.waitFor(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            state.terminated = !process.isAlive();
            Thread closer = new Thread(() -> {
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
            }, "product-test-pipe-cleanup");
            closer.setDaemon(true); closer.start();
            for (Thread thread : List.of(outThread, errThread, closer)) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0) break;
                try { TimeUnit.NANOSECONDS.timedJoin(thread, remaining); }
                catch (InterruptedException e) { interrupted = true; }
            }
            state.streamsClosed = !outThread.isAlive() && !errThread.isAlive() && !closer.isAlive();
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static final class CleanupState {
        long pid; boolean terminated; boolean streamsClosed;
        String description() {
            if (pid == 0) return "No framework child was launched.";
            return "Owned child " + pid + " termination " + (terminated ? "confirmed" : "unconfirmed")
                    + "; pipe cleanup " + (streamsClosed ? "confirmed" : "unconfirmed") + ".";
        }
    }

    private static final class BoundedReader implements Runnable {
        private final InputStream input; private final AtomicInteger consumed; private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(); private volatile boolean failed;
        BoundedReader(InputStream input, AtomicInteger consumed, int limit) { this.input = input; this.consumed = consumed; this.limit = limit; }
        public void run() {
            try {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (consumed.addAndGet(count) > limit) return;
                    bytes.write(buffer, 0, count);
                }
            } catch (IOException e) { failed = true; }
        }
        String text() { return bytes.toString(StandardCharsets.UTF_8); }
    }
    private record ProcessResult(int exitCode, String stdout, String stderr) {}
    private static final class DeadlineException extends Exception {}
    private static final class OutputLimitException extends Exception {}
    private static final class LaunchException extends Exception { LaunchException(String message, Exception cause) { super(message, cause); } }
    private static void checkDeadline(long deadline) throws DeadlineException { if (System.nanoTime() >= deadline) throw new DeadlineException(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
}

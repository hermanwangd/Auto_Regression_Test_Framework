package com.specdriven.regression.contract.v03.adapter;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.contract.v03.ref.V03Reference;
import com.specdriven.regression.contract.v03.ref.V03ReferenceParser;
import com.specdriven.regression.provider.ProviderDataYaml;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class SftpV03Adapter implements V03ProviderRuntimeAdapter {
    static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_FILES = 32;
    private static final Set<String> OPERATIONS = Set.of("sftp_upload_batch", "sftp_verify_return", "sftp_cleanup_batch");

    @Override public String providerType() { return "sftp"; }

    @Override public boolean supports(String contract, String operation) {
        return "sftp.v0.3".equals(contract) && OPERATIONS.contains(operation);
    }

    @Override public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        V03StepResult result;
        try {
            if (!supports(step.providerContract(), step.operation()) || !"native".equals(step.runtimeMode())) {
                throw new IllegalArgumentException();
            }
            String batch = "batch-" + hashText(context.runDir().getFileName() + "\n" + step.testCaseId() + "\n" + step.target());
            Path ownership = context.runDir().resolve(".sftp-ownership").resolve(batch);
            Map<String, Object> outputs = switch (step.operation()) {
                case "sftp_upload_batch" -> upload(step, context, batch, ownership);
                case "sftp_verify_return" -> verify(step, context, batch, ownership);
                case "sftp_cleanup_batch" -> cleanup(step, context, batch, ownership);
                default -> throw new IllegalArgumentException();
            };
            result = new V03StepResult(step.id(), "passed", outputs, List.of(), "", "");
        } catch (Check error) {
            result = failed(step, error.code, error.getMessage(), error.observed);
        } catch (IllegalArgumentException error) {
            result = failed(step, "SFTP_INVALID_CONFIGURATION", "Check the runner bindings, fixture and step inputs.", Map.of());
        } catch (JSchException | SftpException error) {
            result = failed(step, "SFTP_REQUEST_FAILED", "Check the pinned host key, account, permissions and service availability.", Map.of());
        } catch (IOException | UncheckedIOException error) {
            result = failed(step, "SFTP_IO_FAILED", "Check the fixture, run directory and SFTP transfer.", Map.of());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            result = failed(step, "SFTP_INTERRUPTED", "The return-file wait was interrupted.", Map.of());
        }
        String ref = "provider-evidence/sftp/" + hashText(step.testCaseId() + "\n" + step.target() + "\n" + step.phase() + "\n" + step.id()) + ".yaml";
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("operation", step.operation());
        evidence.put("status", result.status());
        evidence.put("outputs", result.outputs());
        if (!result.passed()) evidence.put("failure_code", result.failureCode());
        try {
            ProviderDataYaml.writeEvidence(context.runDir().resolve(ref), evidence);
        } catch (IOException error) {
            return failed(step, "SFTP_EVIDENCE_FAILED", "Could not write SFTP operation evidence.", Map.of());
        }
        return new V03StepResult(step.id(), result.status(), result.outputs(), List.of(ref), result.failureCode(), result.message());
    }

    private Map<String, Object> upload(V03ExecutionStep step, V03ExecutionContext context, String batch, Path ownership)
            throws IOException, SftpException, JSchException {
        String name = filename(step.inputs().get("name"));
        var ref = new V03ReferenceParser().parse(step.inputs().get("payload_ref"));
        if (!(ref instanceof V03Reference.Artifact artifact) || !artifact.jsonPointer().isEmpty()) throw new IllegalArgumentException();
        Path file = context.referenceResolver().artifactPath(artifact, context.referenceContext(step.testCaseId()));
        byte[] payload;
        try (InputStream in = Files.newInputStream(file)) { payload = boundedBytes(in); }
        Files.createDirectories(ownership.getParent());
        if (Files.exists(ownership, LinkOption.NOFOLLOW_LINKS)) throw new Check("SFTP_BATCH_CONFLICT", "This batch already exists.");
        try (var conn = SftpRunnerConnection.open(context, step.target(), 12_000)) {
            var sftp = conn.channel;
            String dir = conn.root + "/" + batch;
            // mkdir is exclusive. Never adopt an existing directory after an error or on a repeated upload.
            sftp.mkdir(dir);
            try {
                Files.createFile(ownership);
            } catch (IOException error) {
                sftp.rmdir(dir);
                throw error;
            }
            sftp.mkdir(dir + "/incoming");
            sftp.mkdir(dir + "/returns");
            sftp.put(new ByteArrayInputStream(payload), dir + "/incoming/.upload-part");
            sftp.rename(dir + "/incoming/.upload-part", dir + "/incoming/" + name);
        }
        return Map.of("batch", batch, "filename", name, "bytes", payload.length, "sha256", hash(payload));
    }

    private Map<String, Object> verify(V03ExecutionStep step, V03ExecutionContext context, String batch, Path ownership)
            throws IOException, JSchException, SftpException, InterruptedException {
        String name = filename(step.inputs().get("name"));
        int wait = integer(step.inputs().get("timeout_ms"), 100, 30_000);
        int expectedCount = integer(step.inputs().get("expected_records"), 0, MAX_BYTES);
        Object expectedHash = step.inputs().get("expected_sha256");
        if (!(expectedHash instanceof String digest) || !digest.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException();
        if (!Files.isRegularFile(ownership, LinkOption.NOFOLLOW_LINKS)) throw new Check("SFTP_BATCH_MISSING", "Upload a run-owned batch before verification.");
        try (var conn = SftpRunnerConnection.open(context, step.target(), wait + 8_000)) {
            var sftp = conn.channel;
            String dir = conn.root + "/" + batch;
            requireDirectory(sftp, dir);
            String returns = dir + "/returns";
            requireDirectory(sftp, returns);
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wait);
            while (true) {
                List<ChannelSftp.LsEntry> entries = entries(sftp, returns);
                if (entries.stream().anyMatch(entry -> name.equals(entry.getFilename()))) {
                    if (entries.size() != 1) throw new Check("SFTP_FILE_COUNT_MISMATCH", "Expected one return file.", Map.of("file_count", entries.size()));
                    String path = returns + "/" + name;
                    SftpATTRS attrs = sftp.lstat(path);
                    if (!attrs.isReg()) throw unsafePath();
                    if (attrs.getSize() > MAX_BYTES) throw tooLarge();
                    byte[] contents;
                    try (InputStream in = sftp.get(path)) { contents = boundedBytes(in); }
                    int records = records(contents);
                    String actual = hash(contents);
                    Map<String, Object> observed = Map.of("batch", batch, "filename", name, "file_count", 1,
                            "records", records, "bytes", contents.length, "sha256", actual);
                    if (records != expectedCount) throw new Check("SFTP_RECORD_COUNT_MISMATCH", "Return record count differed.", observed);
                    if (!digest.equalsIgnoreCase(actual)) throw new Check("SFTP_CHECKSUM_MISMATCH", "Return SHA-256 differed.", observed);
                    return observed;
                }
                long remaining = end - System.nanoTime();
                if (remaining <= 0) {
                    if (!entries.isEmpty()) throw new Check("SFTP_FILENAME_MISMATCH", "The expected filename did not arrive.", Map.of("file_count", entries.size()));
                    throw new Check("SFTP_RETURN_TIMEOUT", "The bounded return-file wait expired.", Map.of("file_count", 0));
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
            }
        }
    }

    private Map<String, Object> cleanup(V03ExecutionStep step, V03ExecutionContext context, String batch, Path ownership)
            throws IOException, JSchException, SftpException {
        if (!Files.isRegularFile(ownership, LinkOption.NOFOLLOW_LINKS)) return Map.of("deleted", false);
        try (var conn = SftpRunnerConnection.open(context, step.target(), 12_000)) {
            var sftp = conn.channel;
            String dir = conn.root + "/" + batch;
            if (exists(sftp, dir)) {
                requireDirectory(sftp, dir);
                for (String child : List.of("incoming", "returns")) {
                    String folder = dir + "/" + child;
                    if (!exists(sftp, folder)) continue;
                    requireDirectory(sftp, folder);
                    for (var entry : entries(sftp, folder)) {
                        String leaf = entry.getFilename();
                        if (!leaf.matches("[A-Za-z0-9._-]{1,128}") || entry.getAttrs().isDir()) throw unsafePath();
                        sftp.rm(folder + "/" + leaf);
                    }
                    sftp.rmdir(folder);
                }
                sftp.rmdir(dir);
            }
            if (exists(sftp, dir)) throw new Check("SFTP_CLEANUP_UNCONFIRMED", "The batch directory is still present.");
        }
        Files.delete(ownership);
        return Map.of("batch", batch, "deleted", true);
    }

    private static List<ChannelSftp.LsEntry> entries(ChannelSftp sftp, String dir) throws SftpException {
        List<ChannelSftp.LsEntry> result = new ArrayList<>();
        sftp.ls(dir, entry -> {
            if (!Set.of(".", "..").contains(entry.getFilename())) result.add(entry);
            return result.size() > MAX_FILES ? ChannelSftp.LsEntrySelector.BREAK : ChannelSftp.LsEntrySelector.CONTINUE;
        });
        if (result.size() > MAX_FILES) throw new Check("SFTP_FILE_LIMIT", "The directory exceeds 32 entries.");
        return result;
    }

    private static boolean exists(ChannelSftp sftp, String path) throws SftpException {
        try {
            sftp.lstat(path);
            return true;
        } catch (SftpException error) {
            if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return false;
            throw error;
        }
    }

    private static void requireDirectory(ChannelSftp sftp, String path) throws SftpException {
        if (!sftp.lstat(path).isDir() || !sftp.realpath(path).equals(path)) throw unsafePath();
    }

    private static String filename(Object value) {
        if (!(value instanceof String name) || !name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")) throw new IllegalArgumentException();
        return name;
    }

    private static int integer(Object value, int min, int max) {
        if (!(value instanceof Integer number) || number < min || number > max) throw new IllegalArgumentException();
        return number;
    }

    private static byte[] boundedBytes(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_BYTES + 1);
        if (data.length > MAX_BYTES) throw tooLarge();
        return data;
    }

    private static int records(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return Math.toIntExact(text.lines().count());
        } catch (CharacterCodingException error) {
            throw new Check("SFTP_RETURN_ENCODING", "The return file must be UTF-8 text.");
        }
    }

    private static Check unsafePath() { return new Check("SFTP_UNSAFE_PATH", "Expected a regular file or run-owned directory."); }
    private static Check tooLarge() { return new Check("SFTP_FILE_TOO_LARGE", "The file exceeds 1 MiB."); }

    private static String hashText(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    private static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Check extends RuntimeException {
        final String code;
        final Map<String, Object> observed;
        Check(String code, String message) { this(code, message, Map.of()); }
        Check(String code, String message, Map<String, Object> observed) {
            super(message);
            this.code = code;
            this.observed = observed;
        }
    }

    private static V03StepResult failed(V03ExecutionStep step, String code, String message, Map<String, Object> observed) {
        return new V03StepResult(step.id(), "failed", observed, List.of(), code, message);
    }
}

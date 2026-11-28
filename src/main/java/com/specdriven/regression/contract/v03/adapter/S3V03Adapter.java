package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.contract.v03.ref.V03Reference;
import com.specdriven.regression.contract.v03.ref.V03ReferenceParser;
import com.specdriven.regression.provider.ProviderDataYaml;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Native operations are scoped to the framework-issued run, test, target and logical object name. */
public final class S3V03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter {
    static final int MAX_BYTES = 1024 * 1024;
    private static final String OWNER = "regress-owner";
    private static final Set<String> OPERATIONS = Set.of(
            "s3_object_key", "s3_put_object", "s3_head_object", "s3_verify_object", "s3_delete_object");
    private final Map<V03ExecutionContext, Map<String, String>> uploadedVersions = new ConcurrentHashMap<>();

    @Override public void endRun(V03ExecutionContext context) { uploadedVersions.remove(context); }

    @Override public String providerType() { return "s3"; }

    @Override public boolean supports(String contract, String operation) {
        return "s3.v0.3".equals(contract) && OPERATIONS.contains(operation);
    }

    @Override
    public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        V03StepResult result;
        try {
            result = dispatch(step, context);
        } catch (S3Exception error) {
            String code = switch (error.statusCode()) {
                case 401, 403 -> "S3_ACCESS_DENIED";
                case 404 -> "S3_OBJECT_MISSING";
                case 409, 412 -> "S3_CONFLICT";
                default -> "S3_REQUEST_FAILED";
            };
            result = failed(step, code, "S3 returned HTTP " + error.statusCode() + ".", Map.of());
        } catch (SdkException error) {
            result = failed(step, "S3_REQUEST_FAILED", "Check the S3 endpoint, credentials and request timeout.", Map.of());
        } catch (IllegalArgumentException error) {
            result = failed(step, "S3_INVALID_CONFIGURATION",
                    "Check the S3 runner environment bindings, object name, fixture and checksum inputs.", Map.of());
        } catch (IOException | UncheckedIOException error) {
            result = failed(step, "S3_FIXTURE_UNREADABLE", "Check the declared S3 fixture file.", Map.of());
        }
        return recordEvidence(step, context, result);
    }

    private V03StepResult dispatch(V03ExecutionStep step, V03ExecutionContext context) throws IOException {
        if (!supports(step.providerContract(), step.operation()) || !"native".equals(step.runtimeMode())) {
            throw new IllegalArgumentException("Unsupported native operation.");
        }
        Object rawName = step.inputs().get("name");
        if (!(rawName instanceof String name) || !name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")) {
            throw new IllegalArgumentException("Invalid name.");
        }
        String owner = hash(String.join("\n", context.runDir().getFileName().toString(),
                step.testCaseId(), step.target()).getBytes(StandardCharsets.UTF_8));
        boolean issueKey = "s3_object_key".equals(step.operation());
        boolean applicationExport = issueKey;
        if (step.inputs().containsKey("application_export")) {
            if (!(step.inputs().get("application_export") instanceof Boolean flag)
                    || !("s3_head_object".equals(step.operation()) || "s3_verify_object".equals(step.operation()))) {
                throw new IllegalArgumentException("Application exports permit HEAD and verification only.");
            }
            applicationExport = flag;
        }
        String key = "regress/" + owner + (applicationExport ? "/exports/" : "/") + name;
        if (issueKey) return passed(step, Map.of("object_key", key));
        try (S3RunnerConnection connection = S3RunnerConnection.open(context, step.target())) {
            return switch (step.operation()) {
                case "s3_put_object" -> put(step, context, connection, key, owner);
                case "s3_head_object" -> head(step, connection, new ReadLocation(key, owner, applicationExport));
                case "s3_verify_object" -> verify(step, context, connection, new ReadLocation(key, owner, applicationExport));
                case "s3_delete_object" -> delete(step, connection, key, owner,
                        uploadedVersions.getOrDefault(context, Map.of()).get(key));
                default -> throw new IllegalArgumentException("Unsupported S3 operation.");
            };
        }
    }

    private record ReadLocation(String key, String owner, boolean applicationExport) {
        boolean readable(Map<String, String> metadata) {
            return applicationExport || owner.equals(metadata.get(OWNER));
        }
    }

    private V03StepResult put(V03ExecutionStep step, V03ExecutionContext context, S3RunnerConnection connection,
            String key, String owner) throws IOException {
        V03Reference ref = new V03ReferenceParser().parse(step.inputs().get("payload_ref"));
        if (!(ref instanceof V03Reference.Artifact artifact) || !artifact.jsonPointer().isEmpty()) {
            throw new IllegalArgumentException("A complete fixture artifact is required.");
        }
        Path fixture = context.referenceResolver().artifactPath(artifact, context.referenceContext(step.testCaseId()));
        byte[] bytes;
        try (InputStream input = Files.newInputStream(fixture)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) return tooLarge(step);
        var upload = connection.client.putObject(b -> b.bucket(connection.bucket).key(key).ifNoneMatch("*")
                .metadata(Map.of(OWNER, owner)), RequestBody.fromBytes(bytes));
        var versions = uploadedVersions.computeIfAbsent(context, ignored -> new ConcurrentHashMap<>());
        if (upload.versionId() == null) {
            versions.remove(key);
        } else {
            versions.put(key, upload.versionId());
        }
        return passed(step, Map.of("object_key", key, "content_length", bytes.length, "sha256", hash(bytes)));
    }

    private V03StepResult head(V03ExecutionStep step, S3RunnerConnection connection, ReadLocation location) {
        HeadObjectResponse head = connection.client.headObject(b -> b.bucket(connection.bucket).key(location.key()));
        if (!location.readable(head.metadata())) return ownershipMismatch(step);
        return passed(step, Map.of("exists", true, "content_length", head.contentLength(), "object_key", location.key()));
    }

    private V03StepResult verify(V03ExecutionStep step, V03ExecutionContext context, S3RunnerConnection connection,
            ReadLocation location) {
        Object expected = resolveProviderValue(step.inputs().get("expected_sha256"), step, context);
        if (!(expected instanceof String digest) || !digest.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("A SHA-256 hex digest is required.");
        }
        var request = GetObjectRequest.builder().bucket(connection.bucket).key(location.key()).build();
        long started = System.nanoTime();
        return connection.client.getObject(request, (response, stream) -> {
            if (!location.readable(response.metadata())) {
                stream.abort();
                return ownershipMismatch(step);
            }
            if (response.contentLength() != null && response.contentLength() > MAX_BYTES) {
                stream.abort();
                return tooLarge(step);
            }
            MessageDigest sha = sha256();
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (System.nanoTime() - started >= S3RunnerConnection.CALL_TIMEOUT.toNanos()) {
                    stream.abort();
                    return failed(step, "S3_READ_TIMEOUT", "The object download exceeded the request timeout.", Map.of());
                }
                total += count;
                if (total > MAX_BYTES) {
                    stream.abort();
                    return tooLarge(step);
                }
                sha.update(buffer, 0, count);
            }
            String observed = HexFormat.of().formatHex(sha.digest());
            Map<String, Object> outputs = Map.of("content_length", total, "sha256", observed, "object_key", location.key());
            if (!observed.equalsIgnoreCase(digest)) {
                return failed(step, "S3_CHECKSUM_MISMATCH", "The downloaded bytes differ from the expected SHA-256.", outputs);
            }
            return passed(step, outputs);
        });
    }

    private V03StepResult delete(V03ExecutionStep step, S3RunnerConnection connection, String key, String owner,
            String uploadedVersion) {
        HeadObjectResponse head;
        try {
            head = connection.client.headObject(b -> b.bucket(connection.bucket).key(key).versionId(uploadedVersion));
        } catch (S3Exception error) {
            if (error.statusCode() == 404) return passed(step, Map.of("deleted", false));
            throw error;
        }
        if ((uploadedVersion != null && !uploadedVersion.equals(head.versionId()))
                || !owner.equals(head.metadata().get(OWNER)) || head.eTag() == null || head.eTag().isBlank()) {
            return ownershipMismatch(step);
        }
        // Versioned buckets must remove the observed version, including the literal "null" version.
        DeleteObjectRequest.Builder request = DeleteObjectRequest.builder()
                .bucket(connection.bucket).key(key).ifMatch(head.eTag());
        if (head.versionId() != null) request.versionId(head.versionId());
        connection.client.deleteObject(request.build());
        try {
            var probe = HeadObjectRequest.builder().bucket(connection.bucket).key(key);
            if (head.versionId() != null) probe.versionId(head.versionId());
            connection.client.headObject(probe.build());
        } catch (S3Exception error) {
            if (error.statusCode() == 404) return passed(step, Map.of("deleted", true));
            throw error;
        }
        return failed(step, "S3_CLEANUP_NOT_CONFIRMED", "The deleted object is still visible.", Map.of("deleted", false));
    }

    private static V03StepResult recordEvidence(V03ExecutionStep step, V03ExecutionContext context, V03StepResult result) {
        String id = hash((step.testCaseId() + "\n" + step.id()).getBytes(StandardCharsets.UTF_8));
        String ref = "provider-evidence/s3/" + step.phase() + "-" + id + ".yaml";
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("operation", step.operation());
        fields.put("status", result.status());
        fields.put("outputs", result.outputs());
        if (!result.failureCode().isEmpty()) fields.put("failure_code", result.failureCode());
        try {
            ProviderDataYaml.writeEvidence(context.runDir().resolve(ref), fields);
        } catch (IOException error) {
            return failed(step, "S3_EVIDENCE_FAILED", "Could not write S3 operation evidence.", Map.of());
        }
        return new V03StepResult(step.id(), result.status(), result.outputs(), List.of(ref), result.failureCode(), result.message());
    }

    private static V03StepResult tooLarge(V03ExecutionStep step) {
        return failed(step, "S3_OBJECT_TOO_LARGE", "The object exceeds the 1 MiB fixture limit.", Map.of());
    }

    private static V03StepResult ownershipMismatch(V03ExecutionStep step) {
        return failed(step, "S3_OWNERSHIP_MISMATCH", "The object ownership or conditional identity could not be verified.", Map.of());
    }

    private static V03StepResult passed(V03ExecutionStep step, Map<String, Object> outputs) {
        return new V03StepResult(step.id(), "passed", outputs, List.of(), "", "");
    }

    private static V03StepResult failed(V03ExecutionStep step, String code, String message, Map<String, Object> outputs) {
        return new V03StepResult(step.id(), "failed", outputs, List.of(), code, message);
    }

    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
}

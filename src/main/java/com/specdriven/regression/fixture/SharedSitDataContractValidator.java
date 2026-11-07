package com.specdriven.regression.fixture;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specdriven.regression.runner.RunnerBundleService.SourceBindings;
import com.specdriven.regression.sit.SitReadinessReport;
import com.specdriven.regression.sit.SitReadinessState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import static com.specdriven.regression.fixture.SharedSitDataDecision.*;

/** Pure metadata admission; never opens files, resolves capabilities or executes provider effects. */
public final class SharedSitDataContractValidator {
    private static final int METADATA_LIMIT = 65536;
    private static final int CANONICAL_LIMIT = 4 * 1024 * 1024;
    private static final ObjectMapper JSON = mapper(16, 4096);
    private static final ObjectMapper CANONICAL_JSON = mapper(64, 1024 * 1024);
    private static final Map<String, String> ROLES = Map.of("data", "sanitized_data", "schema", "config_template",
            "config", "config_template", "oracle", "oracle", "cleanup", "cleanup_definition");
    private final Clock clock;

    public SharedSitDataContractValidator() { this(Clock.systemUTC()); }
    SharedSitDataContractValidator(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public SharedSitDataDecision assess(byte[] contractBytes, String expectedContractSha256,
            SourceBindings source, byte[] controllerBytes, String expectedControllerSha256,
            SitReadinessReport readiness, byte[] canonicalBytes) {
        // Snapshot bounded caller byte arrays before hashing and parsing them.
        contractBytes = snapshot(contractBytes, METADATA_LIMIT);
        controllerBytes = snapshot(controllerBytes, METADATA_LIMIT);
        canonicalBytes = snapshot(canonicalBytes, CANONICAL_LIMIT);
        Set<String> gaps = new LinkedHashSet<>();
        String contractHash = boundedHash(contractBytes, METADATA_LIMIT);
        String controllerHash = boundedHash(controllerBytes, METADATA_LIMIT);
        String canonicalHash = boundedHash(canonicalBytes, CANONICAL_LIMIT);
        JsonNode contract = null, controller = null, sourceMetadata = null, identity = null;
        Scope scope = Scope.CONTRACT;
        OwnershipState ownership = OwnershipState.UNKNOWN;
        CleanupState cleanup = CleanupState.UNKNOWN;
        SitReadinessState readinessState = SitReadinessState.UNKNOWN;
        CanonicalIdentityState canonicalState = canonicalBytes == null || canonicalBytes.length == 0
                ? CanonicalIdentityState.MISSING : CanonicalIdentityState.INVALID;
        String verdict = "UNKNOWN", key = null;
        boolean valid = true;
        try {
            controller = pinned(controllerBytes, expectedControllerSha256);
            // A shared label can only restrict this assessment, never create authority.
            if ("SHARED".equals(controller.path("scope").asText())) scope = Scope.SHARED_BLOCKED;
            validateController(controller);
            scope = switch (controller.path("scope").asText()) {
                case "ISOLATED_ONLY" -> Scope.ISOLATED_ONLY;
                case "SHARED" -> Scope.SHARED_BLOCKED;
                default -> Scope.CONTRACT;
            };
            ownership = "LOCAL_FAKE".equals(controller.path("ownership").asText())
                    ? OwnershipState.LOCAL_FAKE : OwnershipState.UNKNOWN;
            cleanup = CleanupState.valueOf(controller.path("cleanup").asText());
        } catch (RuntimeException invalid) {
            valid = false;
            gaps.add("CONTROLLER_BINDING_INVALID");
        }
        try {
            contract = pinned(contractBytes, expectedContractSha256);
            require(source != null);
            sourceMetadata = source.metadata();
            require("ADMITTED".equals(sourceMetadata.path("source_binding_state").asText()));
            require(controller != null && controllerHash != null && controllerHash.equals(expectedControllerSha256));
            require(contractHash != null && contractHash.equals(controller.path("approved_contract_sha256").asText()));
            require(hash(canonical(sourceMetadata)).equals(controller.path("source_binding_sha256").asText()));
            identity = validateContract(contract, sourceMetadata, controller);
        } catch (RuntimeException invalid) {
            valid = false;
            gaps.add("CONTRACT_SOURCE_BINDING_INVALID");
        }
        if (controller != null && controllerHash != null && controllerHash.equals(expectedControllerSha256)) {
            try {
                CanonicalMatch matched = canonicalIdentity(controller.path("canonical_identity"), canonicalBytes, canonicalHash);
                canonicalState = matched.state();
                verdict = matched.verdict();
            } catch (RuntimeException invalid) {
                canonicalState = canonicalBytes == null || canonicalBytes.length == 0
                        ? CanonicalIdentityState.MISSING : CanonicalIdentityState.INVALID;
            }
        }
        if (canonicalState != CanonicalIdentityState.MATCHED) gaps.add("CANONICAL_IDENTITY_UNAVAILABLE");
        if (valid) {
            try {
                Instant expiry = Instant.parse(controller.path("valid_until").asText());
                require(expiry.isAfter(clock.instant()) && !expiry.isAfter(clock.instant().plusSeconds(300)));
            } catch (RuntimeException invalid) { gaps.add("CONTROLLER_CONTEXT_EXPIRED"); }
            readinessState = readiness(contract, controller, readiness, gaps);
            if (ownership != OwnershipState.LOCAL_FAKE) gaps.add("OWNERSHIP_UNBOUND");
            if (cleanup != CleanupState.CONFIRMED) gaps.add("CLEANUP_UNCONFIRMED");
            try {
                ObjectNode run = JSON.createObjectNode();
                for (String name : List.of("run_id", "run_namespace", "scope")) run.set(name, controller.get(name));
                key = semanticKey(normalizedContract(contract), identity, run);
            } catch (RuntimeException invalid) { valid = false; gaps.add("IDEMPOTENCY_IDENTITY_INVALID"); }
        }
        Acceptance acceptance;
        if (scope == Scope.SHARED_BLOCKED) {
            gaps.add("PERSISTENT_SHARED_OWNERSHIP_UNBOUND");
            gaps.add("PRE_DATA_CLEANUP_SAVE_UNBOUND");
            acceptance = Acceptance.BLOCKED_NO_SHARED_OWNERSHIP_OR_PRECLEANUP_SAVE_BINDING;
        } else if (!valid) acceptance = Acceptance.BLOCKED_INPUT;
        else acceptance = gaps.isEmpty() ? Acceptance.CONTRACT_ONLY : Acceptance.BLOCKED_PRECONDITIONS;
        return new SharedSitDataDecision(valid ? ContractState.VALID : ContractState.INVALID, scope, acceptance,
                readinessState, ownership, cleanup, canonicalState, verdict, canonicalHash,
                contractHash, controllerHash, key, OriginState.UNBOUND, false, false, List.copyOf(gaps));
    }

    private static void validateController(JsonNode c) {
        fields(c, "context_version", "scope", "run_id", "run_namespace", "source_binding_sha256",
                "approved_contract_sha256", "readiness_sha256", "valid_until", "ownership", "cleanup",
                "owner_token_refs", "actual_sut_revisions", "canonical_identity");
        require("shared-sit-data-controller.v1".equals(text(c, "context_version", 128)));
        require(Set.of("CONTRACT", "ISOLATED_ONLY", "SHARED").contains(text(c, "scope", 32)));
        identifier(c, "run_id"); identifier(c, "run_namespace");
        for (String name : List.of("source_binding_sha256", "approved_contract_sha256", "readiness_sha256")) digest(c, name);
        text(c, "valid_until", 64);
        require(Set.of("UNKNOWN", "LOCAL_FAKE", "READY").contains(text(c, "ownership", 32)));
        require(Set.of("NOT_RUN", "CONFIRMED", "UNKNOWN").contains(text(c, "cleanup", 32)));
        Set<String> refs = new HashSet<>();
        for (JsonNode value : array(c, "owner_token_refs", 1, 32)) {
            require(value.isTextual() && ownerRef(value.asText()) && refs.add(value.asText()));
        }
        Set<String> suts = new HashSet<>();
        for (JsonNode sut : array(c, "actual_sut_revisions", 1, 16)) {
            fields(sut, "sut_id", "observed_revision", "observation_ref");
            require(suts.add(identifier(sut, "sut_id"))); text(sut, "observed_revision", 256); identifier(sut, "observation_ref");
        }
        JsonNode canonical = c.path("canonical_identity");
        fields(canonical, "sha256", "run_id", "suite_id", "profile", "batch_id");
        if (!canonical.path("sha256").isNull()) digest(canonical, "sha256");
        for (String name : List.of("run_id", "suite_id", "profile", "batch_id")) identifier(canonical, name);
        require(c.path("run_id").equals(canonical.path("run_id")));
    }

    private static JsonNode validateContract(JsonNode c, JsonNode source, JsonNode controller) {
        fields(c, "contract_version", "contract_id", "dataset_id", "data_version", "schema_version",
                "artifacts", "resources", "preconditions", "sut_expectations");
        require("shared-sit-data.v1".equals(text(c, "contract_version", 128)));
        for (String name : List.of("contract_id", "dataset_id", "data_version", "schema_version")) identifier(c, name);
        fields(c.path("artifacts"), "data", "schema", "oracle", "cleanup", "config");
        Map<String, JsonNode> available = new HashMap<>();
        require(source.path("artifacts").isArray() && source.path("artifacts").size() <= 128);
        for (JsonNode value : source.path("artifacts")) available.put(value.path("artifact_id").asText(), value);
        ObjectNode identity = JSON.createObjectNode();
        identity.put("lock_sha256", digest(source.path("lock"), "sha256"));
        identity.put("composition_root_version", text(source.path("lock"), "composition_root_version", 40));
        ArrayNode selected = identity.putArray("artifacts");
        Set<String> ids = new HashSet<>();
        for (String name : new TreeSet<>(ROLES.keySet())) {
            JsonNode reference = c.path("artifacts").path(name);
            fields(reference, "artifact_id", "sha256");
            String id = text(reference, "artifact_id", 2048), sha = digest(reference, "sha256");
            JsonNode found = available.get(id);
            require(ids.add(id) && found != null && sha.equals(found.path("sha256").asText())
                    && ROLES.get(name).equals(found.path("role").asText()));
            ObjectNode item = selected.addObject();
            for (String field : List.of("artifact_id", "sha256", "source_testrepo_version", "role")) item.set(field, found.get(field));
        }
        sort(selected, "artifact_id");
        Set<String> owners = new HashSet<>(); controller.path("owner_token_refs").forEach(value -> owners.add(value.asText()));
        Set<String> resources = new HashSet<>();
        for (JsonNode resource : array(c, "resources", 1, 32)) {
            fields(resource, "resource_id", "run_namespace", "owner_token_ref", "effect");
            require(resources.add(identifier(resource, "resource_id")));
            require(identifier(resource, "run_namespace").equals(controller.path("run_namespace").asText()));
            String owner = text(resource, "owner_token_ref", 140);
            require(ownerRef(owner) && owners.contains(owner));
            require(Set.of("READ_ONLY", "OWNED_WRITE", "OWNED_CLEANUP").contains(text(resource, "effect", 32)));
        }
        Map<String, String> suts = new HashMap<>(), sourceSuts = new HashMap<>();
        for (JsonNode sut : array(c, "sut_expectations", 1, 16)) {
            fields(sut, "sut_id", "expected_deployed_revision");
            require(suts.putIfAbsent(identifier(sut, "sut_id"), text(sut, "expected_deployed_revision", 256)) == null);
        }
        for (JsonNode sut : source.path("sut_expectations")) sourceSuts.put(sut.path("sut_id").asText(), sut.path("expected_deployed_revision").asText());
        require(suts.equals(sourceSuts));
        Set<String> checkIds = new HashSet<>(), sutChecks = new HashSet<>();
        int data = 0, schema = 0, auth = 0;
        for (JsonNode pre : array(c, "preconditions", 4, 32)) {
            fields(pre, "check_id", "kind", "purpose", "subject_id", "expected_version");
            require(checkIds.add(identifier(pre, "check_id")));
            String kind = text(pre, "kind", 32), purpose = text(pre, "purpose", 32), subject = identifier(pre, "subject_id");
            String version = text(pre, "expected_version", 256);
            require(Set.of("test_data", "external_system", "dependency").contains(kind));
            switch (purpose) {
                case "DATA_VERSION" -> { require(kind.equals("test_data") && subject.equals(c.path("dataset_id").asText()) && version.equals(c.path("data_version").asText())); data++; }
                case "SCHEMA_VERSION" -> { require(kind.equals("test_data") && subject.equals(c.path("dataset_id").asText()) && version.equals(c.path("schema_version").asText())); schema++; }
                case "AUTH" -> { require(kind.equals("external_system")); auth++; }
                case "DEPENDENCY" -> require(kind.equals("dependency"));
                case "SUT" -> require(kind.equals("external_system") && version.equals(suts.get(subject)) && sutChecks.add(subject));
                default -> throw new IllegalArgumentException();
            }
        }
        require(data == 1 && schema == 1 && auth > 0 && sutChecks.equals(suts.keySet()));
        return identity;
    }

    private SitReadinessState readiness(JsonNode contract, JsonNode controller, SitReadinessReport report, Set<String> gaps) {
        try {
            require(report != null && report.state() != null && report.startedAt() != null && report.finishedAt() != null
                    && report.checks().size() >= 1 && report.checks().size() <= 32);
            for (var check : report.checks()) {
                require(check != null && check.state() != null && check.evidence().size() <= 32);
                for (var entry : check.evidence().entrySet()) require(bounded(entry.getKey(), 256) && bounded(entry.getValue(), 2048));
                require(bounded(check.id(), 128) && bounded(check.kind(), 32) && bounded(check.adapter(), 128)
                        && bounded(check.adapterVersion(), 256) && bounded(check.expectedVersion(), 256)
                        && check.observedVersion() != null && check.observedVersion().length() <= 256
                        && check.ownerAction() != null && check.ownerAction().length() <= 2048);
            }
            require(hash(canonical(JSON.valueToTree(report.toMap()))).equals(controller.path("readiness_sha256").asText()));
            Instant now = clock.instant();
            require(!report.startedAt().isBefore(now.minusSeconds(300)) && !report.finishedAt().isBefore(report.startedAt())
                    && !report.finishedAt().isAfter(now));
            Map<String, SitReadinessReport.Check> checks = new HashMap<>();
            for (var check : report.checks()) require(checks.putIfAbsent(check.id(), check) == null);
            require(checks.size() == contract.path("preconditions").size());
            for (JsonNode pre : contract.path("preconditions")) {
                var observed = checks.get(pre.path("check_id").asText());
                require(observed != null && pre.path("kind").asText().equals(observed.kind())
                        && pre.path("expected_version").asText().equals(observed.expectedVersion()));
                if (observed.state() != SitReadinessState.READY) { gaps.add("READINESS_NOT_READY"); return observed.state(); }
                require(observed.observedAt() != null && !observed.observedAt().isBefore(report.startedAt())
                        && !observed.observedAt().isAfter(report.finishedAt()) && !observed.evidence().isEmpty()
                        && observed.expectedVersion().equals(observed.observedVersion()));
                if ("SHARED".equals(controller.path("scope").asText()) && observed.simulated()) {
                    gaps.add("SIMULATED_SHARED_READINESS"); return SitReadinessState.BLOCKED;
                }
            }
            if (report.state() != SitReadinessState.READY) { gaps.add("READINESS_NOT_READY"); return report.state(); }
            Map<String, JsonNode> sutChecks = new HashMap<>();
            for (JsonNode pre : contract.path("preconditions")) if ("SUT".equals(pre.path("purpose").asText())) sutChecks.put(pre.path("subject_id").asText(), pre);
            require(controller.path("actual_sut_revisions").size() == sutChecks.size());
            for (JsonNode actual : controller.path("actual_sut_revisions")) {
                JsonNode expected = sutChecks.get(actual.path("sut_id").asText());
                require(expected != null && expected.path("check_id").asText().equals(actual.path("observation_ref").asText())
                        && expected.path("expected_version").asText().equals(actual.path("observed_revision").asText()));
            }
            return SitReadinessState.READY;
        } catch (RuntimeException invalid) { gaps.add("READINESS_VERSION_OR_IDENTITY_INVALID"); return SitReadinessState.INVALID; }
    }

    private static CanonicalMatch canonicalIdentity(JsonNode expected, byte[] raw, String actualSha) {
        if (raw == null || raw.length == 0) return new CanonicalMatch(CanonicalIdentityState.MISSING, "UNKNOWN");
        require(actualSha != null && actualSha.equals(digest(expected, "sha256")));
        JsonNode result = parse(raw, CANONICAL_JSON);
        require("v0.3".equals(result.path("dsl_version").asText()) && "v0.3".equals(result.path("result_contract_version").asText()));
        for (String name : List.of("run_id", "suite_id", "profile", "batch_id")) require(text(expected, name, 128).equals(text(result, name, 128)));
        String status = text(result, "status", 32);
        require(Set.of("passed", "failed", "blocked", "skipped").contains(status));
        return new CanonicalMatch(CanonicalIdentityState.MATCHED, status);
    }

    private static ObjectMapper mapper(int depth, int maxString) {
        return new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(depth).maxStringLength(maxString).maxNameLength(128).build()).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private static JsonNode pinned(byte[] bytes, String expected) {
        require(bytes != null && bytes.length > 0 && bytes.length <= METADATA_LIMIT && expected != null
                && expected.matches("[a-f0-9]{64}") && hash(bytes).equals(expected));
        return parse(bytes, JSON);
    }
    private static JsonNode parse(byte[] bytes, ObjectMapper mapper) {
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            JsonNode result = mapper.readTree(utf8);
            require(result != null && result.isObject());
            return result;
        }
        catch (java.io.IOException error) { throw new IllegalArgumentException("INVALID_BOUNDED_DOCUMENT"); }
    }
    private static void fields(JsonNode node, String... expected) {
        require(node != null && node.isObject()); Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(Set.of(expected)));
    }
    private static String text(JsonNode node, String name, int limit) {
        JsonNode value = node.get(name); require(value != null && value.isTextual() && bounded(value.asText(), limit)); return value.asText();
    }
    private static String identifier(JsonNode node, String name) {
        String value = text(node, name, 128); require(value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")); return value;
    }
    private static String digest(JsonNode node, String name) {
        String value = text(node, name, 64); require(value.matches("[a-f0-9]{64}")); return value;
    }
    private static boolean ownerRef(String value) { return value.matches("owner-ref://[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"); }
    private static boolean bounded(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static List<JsonNode> array(JsonNode node, String name, int minimum, int maximum) {
        JsonNode values = node.get(name); require(values != null && values.isArray() && values.size() >= minimum && values.size() <= maximum);
        List<JsonNode> result = new ArrayList<>(); values.forEach(result::add); return result;
    }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException("INVALID_DECLARED_METADATA"); }
    private static byte[] snapshot(byte[] bytes, int limit) {
        return bytes == null || bytes.length > limit ? bytes : bytes.clone();
    }
    private static String boundedHash(byte[] bytes, int limit) { return bytes == null || bytes.length == 0 || bytes.length > limit ? null : hash(bytes); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    private static JsonNode ordered(JsonNode node) {
        if (node.isObject()) { ObjectNode result = JSON.createObjectNode(); Set<String> keys = new TreeSet<>(); node.fieldNames().forEachRemaining(keys::add); for (String key : keys) result.set(key, ordered(node.get(key))); return result; }
        if (node.isArray()) { ArrayNode result = JSON.createArrayNode(); node.forEach(value -> result.add(ordered(value))); return result; }
        return node.deepCopy();
    }
    private static byte[] canonical(JsonNode node) {
        try { return JSON.writeValueAsBytes(ordered(node)); }
        catch (java.io.IOException error) { throw new IllegalArgumentException("INVALID_BOUNDED_DOCUMENT"); }
    }
    private static void sort(ArrayNode values, String field) {
        List<JsonNode> sorted = new ArrayList<>(); values.forEach(sorted::add); sorted.sort(Comparator.comparing(value -> value.path(field).asText())); values.removeAll(); sorted.forEach(values::add);
    }
    private static JsonNode normalizedContract(JsonNode contract) {
        ObjectNode normalized = contract.deepCopy(); sort((ArrayNode)normalized.path("resources"), "resource_id"); sort((ArrayNode)normalized.path("preconditions"), "check_id"); sort((ArrayNode)normalized.path("sut_expectations"), "sut_id"); return normalized;
    }
    static String semanticKey(JsonNode contract, JsonNode sourceIdentity, JsonNode runIdentity) {
        ObjectNode value = JSON.createObjectNode(); value.set("contract", contract); value.set("source", sourceIdentity); value.set("run", runIdentity);
        byte[] domain = "shared-sit-data.idempotency.v1\n".getBytes(StandardCharsets.UTF_8), payload = canonical(value);
        byte[] bytes = new byte[domain.length + payload.length]; System.arraycopy(domain, 0, bytes, 0, domain.length); System.arraycopy(payload, 0, bytes, domain.length, payload.length); return hash(bytes);
    }
    private record CanonicalMatch(CanonicalIdentityState state, String verdict) { }
}

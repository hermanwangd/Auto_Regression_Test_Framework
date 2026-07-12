package com.specdriven.regression.evidence;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ResultContractValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

public class EvidenceHardeningService {

    private static final Set<String> APPROVED_EVIDENCE_TYPES = Set.of(
            "execution_log",
            "batch_summary",
            "fixture_setup",
            "fixture_cleanup",
            "wiremock_request_journal",
            "wiremock_server_log",
            "jdbc_seed",
            "jdbc_query",
            "jdbc_cleanup",
            "nats_event",
            "kafka_event",
            "ibm_mq_event",
            "http_request_response",
            "grpc_request_response",
            "assertion_diff",
            "polling_observation");
    private static final Set<String> PROVIDER_EVIDENCE_TYPES = Set.of(
            "fixture_setup",
            "fixture_cleanup",
            "wiremock_request_journal",
            "wiremock_server_log",
            "jdbc_seed",
            "jdbc_query",
            "jdbc_cleanup",
            "nats_event",
            "kafka_event",
            "ibm_mq_event",
            "http_request_response",
            "grpc_request_response");
    private static final List<String> ENTRY_REQUIRED_FIELDS = List.of(
            "evidence_id",
            "evidence_type",
            "produced_by",
            "test_case_id",
            "run_id",
            "batch_id",
            "file_path",
            "content_type",
            "status",
            "created_at",
            "masking_applied",
            "linked_result_field");
    private static final Pattern JSON_SECRET_FIELD = Pattern.compile(
            "(?i)\"(?:password|token|secret|credential|api_key|authorization)\"\\s*:\\s*\""
                    + "(?!\\*\\*\\*MASKED\\*\\*\\*|masked|secret://|vault://|generated://|\\$\\{)[^\"]{4,}\"");
    private static final Pattern TEXT_SECRET_FIELD = Pattern.compile(
            "(?i)(?:password|token|secret|credential|api_key)\\b\\s*(?:=|:(?!//))\\s*"
                    + "(?!\\*\\*\\*MASKED\\*\\*\\*|masked|secret://|vault://|generated://|\\$\\{|true|false|null)[^\\s,}\\]]{4,}");
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
            "(?i)authorization\\s*[:=]\\s*(?!\\*\\*\\*MASKED\\*\\*\\*|masked|\\$\\{|secret://|vault://)[^\\s,}\\]]{6,}");
    private static final Pattern JDBC_URL = Pattern.compile("(?i)\\bjdbc:[a-z0-9_+.-]+:[^\\s\"']+");
    private static final Pattern MONGO_URI = Pattern.compile("(?i)mongodb(?:\\+srv)?://[^\\s\"']+");
    private static final Pattern NATS_CREDENTIAL = Pattern.compile("(?i)nats://[^\\s/@:]+:[^\\s/@]+@[^\\s\"']+");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bbearer\\s+[a-z0-9._~+/=-]{8,}");
    private static final Pattern CONNECTION_URI =
            Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s/@:]+:[^\\s/@]+@[^\\s\"']+");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");

    private final Yaml yaml = new Yaml();

    public EvidenceValidationResult validateResult(Path resultJson) {
        List<ContractFinding> findings = new ArrayList<>();
        if (!Files.isRegularFile(resultJson)) {
            findings.add(finding(resultJson, "result", "missing_result_json",
                    "Provide an existing standard result JSON file."));
            return emptyResult(false, resultJson, findings);
        }
        String resultText;
        Map<String, Object> result;
        try {
            resultText = Files.readString(resultJson);
            result = mapValue(yaml.load(resultText));
        } catch (IOException | RuntimeException e) {
            findings.add(finding(resultJson, "result", "invalid_result_json",
                    "Fix malformed result JSON before validating evidence."));
            return emptyResult(false, resultJson, findings);
        }
        if (rawSecretDetected(resultText)) {
            findings.add(finding(resultJson, "result", "raw_secret",
                    "Mask passwords, tokens, JDBC URLs, credentials, Authorization headers, and private keys in result JSON."));
        }
        findings.addAll(ResultContractValidator.validate(resultJson, result));

        Path indexPath = evidenceIndexPath(resultJson, result);
        if (indexPath == null || !Files.isRegularFile(indexPath)) {
            findings.add(finding(indexPath == null ? resultJson : indexPath, "evidence_index_ref", "missing_evidence_index",
                    "Create evidence/evidence_index.yaml and reference it from result JSON."));
            return result(resultJson, result, indexPath, List.of(), findings);
        }

        Map<String, Object> index = readIndex(indexPath, findings);
        List<Map<String, Object>> entries = mapList(index.get("entries"));
        validateIndexHeader(indexPath, index, findings);
        List<EvidenceEntry> evidenceEntries = validateEntries(indexPath, entries, findings);
        validateResultEvidenceRefs(resultJson, result, indexPath, evidenceEntries, findings);
        validateRequiredEvidence(resultJson, result, evidenceEntries, findings);
        validateFailureEvidence(resultJson, result, evidenceEntries, findings);
        validatePollingEvidence(resultJson, result, findings);
        validateCleanupEvidence(resultJson, result, evidenceEntries, findings);
        validateEvidenceFiles(indexPath, evidenceEntries, findings);
        return result(resultJson, result, indexPath, evidenceEntries, findings);
    }

    public boolean hasEvidenceIndexReference(Path resultJson) {
        if (!Files.isRegularFile(resultJson)) {
            return false;
        }
        try {
            Map<String, Object> result = mapValue(yaml.load(Files.readString(resultJson)));
            return evidenceIndexPath(resultJson, result) != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private Map<String, Object> readIndex(Path indexPath, List<ContractFinding> findings) {
        try {
            return mapValue(yaml.load(Files.readString(indexPath)));
        } catch (IOException | RuntimeException e) {
            findings.add(finding(indexPath, "entries", "invalid_evidence_index",
                    "Fix malformed evidence_index.yaml before validating evidence."));
            return Map.of();
        }
    }

    private void validateIndexHeader(Path indexPath, Map<String, Object> index, List<ContractFinding> findings) {
        for (String field : List.of("evidence_index_version", "suite_id", "batch_id", "run_id", "test_case_id", "entries")) {
            if (isMissing(index.get(field))) {
                findings.add(finding(indexPath, field, "missing_required_field",
                        "Add required evidence index field `" + field + "`."));
            }
        }
    }

    private List<EvidenceEntry> validateEntries(
            Path indexPath,
            List<Map<String, Object>> entries,
            List<ContractFinding> findings) {
        List<EvidenceEntry> evidenceEntries = new ArrayList<>();
        Set<String> evidenceIds = new LinkedHashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            Map<String, Object> entry = entries.get(index);
            String entryPath = "entries[" + index + "]";
            for (String field : ENTRY_REQUIRED_FIELDS) {
                if (isMissing(entry.get(field))) {
                    findings.add(finding(indexPath, entryPath + "." + field, "missing_required_evidence_field",
                            "Add required evidence index entry field `" + field + "`."));
                }
            }
            String evidenceId = stringValue(entry.get("evidence_id"));
            String evidenceType = stringValue(entry.get("evidence_type"));
            if (!evidenceId.isBlank() && !evidenceIds.add(evidenceId)) {
                findings.add(finding(indexPath, entryPath + ".evidence_id", "duplicate_evidence_id",
                        "Use a unique evidence_id per run."));
            }
            if (!evidenceType.isBlank() && !APPROVED_EVIDENCE_TYPES.contains(evidenceType)) {
                findings.add(finding(indexPath, entryPath + ".evidence_type", "unsupported_evidence_type",
                        "Use an approved v0.2 evidence_type catalog value."));
            }
            if (PROVIDER_EVIDENCE_TYPES.contains(evidenceType) || "provider".equals(stringValue(entry.get("produced_by")))) {
                if (stringValue(entry.get("provider_type")).isBlank()) {
                    findings.add(finding(indexPath, entryPath + ".provider_type", "missing_provider_metadata",
                            "Provider evidence entries must include provider_type."));
                }
                if (stringValue(entry.get("provider_id")).isBlank()) {
                    findings.add(finding(indexPath, entryPath + ".provider_id", "missing_provider_metadata",
                            "Provider evidence entries must include provider_id."));
                }
            }
            if (!boolValue(entry.get("masking_applied"))) {
                findings.add(finding(indexPath, entryPath + ".masking_applied", "masking_not_applied",
                        "Set masking_applied to true only after evidence payloads are masked."));
            }
            if ("failed".equals(stringValue(entry.get("status"))) && stringValue(entry.get("failure_code")).isBlank()) {
                findings.add(finding(indexPath, entryPath + ".failure_code", "missing_failure_code",
                        "Failed evidence entries must include failure_code."));
            }
            evidenceEntries.add(new EvidenceEntry(
                    evidenceId,
                    evidenceType,
                    stringValue(entry.get("produced_by")),
                    stringValue(entry.get("provider_type")),
                    stringValue(entry.get("provider_id")),
                    stringValue(entry.get("test_case_id")),
                    stringValue(entry.get("run_id")),
                    stringValue(entry.get("batch_id")),
                    stringValue(entry.get("file_path")),
                    stringValue(entry.get("content_type")),
                    stringValue(entry.get("status")),
                    stringValue(entry.get("created_at")),
                    boolValue(entry.get("masking_applied")),
                    stringValue(entry.get("linked_result_field")),
                    stringValue(entry.get("failure_code"))));
        }
        return List.copyOf(evidenceEntries);
    }

    private void validateResultEvidenceRefs(
            Path resultJson,
            Map<String, Object> result,
            Path indexPath,
            List<EvidenceEntry> entries,
            List<ContractFinding> findings) {
        Set<String> knownRefs = new LinkedHashSet<>();
        knownRefs.add("evidence_index.yaml");
        knownRefs.add(stringValue(result.get("evidence_index_ref")));
        try {
            knownRefs.add(resultJson.getParent().relativize(indexPath).toString());
        } catch (RuntimeException ignored) {
            knownRefs.add(indexPath.toString());
        }
        for (EvidenceEntry entry : entries) {
            knownRefs.add(entry.evidenceId());
            knownRefs.add("evidence://" + entry.evidenceId());
            knownRefs.add(entry.filePath());
            Path evidencePath = resolveEvidencePath(indexPath, entry.filePath());
            knownRefs.add(evidencePath.toString());
            try {
                knownRefs.add(resultJson.getParent().relativize(evidencePath).toString());
            } catch (RuntimeException ignored) {
                knownRefs.add(evidencePath.toAbsolutePath().normalize().toString());
            }
        }
        for (String ref : evidenceRefs(result)) {
            if (ref.isBlank() || ref.endsWith("evidence_index.yaml")) {
                continue;
            }
            if (!knownRefs.contains(ref) && !knownRefs.contains(stripFragment(ref))) {
                findings.add(finding(resultJson, "evidence_refs", "unknown_evidence_ref",
                        "Add evidence ref `" + ref + "` to evidence_index.yaml or remove the stale result reference."));
            }
        }
    }

    private void validateRequiredEvidence(
            Path resultJson,
            Map<String, Object> result,
            List<EvidenceEntry> entries,
            List<ContractFinding> findings) {
        Set<String> presentTypes = evidenceTypes(entries);
        Set<String> requiredTypes = new LinkedHashSet<>(List.of("execution_log", "batch_summary"));
        for (Map<String, Object> providerResult : mapList(result.get("provider_results"))) {
            switch (stringValue(providerResult.get("provider_type"))) {
                case "wiremock_http_mock" -> {
                    requiredTypes.add("wiremock_request_journal");
                    requiredTypes.add("wiremock_server_log");
                }
                case "soap_mock" -> {
                    requiredTypes.add("wiremock_request_journal");
                    requiredTypes.add("wiremock_server_log");
                }
                case "jdbc", "jdbc_database" -> {
                    requireJdbcEvidenceForExecutedOperations(providerResult, requiredTypes);
                }
                case "nats" -> requiredTypes.add("nats_event");
                case "kafka" -> requiredTypes.add("kafka_event");
                case "ibm_mq" -> requiredTypes.add("ibm_mq_event");
                case "rest_client" -> requiredTypes.add("http_request_response");
                case "grpc_mock" -> {
                    requiredTypes.add("wiremock_request_journal");
                    requiredTypes.add("wiremock_server_log");
                }
                case "grpc_client" -> requiredTypes.add("grpc_request_response");
                default -> {
                }
            }
        }
        for (Map<String, Object> verifyResult : mapList(result.get("verify_results"))) {
            String verifierType = stringValue(verifyResult.get("type"));
            if (List.of("json_match", "schema_match", "file_diff").contains(verifierType)) {
                requiredTypes.add("assertion_diff");
            }
            if (!mapValue(verifyResult.get("polling")).isEmpty()) {
                requiredTypes.add("polling_observation");
            }
        }
        for (String requiredType : requiredTypes) {
            if (!presentTypes.contains(requiredType)) {
                findings.add(finding(resultJson, "evidence_index.entries", "missing_required_evidence",
                        "Add required evidence entry type `" + requiredType + "` for this result."));
            }
        }
    }

    private void requireJdbcEvidenceForExecutedOperations(
            Map<String, Object> providerResult,
            Set<String> requiredTypes) {
        List<String> operations = new ArrayList<>();
        for (String operation : stringList(providerResult.get("operations"))) {
            if (operation.contains(",")) {
                for (String item : operation.split(",")) {
                    if (!item.isBlank()) {
                        operations.add(item.trim());
                    }
                }
            } else if (!operation.isBlank()) {
                operations.add(operation);
            }
        }
        if (operations.isEmpty()) {
            operations.addAll(stringList(providerResult.get("operation")));
        }
        if (operations.isEmpty()) {
            requiredTypes.add("jdbc_seed");
            requiredTypes.add("jdbc_query");
            requiredTypes.add("jdbc_cleanup");
            return;
        }
        for (String operation : operations) {
            switch (operation) {
                case "db_seed" -> requiredTypes.add("jdbc_seed");
                case "db_query", "db_record_exists" -> requiredTypes.add("jdbc_query");
                case "db_cleanup" -> requiredTypes.add("jdbc_cleanup");
                default -> {
                }
            }
        }
    }

    private void validateFailureEvidence(
            Path resultJson,
            Map<String, Object> result,
            List<EvidenceEntry> entries,
            List<ContractFinding> findings) {
        boolean verifierFailed = mapList(result.get("verify_results")).stream()
                .anyMatch(verify -> !List.of("", "passed").contains(stringValue(verify.get("status"))));
        if (!verifierFailed) {
            return;
        }
        boolean hasFailureEvidence = entries.stream()
                .anyMatch(entry -> Set.of("assertion_diff", "polling_observation").contains(entry.evidenceType())
                        && "failed".equals(entry.status())
                        && !entry.failureCode().isBlank());
        if (!hasFailureEvidence) {
            findings.add(finding(resultJson, "verify_results", "missing_failure_evidence",
                    "Failed verifications must include failed assertion_diff or polling_observation evidence with failure_code."));
        }
    }

    private void validateCleanupEvidence(
            Path resultJson,
            Map<String, Object> result,
            List<EvidenceEntry> entries,
            List<ContractFinding> findings) {
        boolean verifierFailed = verifierFailed(result);
        boolean cleanupFailed = mapList(result.get("provider_results")).stream()
                .anyMatch(provider -> "failed".equals(stringValue(provider.get("cleanup_status")))
                        || containsCleanupFailure(provider));
        if (!cleanupFailed) {
            return;
        }
        boolean cleanupFailureIndexed = entries.stream()
                .anyMatch(entry -> Set.of("fixture_cleanup", "jdbc_cleanup").contains(entry.evidenceType())
                        && "failed".equals(entry.status())
                        && !entry.failureCode().isBlank());
        if (!cleanupFailureIndexed) {
            findings.add(finding(resultJson, "provider_results.cleanup_status", "cleanup_failure_not_indexed",
                    "Cleanup failure must be indexed as failed cleanup evidence and must not hide the original failure."));
        }
        Map<String, Object> failure = mapValue(result.get("failure"));
        String code = stringValue(failure.get("code")).toLowerCase();
        String classification = stringValue(failure.get("classification")).toLowerCase();
        if (verifierFailed && (code.contains("cleanup") || classification.contains("cleanup"))) {
            findings.add(finding(resultJson, "failure", "cleanup_failure_hides_original_failure",
                    "Preserve the original verification failure in failure.code/classification and nest cleanup failure under failure.cleanup_failure."));
        }
    }

    private void validatePollingEvidence(
            Path resultJson,
            Map<String, Object> result,
            List<ContractFinding> findings) {
        for (Map<String, Object> verifyResult : mapList(result.get("verify_results"))) {
            Map<String, Object> polling = mapValue(verifyResult.get("polling"));
            if (polling.isEmpty()) {
                continue;
            }
            boolean failed = !"passed".equals(stringValue(verifyResult.get("status")));
            String failureCode = stringValue(verifyResult.get("failure_code"))
                    + stringValue(polling.get("failure_code"));
            String verifierType = stringValue(verifyResult.get("type"));
            if (failed
                    && (failureCode.contains("POLLING_TIMEOUT")
                    || List.of("event_published", "db_record_exists").contains(verifierType))
                    && stringValue(polling.get("last_observed_ref")).isBlank()) {
                findings.add(finding(resultJson, "verify_results." + stringValue(verifyResult.get("id")) + ".polling.last_observed_ref",
                        "missing_polling_last_observed_evidence",
                        "Polling timeout must include last_observed_ref linked to polling observation evidence."));
            }
        }
    }

    private boolean verifierFailed(Map<String, Object> result) {
        return mapList(result.get("verify_results")).stream()
                .anyMatch(verify -> !List.of("", "passed").contains(stringValue(verify.get("status"))));
    }

    private void validateEvidenceFiles(Path indexPath, List<EvidenceEntry> entries, List<ContractFinding> findings) {
        Set<Path> scanned = new LinkedHashSet<>();
        Path evidenceRoot = indexPath.getParent().toAbsolutePath().normalize();
        for (EvidenceEntry entry : entries) {
            if (entry.filePath().isBlank()) {
                continue;
            }
            Path evidencePath = resolveEvidencePath(indexPath, entry.filePath());
            Path normalizedEvidencePath = evidencePath.toAbsolutePath().normalize();
            if (!normalizedEvidencePath.startsWith(evidenceRoot)) {
                findings.add(finding(evidencePath, "file_path", "invalid_evidence_path",
                        "Keep evidence file paths under the evidence index folder and remove `..` or external absolute paths."));
                continue;
            }
            if (!scanned.add(evidencePath)) {
                continue;
            }
            if (!Files.isRegularFile(evidencePath)) {
                findings.add(finding(evidencePath, "file_path", "missing_evidence_file",
                        "Restore missing evidence file `" + entry.filePath() + "` or remove the stale index entry."));
                continue;
            }
            try {
                String content = Files.readString(evidencePath);
                if (rawSecretDetected(content)) {
                    findings.add(finding(evidencePath, "content", "raw_secret",
                            "Mask raw secrets in evidence payloads, params, query results, logs, and failure messages."));
                }
            } catch (IOException e) {
                findings.add(finding(evidencePath, "file_path", "unreadable_evidence_file",
                        "Make evidence files readable before validating evidence."));
            }
        }
    }

    private boolean containsCleanupFailure(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object nested = entry.getValue();
                if (key.toLowerCase().contains("cleanup")
                        && String.valueOf(nested).toLowerCase().contains("failed")) {
                    return true;
                }
                if (containsCleanupFailure(nested)) {
                    return true;
                }
            }
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(this::containsCleanupFailure);
        }
        return false;
    }

    private EvidenceValidationResult result(
            Path resultJson,
            Map<String, Object> result,
            Path indexPath,
            List<EvidenceEntry> entries,
            List<ContractFinding> findings) {
        List<ProviderEvidenceSummary> providerSummaries = providerSummaries(entries);
        List<String> failedEvidenceSummary = entries.stream()
                .filter(entry -> "failed".equals(entry.status()))
                .map(entry -> entry.evidenceId() + ":" + entry.evidenceType() + ":"
                        + (entry.failureCode().isBlank() ? "failed" : entry.failureCode()))
                .toList();
        int missingEvidenceCount = (int) findings.stream()
                .filter(finding -> finding.reason().startsWith("missing_evidence")
                        || finding.reason().equals("unknown_evidence_ref")
                        || finding.reason().equals("missing_required_evidence"))
                .count();
        boolean maskingPassed = findings.stream().noneMatch(finding -> Set.of("raw_secret", "masking_not_applied")
                .contains(finding.reason()));
        List<Map<String, Object>> testResults = mapList(result.get("test_results"));
        int testCount = testResults.isEmpty()
                ? (stringValue(result.get("test_case_id")).isBlank() ? 0 : 1)
                : testResults.size();
        int passCount = testResults.isEmpty()
                ? ("passed".equals(stringValue(result.get("status"))) ? 1 : 0)
                : (int) testResults.stream()
                        .filter(testResult -> "passed".equals(stringValue(testResult.get("status"))))
                        .count();
        int failCount = testResults.isEmpty()
                ? ("failed".equals(stringValue(result.get("status"))) ? 1 : 0)
                : countStatus(testResults, "failed");
        int blockedCount = testResults.isEmpty()
                ? ("blocked".equals(stringValue(result.get("status"))) ? 1 : 0)
                : countStatus(testResults, "blocked");
        int skippedCount = testResults.isEmpty()
                ? ("skipped".equals(stringValue(result.get("status"))) ? 1 : 0)
                : countStatus(testResults, "skipped");
        return new EvidenceValidationResult(
                findings.isEmpty(),
                stringValue(result.get("suite_id")),
                stringValue(result.get("batch_id")),
                stringValue(result.get("run_id")),
                testCount,
                passCount,
                failCount,
                blockedCount,
                skippedCount,
                indexPath == null ? resultJson.getParent() : indexPath.getParent(),
                missingEvidenceCount,
                failedEvidenceSummary.size(),
                maskingPassed,
                providerSummaries,
                failedEvidenceSummary,
                List.copyOf(findings));
    }

    private EvidenceValidationResult emptyResult(boolean valid, Path resultJson, List<ContractFinding> findings) {
        return new EvidenceValidationResult(
                valid,
                "",
                "",
                "",
                0,
                0,
                0,
                0,
                0,
                resultJson.getParent(),
                (int) findings.stream().filter(finding -> finding.reason().startsWith("missing_evidence")).count(),
                0,
                findings.stream().noneMatch(finding -> "raw_secret".equals(finding.reason())),
                List.of(),
                List.of(),
                List.copyOf(findings));
    }

    private List<ProviderEvidenceSummary> providerSummaries(List<EvidenceEntry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (EvidenceEntry entry : entries) {
            if (entry.providerType().isBlank() && entry.providerId().isBlank()) {
                continue;
            }
            String key = entry.providerType() + "\n" + entry.providerId();
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        List<ProviderEvidenceSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String[] parts = entry.getKey().split("\n", -1);
            summaries.add(new ProviderEvidenceSummary(parts[0], parts[1], entry.getValue()));
        }
        summaries.sort(Comparator
                .comparing(ProviderEvidenceSummary::providerType)
                .thenComparing(ProviderEvidenceSummary::providerId));
        return List.copyOf(summaries);
    }

    private Set<String> evidenceTypes(List<EvidenceEntry> entries) {
        Set<String> types = new LinkedHashSet<>();
        for (EvidenceEntry entry : entries) {
            if (!entry.evidenceType().isBlank()) {
                types.add(entry.evidenceType());
            }
        }
        return types;
    }

    private List<String> evidenceRefs(Map<String, Object> result) {
        List<String> refs = new ArrayList<>();
        collectEvidenceRefs(result, "", refs);
        return refs.stream().map(this::stripFragment).filter(ref -> !ref.isBlank()).distinct().toList();
    }

    private void collectEvidenceRefs(Object value, String fieldPath, List<String> refs) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = fieldPath.isBlank() ? key : fieldPath + "." + key;
                if ("evidence_index_ref".equals(key)) {
                    continue;
                }
                if (List.of("evidence_refs", "provider_evidence_refs").contains(key)) {
                    refs.addAll(stringList(entry.getValue()));
                    continue;
                }
                if (key.endsWith("_evidence_ref") || "last_observed_ref".equals(key)) {
                    String ref = stringValue(entry.getValue());
                    if (!ref.isBlank()) {
                        refs.add(ref);
                    }
                    continue;
                }
                collectEvidenceRefs(entry.getValue(), childPath, refs);
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectEvidenceRefs(item, fieldPath, refs);
            }
        }
    }

    private boolean rawSecretDetected(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return JSON_SECRET_FIELD.matcher(text).find()
                || TEXT_SECRET_FIELD.matcher(text).find()
                || AUTHORIZATION_HEADER.matcher(text).find()
                || JDBC_URL.matcher(text).find()
                || MONGO_URI.matcher(text).find()
                || NATS_CREDENTIAL.matcher(text).find()
                || BEARER_TOKEN.matcher(text).find()
                || CONNECTION_URI.matcher(text).find()
                || PRIVATE_KEY.matcher(text).find();
    }

    private Path evidenceIndexPath(Path resultJson, Map<String, Object> result) {
        Path resultParent = resultJson.getParent() == null ? Path.of(".") : resultJson.getParent();
        String configuredRef = stringValue(result.get("evidence_index_ref"));
        if (!configuredRef.isBlank()) {
            return resultParent.resolve(configuredRef).normalize();
        }
        for (String ref : stringList(result.get("evidence_refs"))) {
            if (ref.endsWith("evidence_index.yaml")) {
                return resultParent.resolve(ref).normalize();
            }
        }
        Path sameDir = resultParent.resolve("evidence_index.yaml").normalize();
        if (Files.exists(sameDir)) {
            return sameDir;
        }
        Path evidenceDir = resultParent.resolve("evidence/evidence_index.yaml").normalize();
        if (Files.exists(evidenceDir)) {
            return evidenceDir;
        }
        return null;
    }

    private Path resolveEvidencePath(Path indexPath, String filePath) {
        Path path = Path.of(filePath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return indexPath.getParent().resolve(path).normalize();
    }

    private ContractFinding finding(Path path, String fieldPath, String reason, String ownerAction) {
        return new ContractFinding(
                path == null ? "" : path.toString(),
                fieldPath,
                reason,
                "",
                "",
                "",
                "",
                ownerAction);
    }

    private String stripFragment(String value) {
        String text = value == null ? "" : value.trim();
        int fragment = text.indexOf('#');
        return fragment >= 0 ? text.substring(0, fragment) : text;
    }

    private boolean isMissing(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private List<Map<String, Object>> mapList(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : listValue(value)) {
            result.add(mapValue(item));
        }
        return result;
    }

    private List<Object> listValue(Object value) {
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return List.of();
    }

    private List<String> stringList(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::stringValue).toList();
        }
        String text = stringValue(value);
        return text.isBlank() ? List.of() : List.of(text);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int countStatus(List<Map<String, Object>> testResults, String status) {
        return (int) testResults.stream()
                .filter(testResult -> status.equals(stringValue(testResult.get("status"))))
                .count();
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(stringValue(value));
    }

    public record EvidenceValidationResult(
            boolean valid,
            String suiteId,
            String batchId,
            String runId,
            int testCount,
            int passCount,
            int failCount,
            int blockedCount,
            int skippedCount,
            Path evidenceDir,
            int missingEvidenceCount,
            int failedEvidenceCount,
            boolean maskingPassed,
            List<ProviderEvidenceSummary> providerEvidenceSummary,
            List<String> failedEvidenceSummary,
            List<ContractFinding> findings) {
    }

    public record ProviderEvidenceSummary(
            String providerType,
            String providerId,
            int evidenceCount) {
    }

    private record EvidenceEntry(
            String evidenceId,
            String evidenceType,
            String producedBy,
            String providerType,
            String providerId,
            String testCaseId,
            String runId,
            String batchId,
            String filePath,
            String contentType,
            String status,
            String createdAt,
            boolean maskingApplied,
            String linkedResultField,
            String failureCode) {
    }
}

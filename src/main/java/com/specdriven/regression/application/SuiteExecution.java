package com.specdriven.regression.application;

import static com.specdriven.regression.application.SuiteDocuments.readYamlMap;
import static com.specdriven.regression.application.SuiteDocuments.mapValue;
import static com.specdriven.regression.application.SuiteDocuments.stringValue;
import static com.specdriven.regression.application.SuiteValidation.finding;

import com.specdriven.regression.application.SuiteValidation.SuiteGroupChild;
import com.specdriven.regression.application.SuiteValidation.SuiteGroupDefinition;
import com.specdriven.regression.application.SuiteValidation.SuiteGroupValidation;
import com.specdriven.regression.contract.CommonVerifyService;
import com.specdriven.regression.contract.CommonVerifyService.CommonVerifyRunResult;
import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.DryRunResult;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.contract.GoldenE2eService;
import com.specdriven.regression.contract.GoldenE2eService.GoldenRunResult;
import com.specdriven.regression.contract.GrpcMockCapabilityService;
import com.specdriven.regression.contract.GrpcMockCapabilityService.GrpcRunResult;
import com.specdriven.regression.contract.MessagingClientProviderCapabilityService;
import com.specdriven.regression.contract.MessagingClientProviderCapabilityService.MessagingClientRunResult;
import com.specdriven.regression.contract.NatsProviderCapabilityService;
import com.specdriven.regression.contract.NatsProviderCapabilityService.NatsRunResult;
import com.specdriven.regression.contract.RestClientCapabilityService;
import com.specdriven.regression.contract.RestClientCapabilityService.RestClientRunResult;
import com.specdriven.regression.contract.SoapMockCapabilityService;
import com.specdriven.regression.contract.SoapMockCapabilityService.SoapRunResult;
import com.specdriven.regression.contract.WireMockHttpRequestCapabilityService;
import com.specdriven.regression.contract.WireMockHttpRequestCapabilityService.MixedRunResult;
import com.specdriven.regression.contract.WireMockProviderCapabilityService;
import com.specdriven.regression.contract.WireMockProviderCapabilityService.WireMockRunResult;
import com.specdriven.regression.contract.v03.V03RuntimeExecutionService;
import com.specdriven.regression.contract.v03.V03RuntimeExecutionService.V03RuntimeRunResult;
import com.specdriven.regression.producttest.ProductTestHandoffService;
import com.specdriven.regression.producttest.ProductTestHandoffService.InvocationRequirement;
import com.specdriven.regression.provider.jdbc.JdbcDriverDiscovery.DiscoveryResult;
import com.specdriven.regression.summary.SuiteAggregationService;
import com.specdriven.regression.summary.SuiteArtifactFinalizer;
import com.specdriven.regression.summary.SuiteExecutionContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.yaml.snakeyaml.Yaml;

/** Runs selected suite leaves and groups, dispatches supported runtimes and finalizes their artifacts. */
public final class SuiteExecution {

    private final CommonVerifyService commonVerifyService = new CommonVerifyService();
    private final GoldenE2eService goldenE2eService = new GoldenE2eService();
    private final WireMockProviderCapabilityService wireMockProviderCapabilityService =
            new WireMockProviderCapabilityService();
    private final WireMockHttpRequestCapabilityService wireMockHttpRequestCapabilityService =
            new WireMockHttpRequestCapabilityService();
    private final RestClientCapabilityService restClientCapabilityService =
            new RestClientCapabilityService();
    private final NatsProviderCapabilityService natsProviderCapabilityService =
            new NatsProviderCapabilityService();
    private final MessagingClientProviderCapabilityService messagingClientProviderCapabilityService =
            new MessagingClientProviderCapabilityService();
    private final SoapMockCapabilityService soapMockCapabilityService =
            new SoapMockCapabilityService();
    private final GrpcMockCapabilityService grpcMockCapabilityService =
            new GrpcMockCapabilityService();
    private final V03RuntimeExecutionService v03RuntimeExecutionService = new V03RuntimeExecutionService();

    private final SuiteArtifactFinalizer suiteArtifactFinalizer = new SuiteArtifactFinalizer();
    private final SuiteAggregationService suiteAggregationService = new SuiteAggregationService();

    private final SuiteValidation validation;
    private final InvocationRequirement invocationRequirement;

    public SuiteExecution(SuiteValidation validation, InvocationRequirement invocationRequirement) {
        this.validation = validation;
        this.invocationRequirement = invocationRequirement;
    }

    public boolean reviewedRouteBlocked(Path root, String suite, String profile, boolean packageRoute, boolean dryRun) {
        if (!invocationRequirement.required()) return false;
        if (invocationRequirement.expectedSha256() == null
                || !invocationRequirement.expectedSha256().matches("[a-f0-9]{64}")
                || suite == null || profile == null || packageRoute || dryRun) return true;
        try {
            Path manifest = root.resolve(suite).normalize();
            return !"v0.3".equals(readYamlMap(manifest).get("manifest_version"))
                    || validation.isSuiteGroupManifest(manifest)
                    || !ProductTestHandoffService.configured(manifest, profile);
        } catch (RuntimeException error) {
            return true;
        }
    }

    public record DryRun(SuiteValidation.Leaf leaf, DryRunResult compatibility) {}

    public DryRun dryRun(Path manifest, String profile, String selectedCase) {
        SuiteValidation.Leaf leaf = validation.checkLeaf(manifest, profile, selectedCase);
        DryRunResult compatibility = leaf.plan() == null && selectedCase == null
                ? validation.compatibilityDryRun(manifest) : null;
        return new DryRun(leaf, compatibility);
    }

    public SuiteRuntimeResult runLeaf(Path manifest, String suiteRef, String profile, Path outputRoot,
            String selectedCase, Supplier<DiscoveryResult> jdbcDrivers) {
        return dispatchSuiteRuntime(manifest, suiteRef, profile, outputRoot, null, jdbcDrivers, selectedCase);
    }

    public record GroupRun(String status, String suiteId, String profile, int testCount, String batchId,
            String runId, int passed, int failed, int expectedFailureCount, int expectedFailedObservedCount,
            SuiteGroupOutput output, List<ContractFinding> findings) {}

    private SuiteRuntimeResult dispatchSuiteRuntime(
            Path suiteManifest,
            String suiteRef,
            String profile,
            Path outputRoot,
            SuiteExecutionContext parentContext,
            Supplier<DiscoveryResult> jdbcDrivers, String selectedTestCase) {
        SuiteRuntimeResult result = dispatchSuiteRuntimeUnfinalized(
                suiteManifest, suiteRef, profile, outputRoot, parentContext, jdbcDrivers, selectedTestCase);
        if (result.resultJson() == null) {
            return result;
        }
        if (Files.isRegularFile(result.resultJson().getParent().resolve("product-test/reviewed-handoff.json"))) {
            // Reviewed execution already finalized against its private snapshot before closing it.
            return result.withSummaryJson(result.resultJson().getParent().resolve("suite_summary.json"));
        }
        SuiteArtifactFinalizer.FinalizedArtifacts finalized =
                suiteArtifactFinalizer.finalizeLeaf(suiteManifest, result.resultJson());
        return result.withSummaryJson(finalized.summaryJson());
    }

    private SuiteRuntimeResult dispatchSuiteRuntimeUnfinalized(
            Path suiteManifest,
            String suiteRef,
            String profile,
            Path outputRoot,
            SuiteExecutionContext parentContext,
            Supplier<DiscoveryResult> jdbcDrivers, String selectedTestCase) {
        if (parentContext != null && !parentContext.matchesRequestedProfile(profile)) {
            return SuiteRuntimeResult.blocked("", profile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "profile",
                    "profile_mismatch",
                    "",
                    "",
                    profile == null ? "" : profile,
                    "",
                    "Use the suite-group parent profile `" + parentContext.profile()
                            + "` for the child suite or split it into a separate batch.")));
        }
        try {
            if (invocationRequirement.required() || ProductTestHandoffService.configured(suiteManifest, profile)) {
                if (parentContext != null) {
                    return SuiteRuntimeResult.blocked("", profile, List.of(new ContractFinding(suiteManifest.toString(),
                            "product_test", "REVIEWED_HANDOFF_REQUIRED", "", "", profile, "",
                            "Run a reviewed v0.3 leaf separately; reviewed group execution is unsupported.")));
                }
                return SuiteRuntimeResult.fromV03(v03RuntimeExecutionService.runReviewed(suiteManifest, profile,
                        runtimeContext(null, outputRoot, profile), invocationRequirement, selectedTestCase));
            }
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException error) {
            return SuiteRuntimeResult.blocked("", profile, List.of(new ContractFinding(suiteManifest.toString(),
                    "product_test", "REVIEWED_HANDOFF_REQUIRED", "", "", profile, "",
                    "Correct the reviewed leaf/profile handoff: " + error.getMessage())));
        }
        ValidationResult validation = this.validation.validateForExecution(suiteManifest);
        if (!validation.valid()) {
            return SuiteRuntimeResult.blocked(validation.suiteId(), profile, validation.findings());
        }
        List<String> providerTypes = validation.providerTypesUsed();
        if (SuiteValidation.isV03Validation(validation)) {
            return SuiteRuntimeResult.fromV03(v03RuntimeExecutionService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot, profile),
                    validation, jdbcDrivers, selectedTestCase));
        }
        if (selectedTestCase != null) {
            return SuiteRuntimeResult.blocked(validation.suiteId(), profile, List.of(SuiteValidation.unsupportedCaseSelection(suiteManifest)));
        }
        if (supportsWireMockHttpRequestSample(providerTypes)) {
            return SuiteRuntimeResult.fromMixed(wireMockHttpRequestCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot, profile)));
        }
        if (supportsSoapMockSample(providerTypes)) {
            return SuiteRuntimeResult.fromSoap(soapMockCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot, profile)));
        }
        if (supportsGrpcMockSample(providerTypes)) {
            return SuiteRuntimeResult.fromGrpc(grpcMockCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot, profile)));
        }
        if (providerTypes.equals(List.of("rest_client"))) {
            return SuiteRuntimeResult.fromRest(restClientCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot, profile)));
        }
        if (providerTypes.equals(List.of("wiremock_http_mock"))) {
            return SuiteRuntimeResult.fromWireMock(wireMockProviderCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot, profile)));
        }
        if (supportsMessagingClientSample(providerTypes, suiteRef)) {
            return SuiteRuntimeResult.fromMessaging(messagingClientProviderCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot, profile)));
        }
        if (providerTypes.equals(List.of("nats")) || suiteRef.contains("provider_capability/nats")) {
            return SuiteRuntimeResult.fromNats(natsProviderCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot, profile)));
        }
        if (providerTypes.equals(List.of("common_verify"))
                || suiteRef.contains("provider_capability/common_verify")
                || suiteRef.contains("provider_capability/polling")) {
            return SuiteRuntimeResult.fromCommonVerify(commonVerifyService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot, profile)));
        }
        if (!providerTypes.equals(List.of("sample_fake_provider"))) {
            return SuiteRuntimeResult.blocked(validation.suiteId(), profile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "provider_types_used",
                    "unsupported_suite_runtime",
                    "",
                    String.join(",", providerTypes),
                    profile,
                    "",
                    "Use a provider type supported by SuiteRuntimeDispatcher or add an explicit runtime dispatcher path.")));
        }
        return SuiteRuntimeResult.fromGolden(goldenE2eService.run(
                suiteManifest,
                profile,
                runtimeContext(parentContext, outputRoot, profile)));
    }

    private SuiteExecutionContext runtimeContext(
            SuiteExecutionContext parentContext, Path runtimeOutputRoot, String profile) {
        if (parentContext != null) {
            return parentContext.withOutputRoot(runtimeOutputRoot);
        }
        String runtime = runtimeOutputRoot.getFileName().toString();
        if ("golden-e2e".equals(runtime)) {
            return SuiteExecutionContext.standaloneWithBatchId(
                    profile, runtimeOutputRoot, "BATCH-GOLDEN-E2E-001");
        }
        String label = switch (runtime) {
            case "v03-runtime" -> "V03";
            case "wiremock_http_request" -> "WIREMOCK-HTTP";
            case "contract_baseline" -> "CONTRACT";
            case "soap_mock" -> "SOAP";
            case "grpc_mock" -> "GRPC";
            case "rest_client" -> "REST";
            case "wiremock" -> "WIREMOCK";
            case "jdbc" -> "JDBC";
            case "messaging-client" -> "MESSAGING";
            case "nats" -> "NATS";
            case "common_verify" -> "COMMON";
            default -> "STANDALONE";
        };
        return SuiteExecutionContext.standalone(profile, runtimeOutputRoot, label);
    }

    public StatusCounts suiteRuntimeStatusCounts(SuiteRuntimeResult result) {
        if (result.resultJson() != null && Files.isRegularFile(result.resultJson())) {
            try {
                Map<String, Object> resultDocument = readYamlMap(result.resultJson());
                Object tests = resultDocument.get("test_results");
                if (tests instanceof List<?> testResults && !testResults.isEmpty()) {
                    int passed = 0;
                    int failed = 0;
                    for (Object value : testResults) {
                        String status = value instanceof Map<?, ?> map ? stringValue(map.get("status")) : "";
                        if ("passed".equals(status)) {
                            passed++;
                        } else {
                            failed++;
                        }
                    }
                    return new StatusCounts(passed, failed);
                }
            } catch (RuntimeException ignored) {
                // CLI summary should remain printable even if result validation reports details later.
            }
        }
        return result.passed()
                ? new StatusCounts(result.testCount(), 0)
                : new StatusCounts(0, result.testCount());
    }

    public GroupRun runGroup(
            Path suiteManifest,
            String requestedProfile,
            Path outputRoot,
            Supplier<DiscoveryResult> jdbcDrivers) {
        SuiteGroupValidation validation = this.validation.validateSuiteGroupArtifacts(suiteManifest, true);
        SuiteGroupDefinition definition = validation.definition();
        List<ContractFinding> findings = new ArrayList<>(validation.findings());
        if (!definition.profile().isBlank() && !definition.profile().equals(requestedProfile)) {
            findings.add(finding(
                    suiteManifest,
                    "profile",
                    "profile_mismatch",
                    "Run the suite group with --profile " + definition.profile() + " or update the suite group profile."));
        }
        if (!validation.valid() || !findings.isEmpty()) {
            return new GroupRun("blocked", definition.suiteId(), requestedProfile, definition.children().size(),
                    null, null, 0, 0, 0, 0, null, List.copyOf(findings));
        }

        long startedAt = System.currentTimeMillis();
        String batchId = "BATCH-" + Long.toString(startedAt, 36).toUpperCase(Locale.ROOT);
        String runId = "RUN-" + UUID.randomUUID();
        Path runDir = outputRoot
                .resolve(safeFileName(definition.suiteId()))
                .resolve(batchId)
                .resolve(runId)
                .normalize();
        Path childOutputRoot = runDir.resolve("children");
        SuiteExecutionContext parentContext = new SuiteExecutionContext(
                batchId,
                requestedProfile,
                java.time.Instant.ofEpochMilli(startedAt),
                childOutputRoot);
        List<SuiteGroupChildResult> childResults = new ArrayList<>();
        for (SuiteGroupChild child : definition.children()) {
            childResults.add(runSuiteGroupChild(child, childOutputRoot, parentContext, jdbcDrivers));
        }
        for (SuiteGroupChildResult childResult : childResults) {
            findings.addAll(childResult.findings());
        }

        int passedCount = (int) childResults.stream().filter(SuiteGroupChildResult::passed).count();
        int failedCount = childResults.size() - passedCount;
        int expectedFailureCount = (int) childResults.stream()
                .filter(result -> "failed".equals(result.expectedStatus()))
                .count();
        int expectedFailedObservedCount = (int) childResults.stream()
                .filter(result -> "expected_failed_observed".equals(result.statusTaxonomy()))
                .count();
        String status = failedCount == 0 ? "passed" : "failed";
        boolean canonicalV03 = "v0.3".equals(stringValue(readYamlMap(suiteManifest).get("manifest_version")));
        SuiteGroupOutput compatibilityOutput = writeSuiteGroupOutput(
                definition,
                requestedProfile,
                batchId,
                runId,
                status,
                startedAt,
                System.currentTimeMillis(),
                runDir,
                childResults,
                expectedFailureCount,
                expectedFailedObservedCount,
                canonicalV03);
        SuiteGroupOutput output = compatibilityOutput;
        if (canonicalV03) {
            SuiteAggregationService.AggregatedArtifacts aggregated = suiteAggregationService.aggregate(
                    suiteManifest,
                    runDir,
                    definition.suiteId(),
                    batchId,
                    runId,
                    requestedProfile,
                    java.time.Instant.ofEpochMilli(startedAt),
                    java.time.Instant.now(),
                    childResults.stream().map(child -> new SuiteAggregationService.ChildArtifact(
                            child.id(), child.ref(), child.resultJson(), child.summaryJson())).toList());
            status = aggregated.status();
            output = new SuiteGroupOutput(
                    aggregated.resultJson(), aggregated.summaryJson(), aggregated.summaryYaml(),
                    compatibilityOutput.allureResultsDir());
        }

        return new GroupRun(status, definition.suiteId(), requestedProfile, childResults.size(), batchId,
                runId, passedCount, failedCount, expectedFailureCount, expectedFailedObservedCount, output,
                List.copyOf(findings));
    }

    private SuiteGroupChildResult runSuiteGroupChild(
            SuiteGroupChild child,
            Path childOutputRoot,
            SuiteExecutionContext parentContext,
            Supplier<DiscoveryResult> jdbcDrivers) {
        ValidationResult validation = this.validation.validateForExecution(child.suiteManifest());
        if (!validation.valid()) {
            return SuiteGroupChildResult.fromBlocked(child, validation.suiteId(), validation.findings());
        }
        SuiteRuntimeResult result = dispatchSuiteRuntime(
                child.suiteManifest(),
                child.ref(),
                child.profile(),
                childOutputRoot,
                parentContext.forChildProfile(child.profile()), jdbcDrivers, null);
        return SuiteGroupChildResult.fromSuiteRuntime(child, result);
    }

    private SuiteGroupOutput writeSuiteGroupOutput(
            SuiteGroupDefinition definition,
            String profile,
            String batchId,
            String runId,
            String status,
            long startedAt,
            long stoppedAt,
            Path runDir,
            List<SuiteGroupChildResult> childResults,
            int expectedFailureCount,
            int expectedFailedObservedCount,
            boolean canonicalV03) {
        try {
            Files.createDirectories(runDir);
            Path allureResultsDir = runDir.resolve("allure-results");
            Files.createDirectories(allureResultsDir);
            List<String> allureChildren = new ArrayList<>();
            for (SuiteGroupChildResult child : childResults) {
                allureChildren.add(writeAllureResult(definition, profile, runId, startedAt, stoppedAt, allureResultsDir, child));
            }
            writeAllureContainer(definition, startedAt, stoppedAt, allureResultsDir, allureChildren);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("suite_id", definition.suiteId());
            summary.put("profile", profile);
            summary.put("batch_id", batchId);
            summary.put("run_id", runId);
            summary.put("status", status);
            summary.put("test_count", childResults.size());
            summary.put("passed_count", childResults.stream().filter(SuiteGroupChildResult::passed).count());
            summary.put("failed_count", childResults.stream().filter(result -> !result.passed()).count());
            summary.put("expected_failure_count", expectedFailureCount);
            summary.put("expected_failed_observed_count", expectedFailedObservedCount);
            summary.put("expected_failed_missing_count", childResults.stream()
                    .filter(result -> "expected_failed_missing".equals(result.statusTaxonomy()))
                    .count());
            summary.put("unsupported_count", childResults.stream()
                    .filter(result -> "unsupported".equals(result.statusTaxonomy()))
                    .count());
            summary.put("blocked_count", childResults.stream()
                    .filter(result -> "blocked".equals(result.statusTaxonomy()))
                    .count());
            summary.put("allure_results_dir", allureResultsDir.toString());
            summary.put("children", childResults.stream().map(SuiteGroupChildResult::toSummary).toList());

            Path summaryJson = runDir.resolve(canonicalV03
                    ? "suite_summary.compatibility.json" : "suite_summary.json");
            Path summaryYaml = runDir.resolve(canonicalV03
                    ? "suite_summary.compatibility.yaml" : "suite_summary.yaml");
            Files.writeString(summaryJson, toJson(summary));
            Files.writeString(summaryYaml, new Yaml().dump(summary));
            return new SuiteGroupOutput(null, summaryJson, summaryYaml, allureResultsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write suite group output: " + runDir, e);
        }
    }

    private String writeAllureResult(
            SuiteGroupDefinition definition,
            String profile,
            String runId,
            long startedAt,
            long stoppedAt,
            Path allureResultsDir,
            SuiteGroupChildResult child) throws IOException {
        String uuid = UUID.nameUUIDFromBytes(
                (runId + ":" + child.id()).getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uuid", uuid);
        result.put("historyId", UUID.nameUUIDFromBytes(child.id().getBytes(StandardCharsets.UTF_8)).toString());
        result.put("testCaseId", child.id());
        result.put("fullName", definition.suiteId() + "." + child.id());
        result.put("name", child.id());
        result.put("status", child.passed() ? "passed" : "failed");
        result.put("start", startedAt);
        result.put("stop", stoppedAt);
        result.put("labels", List.of(
                allureLabel("suite", definition.suiteId()),
                allureLabel("framework", "spec-driven-auto-regression"),
                allureLabel("profile", profile),
                allureLabel("child_suite_id", child.childSuiteId()),
                allureLabel("expected_status", child.expectedStatus()),
                allureLabel("observed_status", child.observedStatus()),
                allureLabel("provider_types", String.join(",", child.providerTypes()))));
        result.put("steps", List.of(allureStep("execute child suite", child.passed() ? "passed" : "failed", startedAt, stoppedAt)));
        Files.writeString(allureResultsDir.resolve(uuid + "-result.json"), toJson(result));
        return uuid;
    }

    private void writeAllureContainer(
            SuiteGroupDefinition definition,
            long startedAt,
            long stoppedAt,
            Path allureResultsDir,
            List<String> children) throws IOException {
        String uuid = UUID.nameUUIDFromBytes(
                (definition.suiteId() + ":container:" + startedAt).getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("uuid", uuid);
        container.put("name", definition.suiteId());
        container.put("children", children);
        container.put("start", startedAt);
        container.put("stop", stoppedAt);
        container.put("befores", List.of());
        container.put("afters", List.of());
        Files.writeString(allureResultsDir.resolve(uuid + "-container.json"), toJson(container));
    }

    private Map<String, Object> allureLabel(String name, String value) {
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("name", name);
        label.put("value", value);
        return label;
    }

    private Map<String, Object> allureStep(String name, String status, long start, long stop) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("status", status);
        step.put("start", start);
        step.put("stop", stop);
        return step;
    }

    private String safeFileName(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.isBlank() ? "suite_group" : safe;
    }

    private boolean supportsWireMockHttpRequestSample(List<String> providerTypes) {
        return providerTypes.size() == 2
                && providerTypes.contains("wiremock_http_mock")
                && providerTypes.contains("rest_client");
    }

    private boolean supportsSoapMockSample(List<String> providerTypes) {
        return providerTypes.size() == 2
                && providerTypes.contains("soap_mock")
                && providerTypes.contains("rest_client");
    }

    private boolean supportsGrpcMockSample(List<String> providerTypes) {
        return providerTypes.size() == 2
                && providerTypes.contains("grpc_mock")
                && providerTypes.contains("grpc_client");
    }

    private boolean supportsMessagingClientSample(List<String> providerTypes, String suite) {
        return (!providerTypes.isEmpty() && providerTypes.stream().allMatch(List.of("kafka", "ibm_mq")::contains))
                || suite.contains("provider_capability/kafka")
                || suite.contains("provider_capability/ibm_mq");
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + escapeJson(text) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(entry.getKey())).append(": ").append(toJson(entry.getValue()));
            }
            return json.append("}").toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(item));
            }
            return json.append("]").toString();
        }
        return toJson(String.valueOf(value));
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    public record SuiteGroupOutput(Path resultJson, Path summaryJson, Path summaryYaml, Path allureResultsDir) {
    }

    public record StatusCounts(int passedCount, int failedCount) {
    }

    public record SuiteRuntimeResult(
            boolean passed,
            String status,
            String suiteId,
            String batchId,
            String runId,
            String testCaseId,
            int testCount,
            String profile,
            boolean providerRuntimeExecuted,
            List<String> providerIds,
            List<String> providerTypes,
            Path resultJson,
            Path summaryJson,
            Path evidenceDir,
            String evidenceClassification,
            List<String> outputLines,
            List<ContractFinding> findings) {

        static SuiteRuntimeResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new SuiteRuntimeResult(
                    false,
                    "blocked",
                    suiteId,
                    "",
                    "",
                    "",
                    0,
                    profile,
                    false,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    "framework_provider_capability_only",
                    List.of(),
                    List.copyOf(findings));
        }

        static SuiteRuntimeResult fromMixed(MixedRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    result.providerIds(),
                    result.providerTypes(),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_provider_capability_only",
                    List.of(
                            "provider_types: " + String.join(",", result.providerTypes()),
                            "provider_ids: " + String.join(",", result.providerIds())),
                    result.findings());
        }

        static SuiteRuntimeResult fromSoap(SoapRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    result.providerIds(),
                    result.providerTypes(),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_provider_capability_only",
                    List.of(
                            "provider_types: " + String.join(",", result.providerTypes()),
                            "provider_ids: " + String.join(",", result.providerIds())),
                    result.findings());
        }

        static SuiteRuntimeResult fromGrpc(GrpcRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    result.providerIds(),
                    result.providerTypes(),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_provider_capability_only",
                    List.of(
                            "provider_types: " + String.join(",", result.providerTypes()),
                            "provider_ids: " + String.join(",", result.providerIds())),
                    result.findings());
        }

        static SuiteRuntimeResult fromRest(RestClientRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    blankListFiltered(result.providerId()),
                    blankListFiltered(result.providerType()),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "product_release_evidence_candidate",
                    List.of(
                            "provider_type: " + result.providerType(),
                            "provider_id: " + result.providerId()),
                    result.findings());
        }

        static SuiteRuntimeResult fromWireMock(WireMockRunResult result) {
            List<String> outputLines = new ArrayList<>();
            outputLines.add("provider_type: " + result.providerType());
            outputLines.add("provider_id: " + result.providerId());
            if (!result.baseUrl().isBlank()) {
                outputLines.add("base_url: " + result.baseUrl());
            }
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    blankListFiltered(result.providerId()),
                    blankListFiltered(result.providerType()),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_provider_capability_only",
                    outputLines,
                    result.findings());
        }

        static SuiteRuntimeResult fromMessaging(MessagingClientRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    blankListFiltered(result.providerId()),
                    blankListFiltered(result.providerType()),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_provider_capability_only",
                    result.providerOutputLines(),
                    result.findings());
        }

        static SuiteRuntimeResult fromNats(NatsRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    blankListFiltered(result.providerId()),
                    blankListFiltered(result.providerType()),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    result.evidenceClassification(),
                    List.of(
                            "provider_type: " + result.providerType(),
                            "provider_id: " + result.providerId(),
                            "runtime_mode: " + result.runtimeMode(),
                            "subject: " + result.subject()),
                    result.findings());
        }

        static SuiteRuntimeResult fromCommonVerify(CommonVerifyRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    blankListFiltered(result.providerId()),
                    blankListFiltered(result.providerType()),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_provider_capability_only",
                    List.of(
                            "provider_type: " + result.providerType(),
                            "provider_id: " + result.providerId()),
                    result.findings());
        }

        static SuiteRuntimeResult fromV03(V03RuntimeRunResult result) {
            List<String> outputLines = new ArrayList<>();
            result.providerTypes().forEach(providerType -> outputLines.add("provider_type: " + providerType));
            if (!result.targets().isEmpty()) {
                outputLines.add("targets: " + String.join(",", result.targets()));
            }
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    result.targets(),
                    result.providerTypes(),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    result.evidenceClassification(),
                    outputLines,
                    result.findings());
        }

        static SuiteRuntimeResult fromGolden(GoldenRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.fakeProviderExecuted(),
                    result.fakeProviderExecuted() ? List.of("sample-fake-runtime") : List.of(),
                    List.of("sample_fake_provider"),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_verification_only",
                    List.of("fake_provider_executed: " + result.fakeProviderExecuted()),
                    result.findings());
        }

        SuiteRuntimeResult withSummaryJson(Path summaryJson) {
            return new SuiteRuntimeResult(
                    passed, status, suiteId, batchId, runId, testCaseId, testCount, profile,
                    providerRuntimeExecuted, providerIds, providerTypes, resultJson, summaryJson,
                    evidenceDir, evidenceClassification, outputLines, findings);
        }

        private static List<String> blankListFiltered(String value) {
            return value == null || value.isBlank() ? List.of() : List.of(value);
        }
    }

    private record SuiteGroupChildResult(
            String id,
            String ref,
            String childSuiteId,
            String profile,
            String expectedStatus,
            String observedStatus,
            boolean passed,
            String batchId,
            String runId,
            List<String> providerIds,
            List<String> providerTypes,
            Path resultJson,
            Path summaryJson,
            Path evidenceDir,
            List<ContractFinding> findings) {

        static SuiteGroupChildResult fromSuiteRuntime(SuiteGroupChild child, SuiteRuntimeResult result) {
            return new SuiteGroupChildResult(
                    child.id(),
                    child.ref(),
                    result.suiteId(),
                    result.profile(),
                    child.expectedStatus(),
                    result.status(),
                    child.expectedStatus().equals(result.status()),
                    result.batchId(),
                    result.runId(),
                    result.providerIds(),
                    result.providerTypes(),
                    result.resultJson(),
                    result.summaryJson(),
                    result.evidenceDir(),
                    result.findings());
        }

        static SuiteGroupChildResult fromBlocked(
                SuiteGroupChild child,
                String childSuiteId,
                List<ContractFinding> findings) {
            return new SuiteGroupChildResult(
                    child.id(),
                    child.ref(),
                    childSuiteId,
                    child.profile(),
                    child.expectedStatus(),
                    "blocked",
                    false,
                    "",
                    "",
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    List.copyOf(findings));
        }

        Map<String, Object> toSummary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", id);
            summary.put("ref", ref);
            summary.put("child_suite_id", childSuiteId);
            summary.put("profile", profile);
            summary.put("expected_status", expectedStatus);
            summary.put("observed_status", observedStatus);
            summary.put("status_taxonomy", statusTaxonomy());
            summary.put("status", passed ? "passed" : "failed");
            summary.put("batch_id", batchId);
            summary.put("run_id", runId);
            summary.put("provider_ids", providerIds);
            summary.put("provider_types", providerTypes);
            summary.put("result_json", resultJson == null ? "" : resultJson.toString());
            summary.put("evidence_dir", evidenceDir == null ? "" : evidenceDir.toString());
            summary.put("finding_count", findings.size());
            return summary;
        }

        String statusTaxonomy() {
            if (findings.stream().anyMatch(finding ->
                    finding.reason().contains("unsupported") || finding.failureCode().contains("UNSUPPORTED"))) {
                return "unsupported";
            }
            if ("blocked".equals(observedStatus)) {
                return "blocked";
            }
            if ("failed".equals(expectedStatus) && "failed".equals(observedStatus)) {
                return "expected_failed_observed";
            }
            if ("failed".equals(expectedStatus)) {
                return "expected_failed_missing";
            }
            if ("passed".equals(observedStatus)) {
                return "passed";
            }
            return "failed";
        }
    }

    public Map<String, Object> failureDetails(SuiteRuntimeResult result) {
        if (result.passed() || result.resultJson() == null || !Files.isRegularFile(result.resultJson())) {
            return Map.of();
        }
        try {
            return mapValue(readYamlMap(result.resultJson()).get("failure"));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }
}

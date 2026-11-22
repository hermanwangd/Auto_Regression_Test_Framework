package com.specdriven.regression.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class SampleLayoutContractTest {

    private static final Path SAMPLES_ROOT = Path.of("samples");
    private static final String RUNTIME_MODE_SAMPLE_SCOPE = "usage_kit_runtime_mode_sample";
    private static final List<CanonicalSample> CANONICAL_V03_SAMPLES = List.of(
            new CanonicalSample("samples/00-getting-started/golden_e2e", "GOLDEN-E2E-v0.3", true),
            new CanonicalSample("samples/10-contract-baseline/mixed_wiremock_jdbc_nats", "MIXED-CONTRACT-BASELINE-v0.3", true),
            new CanonicalSample("samples/20-provider-capability-p0", "PROVIDER-CAPABILITY-P0-v0.3", true),
            new CanonicalSample(
                    "samples/20-provider-capability-p0/http/rest_client_with_wiremock",
                    "HTTP-MOCK-REST-CLIENT-v0.3",
                    true),
            new CanonicalSample("samples/20-provider-capability-p0/data/jdbc", "JDBC-v0.3", true),
            new CanonicalSample("samples/20-provider-capability-p0/messaging/nats", "NATS-v0.3", true),
            new CanonicalSample("samples/20-provider-capability-p0/messaging/kafka", "KAFKA-v0.3", true),
            new CanonicalSample("samples/20-provider-capability-p0/messaging/ibm_mq", "IBM-MQ-v0.3", true),
            new CanonicalSample(
                    "samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed",
                    "KAFKA-IBM-MQ-MIXED-v0.3",
                    true),
            new CanonicalSample("samples/20-provider-capability-p0/rpc/soap_mock", "SOAP-MOCK-REST-CLIENT-v0.3", true),
            new CanonicalSample("samples/20-provider-capability-p0/rpc/grpc_mock", "GRPC-MOCK-GRPC-CLIENT-v0.3", true),
            new CanonicalSample(
                    "samples/20-provider-capability-p0/verification/common_verify",
                    "COMMON-VERIFY-v0.3",
                    true),
            new CanonicalSample(
                    "samples/20-provider-capability-p0/verification/artifact_compare",
                    "ARTIFACT-COMPARE-v0.3",
                    true),
            new CanonicalSample(
                    "samples/20-provider-capability-p0/verification/polling_observer",
                    "POLLING-OBSERVER-v0.3",
                    true),
            new CanonicalSample(
                    "samples/20-provider-capability-p0/verification/multi_test_shared_env",
                    "MULTI-TEST-v0.3",
                    true),
            new CanonicalSample(
                    "samples/30-cross-provider-groups/mock_server_cross_verify",
                    "MOCK-SERVER-CROSS-VERIFY-v0.3",
                    true),
            new CanonicalSample(
                    "samples/30-cross-provider-groups/mixed_provider_e2e",
                    "MIXED-PROVIDER-E2E-v0.3",
                    true),
            new CanonicalSample("samples/80-negative", "NEGATIVE-v0.3", true));

    @Test
    void canonicalSampleCategoryDirectoriesExist() {
        assertThat(List.of(
                        "samples/00-getting-started/golden_e2e",
                        "samples/10-contract-baseline/mixed_wiremock_jdbc_nats",
                        "samples/20-provider-capability-p0",
                        "samples/30-cross-provider-groups/mock_server_cross_verify",
                        "samples/30-cross-provider-groups/mixed_provider_e2e",
                        "samples/40-evidence-reporting/evidence_hardening",
                        "samples/80-negative",
                        "samples/90-compatibility/dummy_rest"))
                .allSatisfy(path -> assertThat(Files.isDirectory(Path.of(path)))
                        .as(path + " should exist in the canonical sample layout")
                        .isTrue());
    }

    @Test
    void canonicalV03SamplesExistWithExpectedSuiteIds() {
        assertThat(CANONICAL_V03_SAMPLES)
                .allSatisfy(sample -> {
                    Path root = Path.of(sample.rootDir());
                    Path manifest = root.resolve("suite_manifest.yaml");
                    assertThat(Files.isDirectory(root))
                            .as(sample.rootDir() + " should exist as a canonical v0.3 sample root")
                            .isTrue();
                    assertThat(Files.isRegularFile(manifest))
                            .as(manifest + " should be the canonical v0.3 entrypoint")
                            .isTrue();

                    Map<String, Object> document = yamlMap(manifest);
                    assertThat(document.get("manifest_version"))
                            .as(manifest + " should use v0.3 manifest format")
                            .isEqualTo("v0.3");
                    assertThat(document.get("suite_id"))
                            .as(manifest + " should keep the registry suite_id")
                            .isEqualTo(sample.suiteId());
                });
    }

    @Test
    void stagingAndRootLegacyAliasesAreNotPublicSampleRoots() {
        assertThat(List.of(
                        "samples/v0_3_dsl",
                        "samples/golden_e2e",
                        "samples/contract_baseline",
                        "samples/provider_capability",
                        "samples/evidence_hardening"))
                .allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                        .as(path + " should not be a public v0.3 sample root")
                        .isFalse());
    }

    @Test
    void activeV03SampleRootsDoNotContainLegacyProviderArtifactDirectories() throws Exception {
        List<Path> activeRoots = List.of(
                Path.of("samples/00-getting-started"),
                Path.of("samples/10-contract-baseline"),
                Path.of("samples/20-provider-capability-p0"),
                Path.of("samples/30-cross-provider-groups"),
                Path.of("samples/80-negative"));

        try (Stream<Path> paths = activeRoots.stream().flatMap(SampleLayoutContractTest::walkUnchecked)) {
            assertThat(paths.filter(Files::isDirectory)
                            .filter(path -> List.of("provider_instances", "provider_contracts")
                                    .contains(path.getFileName().toString()))
                            .toList())
                    .as("active v0.3 samples should use suite targets plus provider contracts, not v0.2 artifact dirs")
                    .isEmpty();
        }
    }

    @Test
    void suiteManifestIsEitherLeafSuiteOrSuiteGroup() throws Exception {
        try (Stream<Path> manifests = Files.walk(SAMPLES_ROOT)) {
            assertThat(manifests.filter(path -> path.getFileName().toString().equals("suite_manifest.yaml")))
                    .allSatisfy(this::assertManifestShape);
        }
    }

    @Test
    void suiteGroupChildRefsStayInsideGroupDirectory() throws Exception {
        try (Stream<Path> manifests = Files.walk(SAMPLES_ROOT)) {
            assertThat(manifests.filter(path -> path.getFileName().toString().equals("suite_manifest.yaml")))
                    .allSatisfy(this::assertChildRefsDoNotEscape);
        }
    }

    @Test
    void activeV03ManifestsAndTestCasesUseV03Versions() throws Exception {
        try (Stream<Path> manifests = Files.walk(SAMPLES_ROOT)) {
            assertThat(manifests.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().equals("suite_manifest.yaml"))
                            .filter(SampleLayoutContractTest::isActiveV03Path)
                            .toList())
                    .allSatisfy(path -> assertThat(yamlMap(path).get("manifest_version"))
                            .as(path + " should be a v0.3 manifest")
                            .isEqualTo("v0.3"));
        }

        try (Stream<Path> testCases = Files.walk(SAMPLES_ROOT)) {
            assertThat(testCases.filter(Files::isRegularFile)
                            .filter(SampleLayoutContractTest::isYaml)
                            .filter(path -> path.toString().contains("/test_cases/"))
                            .filter(SampleLayoutContractTest::isActiveV03Path)
                            .toList())
                    .allSatisfy(path -> assertThat(yamlMap(path).get("dsl_version"))
                            .as(path + " should be a v0.3 test case")
                            .isEqualTo("v0.3"));
        }
    }

    @Test
    void runtimeModeSampleProviderInstancesAreNotExecutableTargets() throws Exception {
        Set<String> runtimeModeSampleProviderIds = runtimeModeSampleProviderIds();
        Set<String> executableTargetProviderIds = executableTargetProviderIds();

        assertThat(runtimeModeSampleProviderIds)
                .as("runtime-mode sample provider instances should be present")
                .isNotEmpty();
        assertThat(executableTargetProviderIds).doesNotContainAnyElementsOf(runtimeModeSampleProviderIds);
    }

    private void assertManifestShape(Path manifest) {
        Map<String, Object> document = yamlMap(manifest);
        boolean hasTests = document.containsKey("tests");
        boolean hasChildSuites = document.containsKey("child_suites");

        assertThat(hasTests || hasChildSuites)
                .as(manifest + " should define tests[] for a leaf suite or child_suites[] for a suite group")
                .isTrue();
        assertThat(hasTests && hasChildSuites)
                .as(manifest + " must not mix leaf suite tests[] with suite group child_suites[]")
                .isFalse();
    }

    private void assertChildRefsDoNotEscape(Path manifest) {
        Map<String, Object> document = yamlMap(manifest);
        Set<String> childIds = new LinkedHashSet<>();
        List<String> duplicateChildIds = new ArrayList<>();
        for (Map<String, Object> child : objectMaps(document.get("child_suites"))) {
            String childId = String.valueOf(child.get("id"));
            if (!childId.isBlank() && !childIds.add(childId)) {
                duplicateChildIds.add(childId);
            }
            String ref = String.valueOf(child.get("ref"));
            Path resolved = manifest.getParent().resolve(ref).normalize();
            assertThat(ref)
                    .as(manifest + " child ref should be relative and stay inside the suite group")
                    .doesNotStartWith("../");
            assertThat(resolved)
                    .as(manifest + " child ref should not escape the suite group directory: " + ref)
                    .startsWith(manifest.getParent().normalize());
            assertThat(Files.isRegularFile(resolved))
                    .as(manifest + " child ref should point to an existing manifest: " + ref)
                    .isTrue();
        }
        assertThat(duplicateChildIds)
                .as(manifest + " child suite ids should be unique")
                .isEmpty();
    }

    private Set<String> runtimeModeSampleProviderIds() throws IOException {
        Set<String> providerIds = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(SAMPLES_ROOT)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".yaml"))
                    .filter(path -> path.toString().contains("/provider_instances/"))
                    .filter(this::isRuntimeModeSample)
                    .map(path -> String.valueOf(yamlMap(path).get("provider_id")))
                    .forEach(providerIds::add);
        }
        return providerIds;
    }

    private Set<String> executableTargetProviderIds() throws IOException {
        Set<String> providerIds = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(SAMPLES_ROOT)) {
            files.filter(Files::isRegularFile)
                    .filter(SampleLayoutContractTest::isYaml)
                    .filter(path -> path.toString().contains("/test_cases/")
                            || path.getFileName().toString().contains("test_case"))
                    .forEach(path -> providerIds.addAll(targetProviderIds(path)));
        }
        return providerIds;
    }

    private boolean isRuntimeModeSample(Path path) {
        Map<String, Object> document = yamlMap(path);
        Map<String, Object> labels = objectMap(document.get("labels"));
        return RUNTIME_MODE_SAMPLE_SCOPE.equals(labels.get("sample_scope"));
    }

    private Set<String> targetProviderIds(Path testCase) {
        Map<String, Object> document = yamlMap(testCase);
        Set<String> providerIds = new LinkedHashSet<>();
        for (Map<String, Object> target : objectMap(document.get("targets")).values().stream()
                .map(SampleLayoutContractTest::objectMap)
                .toList()) {
            Object providerId = target.get("provider_id");
            if (providerId != null) {
                providerIds.add(String.valueOf(providerId));
            }
        }
        return providerIds;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yamlMap(Path path) {
        try {
            Object loaded = new Yaml().load(Files.readString(path));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read YAML file " + path, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    private static Stream<Path> walkUnchecked(Path root) {
        try {
            return Files.walk(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to walk sample root " + root, exception);
        }
    }

    private static boolean isYaml(Path path) {
        String filename = path.getFileName().toString();
        return filename.endsWith(".yaml") || filename.endsWith(".yml");
    }

    private static boolean isActiveV03Path(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.startsWith("samples/00-getting-started/")
                || normalized.startsWith("samples/10-contract-baseline/")
                || normalized.startsWith("samples/20-provider-capability-p0/")
                || normalized.startsWith("samples/30-cross-provider-groups/")
                || normalized.startsWith("samples/80-negative/");
    }

    private record CanonicalSample(String rootDir, String suiteId, boolean shipsInUsageKit) {}
}

package com.specdriven.regression.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void canonicalSampleCategoryDirectoriesExist() {
        assertThat(List.of(
                        "samples/00-getting-started/golden_e2e",
                        "samples/10-contract-baseline/mixed_wiremock_jdbc_nats",
                        "samples/20-provider-capability-p0",
                        "samples/30-cross-provider-groups/mock_server_cross_verify",
                        "samples/40-evidence-reporting/evidence_hardening",
                        "samples/90-compatibility/dummy_rest"))
                .allSatisfy(path -> assertThat(Files.isDirectory(Path.of(path)))
                        .as(path + " should exist in the canonical sample layout")
                        .isTrue());
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
        for (Map<String, Object> child : objectMaps(document.get("child_suites"))) {
            String ref = String.valueOf(child.get("ref"));
            Path resolved = manifest.getParent().resolve(ref).normalize();
            assertThat(ref)
                    .as(manifest + " child ref should be relative and stay inside the suite group")
                    .doesNotStartWith("../");
            assertThat(resolved)
                    .as(manifest + " child ref should not escape the suite group directory: " + ref)
                    .startsWith(manifest.getParent().normalize());
        }
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
                    .filter(path -> path.toString().endsWith(".yaml"))
                    .filter(path -> path.getFileName().toString().contains("test_case"))
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
}

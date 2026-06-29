package com.specdriven.regression.parameter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParameterSetResolverTest {

    @TempDir
    Path tempDir;

    private final ParameterSetResolver resolver = new ParameterSetResolver();

    @Test
    void returnsNotParameterizedForMissingParametersAndLegacyStrategy() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/TC-001.yaml");
        Files.createDirectories(testCase.getParent());

        assertThat(resolver.resolve(testCase, Map.of()).parameterized()).isFalse();
        assertThat(resolver.resolve(testCase, Map.of(
                "parameters", Map.of(
                        "strategy", "explicit_cases",
                        "cases", List.of()))).parameterized()).isFalse();
    }

    @Test
    void resolvesReviewedParameterSetAndReplacesNamespacedReferences() throws Exception {
        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Path testCase = packageRoot.resolve("tests/approved/TC-001.yaml");
        Path parameterSet = packageRoot.resolve("parameter-sets/orders.yaml");
        Files.createDirectories(testCase.getParent());
        Files.createDirectories(parameterSet.getParent());
        Files.writeString(parameterSet, """
                cases:
                  - case_id: baseline
                    values:
                      file_ref: fixtures/orders-baseline.csv
                      count: 2
                """);

        ParameterSetResolution resolution = resolver.resolve(testCase, Map.of(
                "parameters", Map.of(
                        "ref", parameterSet.toString(),
                        "bind_as", "orders_case"),
                "setup", Map.of(
                        "fixtures", Map.of(
                                "orders", Map.of("ref", "${param.orders_case.file_ref}")))));
        Object resolved = resolver.resolveReferences(Map.of(
                "items", List.of("${param.orders_case.file_ref}", "${param.orders_case.count}"),
                "kept", "${param.other.file_ref}",
                "missing", "${param.orders_case.missing}",
                "number", 7), "orders_case", resolution.cases().get(0).values());

        assertThat(resolution.parameterized()).isTrue();
        assertThat(resolution.gaps()).isEmpty();
        assertThat(resolution.cases()).containsExactly(new ParameterCase(
                "baseline",
                "orders_case",
                Map.of("file_ref", "fixtures/orders-baseline.csv", "count", 2)));
        assertThat(resolved).isEqualTo(Map.of(
                "items", List.of("fixtures/orders-baseline.csv", "2"),
                "kept", "${param.other.file_ref}",
                "missing", "${param.orders_case.missing}",
                "number", 7));
    }

    @Test
    void reportsMissingReferenceAndBindNamespaceBeforeLoadingParameterSet() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/TC-001.yaml");
        Files.createDirectories(testCase.getParent());

        ParameterSetResolution resolution = resolver.resolve(testCase, Map.of(
                "parameters", Map.of(
                        "ref", "",
                        "bind_as", "")));

        assertThat(resolution.parameterized()).isTrue();
        assertThat(resolution.gaps()).extracting(ParameterSetGap::fieldPath)
                .containsExactly("parameters.ref", "parameters.bind_as");
    }

    @Test
    void reportsUnreadableParameterSetReference() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/TC-001.yaml");
        Files.createDirectories(testCase.getParent());

        ParameterSetResolution resolution = resolver.resolve(testCase, Map.of(
                "parameters", Map.of(
                        "ref", "parameter-sets/missing.yaml",
                        "bind_as", "orders_case")));

        assertThat(resolution.parameterized()).isTrue();
        assertThat(resolution.gaps()).extracting(ParameterSetGap::fieldPath)
                .containsExactly("parameters.ref");
    }

    @Test
    void reportsEmptyMalformedAndIncompleteCases() throws Exception {
        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Path testCase = packageRoot.resolve("tests/approved/TC-001.yaml");
        Path parameterSet = packageRoot.resolve("parameter-sets/orders.yaml");
        Files.createDirectories(testCase.getParent());
        Files.createDirectories(parameterSet.getParent());
        Files.writeString(parameterSet, """
                cases:
                  - bad-case
                  - case_id:
                    values:
                      file_ref: fixtures/orders.csv
                  - case_id: empty_values
                    values: {}
                  - case_id: blank_ref
                    values:
                      file_ref: ""
                """);

        ParameterSetResolution resolution = resolver.resolve(testCase, Map.of(
                "parameters", Map.of(
                        "ref", "parameter-sets/orders.yaml",
                        "bind_as", "orders_case"),
                "setup", Map.of("fixtures", Map.of(
                        "orders", Map.of("ref", "${param.orders_case.file_ref}")))));

        assertThat(resolution.gaps()).extracting(ParameterSetGap::fieldPath)
                .contains(
                        "parameters.ref.cases[0]",
                        "parameters.ref.cases[1].case_id",
                        "parameters.ref.cases[2].values",
                        "parameters.ref.cases[3].values.file_ref");
    }

    @Test
    void reportsEmptyCaseList() throws Exception {
        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Path testCase = packageRoot.resolve("tests/approved/TC-001.yaml");
        Path parameterSet = packageRoot.resolve("parameter-sets/orders.yaml");
        Files.createDirectories(testCase.getParent());
        Files.createDirectories(parameterSet.getParent());
        Files.writeString(parameterSet, "cases: []\n");

        ParameterSetResolution resolution = resolver.resolve(testCase, Map.of(
                "parameters", Map.of(
                        "ref", "parameter-sets/orders.yaml",
                        "bind_as", "orders_case"),
                "setup", Map.of("fixtures", Map.of(
                        "orders", Map.of("ref", "${param.wrong.file_ref}")))));

        assertThat(resolution.gaps()).extracting(ParameterSetGap::fieldPath)
                .containsExactly("parameters.ref.cases");
    }

    @Test
    void reportsNonMappingParameterSetDocument() throws Exception {
        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Path testCase = packageRoot.resolve("tests/approved/TC-001.yaml");
        Path parameterSet = packageRoot.resolve("parameter-sets/orders.yaml");
        Files.createDirectories(testCase.getParent());
        Files.createDirectories(parameterSet.getParent());
        Files.writeString(parameterSet, "[]\n");

        ParameterSetResolution resolution = resolver.resolve(testCase, Map.of(
                "parameters", Map.of(
                        "ref", "parameter-sets/orders.yaml",
                        "bind_as", "orders_case")));

        assertThat(resolution.gaps()).extracting(ParameterSetGap::fieldPath)
                .containsExactly("parameters.ref.cases");
    }

    @Test
    void reportsWrongNamespaceReferences() throws Exception {
        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Path testCase = packageRoot.resolve("tests/approved/TC-001.yaml");
        Path parameterSet = packageRoot.resolve("parameter-sets/orders.yaml");
        Files.createDirectories(testCase.getParent());
        Files.createDirectories(parameterSet.getParent());
        Files.writeString(parameterSet, """
                cases:
                  - case_id: baseline
                    values:
                      file_ref: fixtures/orders.csv
                """);

        ParameterSetResolution resolution = resolver.resolve(testCase, Map.of(
                "parameters", Map.of(
                        "ref", "parameter-sets/orders.yaml",
                        "bind_as", "orders_case"),
                "setup", Map.of("fixtures", Map.of(
                        "orders", Map.of("ref", "${param.wrong.file_ref}")))));

        assertThat(resolution.gaps()).extracting(ParameterSetGap::fieldPath)
                .containsExactly("parameters.bind_as");
    }
}

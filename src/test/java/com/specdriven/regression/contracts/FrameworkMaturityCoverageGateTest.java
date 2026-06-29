package com.specdriven.regression.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FrameworkMaturityCoverageGateTest {

    private static final Pattern AC_ID = Pattern.compile("\\bAC-\\d{3}\\b");
    private static final Pattern FWK_ID = Pattern.compile("\\bFWK-\\d{3}\\b");
    private static final int REQUIRED_FRAMEWORK_AC_COUNT = 18;
    private static final double REQUIRED_AC_AUTOMATION_RATIO = 1.00;

    @Test
    @DisplayName("v0.2 maturity | framework AC path automation mapping is complete")
    void frameworkAcPathAutomationMappingIsComplete() throws Exception {
        String acceptanceCriteria = Files.readString(Path.of("docs/03-acceptance/04_acceptance_criteria.md"));
        String testPlan = Files.readString(Path.of("docs/07-validation-evidence/07_regression_test_plan.md"));
        Set<String> frameworkAcs = frameworkAcs(acceptanceCriteria);
        Map<String, Boolean> automatedFrameworkCases = automatedFrameworkCases(testPlan);
        Map<String, PathCoverage> pathCoverageRows = pathCoverageRows(testPlan);

        assertThat(testPlan)
                .contains("framework AC-001 through AC-018 have automated happy, failure, and boundary path coverage mapped");
        assertThat(frameworkAcs).hasSize(REQUIRED_FRAMEWORK_AC_COUNT);
        assertThat(pathCoverageRows.keySet()).containsExactlyInAnyOrderElementsOf(frameworkAcs);

        long fullyAutomatedPathCount = frameworkAcs.stream()
                .map(pathCoverageRows::get)
                .filter(pathCoverage -> pathCoverage.hasAutomatedHappyFailureAndBoundary(automatedFrameworkCases))
                .count();
        int requiredAutomatedCount = (int) Math.ceil(frameworkAcs.size() * REQUIRED_AC_AUTOMATION_RATIO);

        assertThat(fullyAutomatedPathCount)
                .as("framework ACs with automated happy, failure, and boundary coverage")
                .isGreaterThanOrEqualTo(requiredAutomatedCount);
    }

    @Test
    @DisplayName("v0.2 maturity | Maven JaCoCo gate declares explicit framework coverage thresholds")
    void mavenJacocoGateDeclaresExplicitFrameworkCoverageThresholds() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .contains("<id>check</id>")
                .contains("<element>BUNDLE</element>");
        assertThat(pom)
                .contains("<exclude>com/specdriven/regression/RegressionApplication*</exclude>");
        assertJacocoCoveredRatioLimit(pom, "INSTRUCTION", "0.99");
        assertJacocoCoveredRatioLimit(pom, "BRANCH", "0.90");
        assertJacocoCoveredRatioLimit(pom, "LINE", "0.98");
        assertJacocoCoveredRatioLimit(pom, "METHOD", "1.00");
        assertJacocoCoveredRatioLimit(pom, "CLASS", "1.00");
    }

    @Test
    @DisplayName("v0.2 maturity | delivery JaCoCo gate declares critical-class scope and exclusions")
    void deliveryJacocoGateDeclaresCriticalClassScopeAndExclusions() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        String implementationPlan = Files.readString(Path.of("docs/04-planning/16_implementation_plan.md"));

        assertThat(pom)
                .contains("<id>v02-delivery-coverage</id>")
                .contains("<element>CLASS</element>")
                .contains("<include>com.specdriven.regression.cli.RegressionCommand*</include>")
                .contains("<include>com.specdriven.regression.dsl.DslTestCaseValidator*</include>")
                .contains("<include>com.specdriven.regression.runtime.GeneratedRuntimeArtifacts*</include>")
                .contains("<include>com.specdriven.regression.execution.ExecutionEngine*</include>")
                .contains("<include>com.specdriven.regression.provider.ProviderCapabilityRegistry*</include>")
                .contains("<include>com.specdriven.regression.provider.RequestResponseProvider*</include>")
                .contains("<include>com.specdriven.regression.provider.MessagingProvider*</include>")
                .contains("<include>com.specdriven.regression.provider.DatabaseFixtureProvider*</include>")
                .contains("<include>com.specdriven.regression.provider.DeploymentReadinessProvider*</include>")
                .contains("<include>com.specdriven.regression.assertion.AssertionEngine*</include>")
                .contains("<include>com.specdriven.regression.report.CoverageReportService*</include>")
                .contains("<exclude>com.specdriven.regression.*.*Result*</exclude>")
                .contains("<exclude>com.specdriven.regression.*.*Report*</exclude>")
                .contains("<exclude>com.specdriven.regression.*.*Gap*</exclude>");
        assertJacocoCoveredRatioLimit(pom, "LINE", "1.00");
        assertJacocoCoveredRatioLimit(pom, "BRANCH", "0.90");
        assertThat(implementationPlan)
                .contains("`./mvnw -Pv02-delivery-coverage verify`")
                .contains("Critical-class line coverage gate")
                .contains("Allowed delivery-gate exclusions");
    }

    private Set<String> frameworkAcs(String acceptanceCriteria) {
        Set<String> acs = new LinkedHashSet<>();
        Matcher matcher = AC_ID.matcher(acceptanceCriteria);
        while (matcher.find()) {
            String ac = matcher.group();
            if (!ac.startsWith("AC-")) {
                continue;
            }
            int number = Integer.parseInt(ac.substring("AC-".length()));
            if (number >= 1 && number <= REQUIRED_FRAMEWORK_AC_COUNT) {
                acs.add(ac);
            }
        }
        return acs;
    }

    private Map<String, Boolean> automatedFrameworkCases(String testPlan) {
        Map<String, Boolean> cases = new LinkedHashMap<>();
        for (String line : testPlan.lines().toList()) {
            if (!line.startsWith("| FWK-")) {
                continue;
            }
            String[] columns = columns(line);
            if (columns.length < 7) {
                continue;
            }
            cases.put(columns[1], columns[6].contains("Auto"));
        }
        return cases;
    }

    private Map<String, PathCoverage> pathCoverageRows(String testPlan) {
        Map<String, PathCoverage> rows = new LinkedHashMap<>();
        boolean inMatrix = false;
        for (String line : testPlan.lines().toList()) {
            if (line.startsWith("### 7.6.4 AC Path Coverage Matrix")) {
                inMatrix = true;
                continue;
            }
            if (inMatrix && line.startsWith("### ")) {
                break;
            }
            if (!inMatrix || !line.startsWith("| AC-")) {
                continue;
            }
            String[] columns = columns(line);
            if (columns.length >= 5) {
                rows.put(columns[1], new PathCoverage(columns[2], columns[3], columns[4]));
            }
        }
        return rows;
    }

    private String[] columns(String line) {
        String[] raw = line.split("\\|", -1);
        String[] columns = new String[raw.length];
        for (int index = 0; index < raw.length; index++) {
            columns[index] = raw[index].trim();
        }
        return columns;
    }

    private void assertJacocoCoveredRatioLimit(String pom, String counter, String minimum) {
        Pattern pattern = Pattern.compile(
                "<counter>\\s*" + counter + "\\s*</counter>\\s*"
                        + "<value>\\s*COVEREDRATIO\\s*</value>\\s*"
                        + "<minimum>\\s*" + Pattern.quote(minimum) + "\\s*</minimum>",
                Pattern.DOTALL);
        assertThat(pattern.matcher(pom).find())
                .as("JaCoCo %s covered ratio minimum %s", counter, minimum)
                .isTrue();
    }

    private record PathCoverage(String happy, String failure, String boundary) {

        boolean hasAutomatedHappyFailureAndBoundary(Map<String, Boolean> automatedFrameworkCases) {
            return hasAutomatedCase(happy, automatedFrameworkCases)
                    && hasAutomatedCase(failure, automatedFrameworkCases)
                    && hasAutomatedCase(boundary, automatedFrameworkCases);
        }

        private boolean hasAutomatedCase(String cell, Map<String, Boolean> automatedFrameworkCases) {
            Matcher matcher = FWK_ID.matcher(cell);
            while (matcher.find()) {
                if (automatedFrameworkCases.getOrDefault(matcher.group(), false)) {
                    return true;
                }
            }
            return false;
        }
    }
}

package com.specdriven.regression.provider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Compatibility entry points for retired RP database operations. */
@Component
public class DatabaseFixtureProvider {
    private static final Pattern DB_FAMILY = Pattern.compile("db_fixture", Pattern.CASE_INSENSITIVE);
    private static final Pattern DB_TYPE = Pattern.compile("jdbc|relational_db", Pattern.CASE_INSENSITIVE);

    public static final String RETIRED =
            "Legacy DB fixture execution is disabled. Migrate the case to a v0.3 suite with a JDBC provider.";

    public static boolean hasRowAssertion(Map<String, Object> normalizedCase) {
        return normalizedCase.get("assertions") instanceof List<?> assertions && assertions.stream()
                .anyMatch(value -> value instanceof Map<?, ?> assertion && "db_row_matches".equals(assertion.get("type")));
    }

    public void checkExecution(Map<String, Object> normalizedCase, ProviderContractResolutionReport report) {
        if (hasRowAssertion(normalizedCase)
                || report.gaps().stream().anyMatch(g -> isDbFixture(g.contractType(), g.providerFamily(), g.providerType()))
                || report.resolvedContracts().stream().anyMatch(c -> isDbFixture(c.contractType(), c.providerFamily(), c.providerType()))) {
            throw new UnsupportedOperationException(RETIRED);
        }
    }

    private boolean isDbFixture(String section, String family, String type) {
        return "fixture".equals(section) && (DB_FAMILY.matcher(Objects.toString(family, "")).matches()
                || DB_TYPE.matcher(Objects.toString(type, "")).matches());
    }

    public void setup(Path packageRoot, Map<String, Object> testCase,
            Map<String, Map<String, Object>> fixtureContracts, Path runDir) {
        rejectSelectedFixture(testCase, "setup", fixtureContracts);
    }

    public void cleanup(Path packageRoot, Map<String, Object> testCase,
            Map<String, Map<String, Object>> fixtureContracts, Path runDir) {
        rejectSelectedFixture(testCase, "cleanup", fixtureContracts);
    }

    private void rejectSelectedFixture(Map<String, Object> testCase, String phase,
            Map<String, Map<String, Object>> fixtureContracts) {
        if (testCase.get("fixture") instanceof Map<?, ?> fixture && fixture.get(phase) instanceof List<?> actions) {
            for (Object value : actions) {
                if (value instanceof Map<?, ?> action && action.get("provider") instanceof String provider
                        && fixtureContracts.containsKey(provider)) {
                    throw new UnsupportedOperationException(RETIRED);
                }
            }
        }
    }
}

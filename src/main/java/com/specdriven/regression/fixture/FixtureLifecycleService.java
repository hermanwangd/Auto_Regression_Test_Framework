package com.specdriven.regression.fixture;

import com.specdriven.regression.dsl.DslTestCaseNormalizer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class FixtureLifecycleService {

    private final DslTestCaseNormalizer dslTestCaseNormalizer = new DslTestCaseNormalizer();

    public FixtureLifecycleReport validate(Path testCasePath) {
        Map<String, Object> testCase = dslTestCaseNormalizer.normalize(readYamlMap(testCasePath));
        Object fixture = testCase.get("fixture");
        Object policy = testCase.get("policy");
        Set<String> fixtureProviders = new LinkedHashSet<>();
        List<FixtureLifecycleGap> gaps = new ArrayList<>();
        boolean mutatesState = false;
        boolean hasCleanup = false;
        boolean cleanupRequired = false;

        if (fixture instanceof Map<?, ?> fixtureMap) {
            Object setup = fixtureMap.get("setup");
            if (setup instanceof List<?> setupEntries) {
                for (Object setupEntry : setupEntries) {
                    if (setupEntry instanceof Map<?, ?> setupMap) {
                        String provider = stringValue(setupMap.get("provider"));
                        if (!provider.isBlank()) {
                            fixtureProviders.add(provider);
                        }
                        if ("mutates_state".equals(setupMap.get("lifecycle"))) {
                            mutatesState = true;
                        }
                    }
                }
            }
            Object cleanup = fixtureMap.get("cleanup");
            hasCleanup = cleanup instanceof List<?> cleanupEntries && !cleanupEntries.isEmpty();
        }

        if (policy instanceof Map<?, ?> policyMap) {
            cleanupRequired = Boolean.TRUE.equals(policyMap.get("cleanup_required"));
        }

        if (mutatesState && !hasCleanup) {
            gaps.add(new FixtureLifecycleGap(
                    "fixture.cleanup",
                    "Declare cleanup for mutating fixture before execution."));
        }
        if (mutatesState && !cleanupRequired) {
            gaps.add(new FixtureLifecycleGap(
                    "policy.cleanup_required",
                    "Declare cleanup_required true for mutating fixture before execution."));
        }

        return new FixtureLifecycleReport(
                gaps.isEmpty(),
                cleanupRequired,
                List.copyOf(fixtureProviders),
                List.copyOf(gaps));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlMap(Path path) {
        try {
            Object loaded = new Yaml().load(Files.readString(path));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read DSL test fixture lifecycle: " + path, e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}

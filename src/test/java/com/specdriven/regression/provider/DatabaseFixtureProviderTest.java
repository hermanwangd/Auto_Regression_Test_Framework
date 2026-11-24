package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseFixtureProviderTest {
    @TempDir Path root;
    private final DatabaseFixtureProvider provider = new DatabaseFixtureProvider();

    @Test
    void selectedSetupAndCleanupAreRejectedBeforeOpeningAssetsOrCreatingEvidence() {
        var actions = List.of(Map.of("provider", "db", "action", "example"));
        Map<String, Object> testCase = Map.of("fixture", Map.of("setup", actions, "cleanup", actions));
        Map<String, Map<String, Object>> contracts = Map.of("db", Map.of("connection_ref", "fixture-marker"));
        Path output = root.resolve("output");

        assertThatThrownBy(() -> provider.setup(root, testCase, contracts, output))
                .isInstanceOf(UnsupportedOperationException.class).hasMessage(DatabaseFixtureProvider.RETIRED);
        assertThatThrownBy(() -> provider.cleanup(root, testCase, contracts, output))
                .isInstanceOf(UnsupportedOperationException.class).hasMessage(DatabaseFixtureProvider.RETIRED);
        assertThat(output).doesNotExist();
    }

    @Test
    void preflightRecognizesCaseVariantsInResolvedAndBlockedFixtureContracts() {
        var resolved = new ResolvedProviderContract("fixture", "orders", "", "DB_FIXTURE", "", "", "");
        var adapter = new ResolvedProviderContract("provider", "orders", "", "db_fixture", "", "", "");
        var gap = new ProviderContractGap("field", "fixture", "orders", "", "ReLaTiOnAl_Db",
                "unsupported", "blocked", "", "", "");
        for (var report : List.of(new ProviderContractResolutionReport(true, List.of(resolved), List.of()),
                new ProviderContractResolutionReport(true, List.of(adapter), List.of()),
                new ProviderContractResolutionReport(false, List.of(), List.of(gap)))) {
            assertThatThrownBy(() -> provider.checkExecution(Map.of(), report))
                    .isInstanceOf(UnsupportedOperationException.class).hasMessage(DatabaseFixtureProvider.RETIRED);
        }
    }

    @Test
    void absentOrUnrelatedFixturesNeedNoDatabase() {
        Map<String, Map<String, Object>> contracts = Map.of("db", Map.of());
        Path output = root.resolve("output");
        var fileFixture = new ResolvedProviderContract("fixture", "file", "", "file_batch", "file_fixture",
                "supported", "supported", "", "", "");
        provider.checkExecution(Map.of("assertions", List.of("invalid", Map.of("type", "file_diff"))),
                new ProviderContractResolutionReport(true, List.of(fileFixture), List.of()));
        provider.setup(root, Map.of(), contracts, output);
        provider.setup(root, Map.of("fixture", Map.of()), contracts, output);
        provider.cleanup(root, Map.of("fixture", Map.of("cleanup",
                List.of("invalid", Map.of(), Map.of("provider", "file")))), contracts, output);
        assertThat(output).doesNotExist();
    }
}

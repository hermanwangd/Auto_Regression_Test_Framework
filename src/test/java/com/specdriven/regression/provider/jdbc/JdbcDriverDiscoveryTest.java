package com.specdriven.regression.provider.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcDriverDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void cliDriverPathReturnsSingleJar() throws Exception {
        Path jar = jar("drivers/oracle/ojdbc11.jar");

        JdbcDriverDiscovery.DiscoveryResult result = discovery(Map.of())
                .discover(List.of(jar.toString()), "");

        assertThat(result.driverSource()).isEqualTo("cli_driver_path");
        assertThat(result.driverStatus()).isEqualTo("found");
        assertThat(result.driverPaths()).containsExactly(jar.toAbsolutePath().normalize());
        assertThat(result.ownerAction()).isBlank();
    }

    @Test
    void repeatedCliDriverPathPreservesOrder() throws Exception {
        Path oracle = jar("drivers/oracle/ojdbc11.jar");
        Path db2 = jar("drivers/db2/jcc.jar");

        JdbcDriverDiscovery.DiscoveryResult result = discovery(Map.of())
                .discover(List.of(oracle.toString(), db2.toString()), "");

        assertThat(result.driverSource()).isEqualTo("cli_driver_path");
        assertThat(result.driverStatus()).isEqualTo("found");
        assertThat(result.driverPaths())
                .containsExactly(oracle.toAbsolutePath().normalize(), db2.toAbsolutePath().normalize());
    }

    @Test
    void driverDirScansOnlyDirectChildJars() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("drivers"));
        Path direct = jar("drivers/ojdbc11.jar");
        jar("drivers/nested/db2jcc.jar");
        Files.writeString(dir.resolve("README.txt"), "not a jar");

        JdbcDriverDiscovery.DiscoveryResult result = discovery(Map.of())
                .discover(List.of(), dir.toString());

        assertThat(result.driverSource()).isEqualTo("cli_driver_dir");
        assertThat(result.driverStatus()).isEqualTo("found");
        assertThat(result.driverPaths()).containsExactly(direct.toAbsolutePath().normalize());
    }

    @Test
    void regressDriverPathEnvironmentUsesPlatformSeparator() throws Exception {
        Path oracle = jar("env/ojdbc11.jar");
        Path db2 = jar("env/jcc.jar");
        String envValue = oracle + System.getProperty("path.separator") + db2;

        JdbcDriverDiscovery.DiscoveryResult result = discovery(Map.of("REGRESS_DRIVER_PATH", envValue))
                .discover(List.of(), "");

        assertThat(result.driverSource()).isEqualTo("env_regress_driver_path");
        assertThat(result.driverStatus()).isEqualTo("found");
        assertThat(result.driverPaths())
                .containsExactly(oracle.toAbsolutePath().normalize(), db2.toAbsolutePath().normalize());
    }

    @Test
    void usageKitDriversFallbackScansDriversDirectory() throws Exception {
        Path jar = jar("drivers/ojdbc11.jar");

        JdbcDriverDiscovery.DiscoveryResult result = discovery(Map.of())
                .discover(List.of(), "");

        assertThat(result.driverSource()).isEqualTo("usage_kit_drivers");
        assertThat(result.driverStatus()).isEqualTo("found");
        assertThat(result.driverPaths()).containsExactly(jar.toAbsolutePath().normalize());
    }

    @Test
    void missingCliDriverPathIsOwnerActionable() {
        Path missing = tempDir.resolve("drivers/missing.jar");

        JdbcDriverDiscovery.DiscoveryResult result = discovery(Map.of())
                .discover(List.of(missing.toString()), "");

        assertThat(result.driverSource()).isEqualTo("cli_driver_path");
        assertThat(result.driverStatus()).isEqualTo("missing");
        assertThat(result.ownerAction()).contains("Restore JDBC driver jar");
        assertThat(result.findings()).isNotEmpty();
    }

    @Test
    void nonJarCliDriverPathIsInvalid() throws Exception {
        Path text = Files.createDirectories(tempDir.resolve("drivers")).resolve("driver.txt");
        Files.writeString(text, "not a jar");

        JdbcDriverDiscovery.DiscoveryResult result = discovery(Map.of())
                .discover(List.of(text.toString()), "");

        assertThat(result.driverSource()).isEqualTo("cli_driver_path");
        assertThat(result.driverStatus()).isEqualTo("invalid");
        assertThat(result.ownerAction()).contains(".jar");
        assertThat(result.findings()).isNotEmpty();
    }

    @Test
    @DisplayName("JDS-P01: explicit path wins over other configured sources")
    void boundaryExplicitPathWinsOverOtherSources() throws Exception {
        BoundaryFixtures f = boundaryFixtures();
        assertBoundary(new JdbcDriverDiscovery(tempDir, forbiddenEnvironment())
                .discover(List.of(f.cli().toString()), f.directory().toString()),
                "cli_driver_path", "found", List.of(f.cli()), "");
    }

    @Test
    @DisplayName("JDS-N02: invalid explicit path does not fall back")
    void boundaryInvalidExplicitPathDoesNotFallBack() throws Exception {
        BoundaryFixtures f = boundaryFixtures();
        assertBoundary(new JdbcDriverDiscovery(tempDir, forbiddenEnvironment())
                .discover(List.of(f.invalid().toString()), f.directory().toString()),
                "cli_driver_path", "invalid", List.of(), "Supply readable JDBC driver .jar files only.");
    }

    @Test
    @DisplayName("JDS-N03: missing explicit path does not fall back")
    void boundaryMissingExplicitPathDoesNotFallBack() throws Exception {
        BoundaryFixtures f = boundaryFixtures();
        assertBoundary(new JdbcDriverDiscovery(tempDir, forbiddenEnvironment())
                .discover(List.of(f.missing().toString()), f.directory().toString()),
                "cli_driver_path", "missing", List.of(), "Restore JDBC driver jar files");
    }

    @Test
    @DisplayName("JDS-P04: explicit directory selects sorted direct child jars")
    void boundaryExplicitDirectorySelectsSortedDirectChildJars() throws Exception {
        BoundaryFixtures f = boundaryFixtures();
        assertBoundary(new JdbcDriverDiscovery(tempDir, forbiddenEnvironment())
                .discover(List.of(), f.directory().toString()),
                "cli_driver_dir", "found", List.of(f.first(), f.last()), "");
    }

    @Test
    @DisplayName("JDS-N05: missing explicit directory does not fall back")
    void boundaryMissingExplicitDirectoryDoesNotFallBack() throws Exception {
        BoundaryFixtures f = boundaryFixtures();
        assertBoundary(new JdbcDriverDiscovery(tempDir, forbiddenEnvironment())
                .discover(List.of(), f.missingDirectory().toString()),
                "cli_driver_dir", "missing", List.of(), "Restore JDBC driver jar files");
    }

    @Test
    @DisplayName("JDS-P06: environment path wins over usage-kit fallback")
    void boundaryEnvironmentPathWinsOverUsageKitFallback() throws Exception {
        BoundaryFixtures f = boundaryFixtures();
        assertBoundary(new JdbcDriverDiscovery(tempDir, configuredEnvironment(f.env()))
                .discover(List.of(), ""),
                "env_regress_driver_path", "found", List.of(f.env()), "");
    }

    @Test
    @DisplayName("JDS-N07: missing environment path does not fall back")
    void boundaryMissingEnvironmentPathDoesNotFallBack() throws Exception {
        BoundaryFixtures f = boundaryFixtures();
        assertBoundary(new JdbcDriverDiscovery(tempDir, configuredEnvironment(f.missing()))
                .discover(List.of(), ""),
                "env_regress_driver_path", "missing", List.of(), "Restore JDBC driver jar files");
    }

    @Test
    @DisplayName("JDS-P08: blank path values permit explicit directory")
    void boundaryBlankPathValuesPermitExplicitDirectory() throws Exception {
        BoundaryFixtures f = boundaryFixtures();
        assertBoundary(new JdbcDriverDiscovery(tempDir, forbiddenEnvironment())
                .discover(List.of("", " "), f.directory().toString()),
                "cli_driver_dir", "found", List.of(f.first(), f.last()), "");
    }

    @Test
    @DisplayName("JDS-P09: absent explicit and environment sources use usage-kit")
    void boundaryAbsentSourcesUseUsageKit() throws Exception {
        BoundaryFixtures f = boundaryFixtures();
        assertBoundary(new JdbcDriverDiscovery(tempDir, configuredEnvironment(null))
                .discover(List.of(), ""),
                "usage_kit_drivers", "found", List.of(f.fallback()), "");
    }

    @Test
    @DisplayName("JDS-N10: no available source returns actionable missing")
    void boundaryNoAvailableSourceIsActionable() throws Exception {
        BoundaryFixtures f = boundaryFixtures();
        assertBoundary(new JdbcDriverDiscovery(f.emptyWorkingDirectory(), configuredEnvironment(null))
                .discover(List.of(), ""),
                "none", "missing", List.of(), "Provide JDBC driver jars");
    }

    private BoundaryFixtures boundaryFixtures() throws IOException {
        Path cli = boundaryJar("cli/primary.jar");
        Path env = boundaryJar("env/env.jar");
        Path fallback = boundaryJar("drivers/fallback.jar");
        Path directory = Files.createDirectories(tempDir.resolve("directory"));
        Path first = boundaryJar("directory/a.jar");
        Path last = boundaryJar("directory/z.jar");
        boundaryJar("directory/ignore.txt");
        boundaryJar("directory/nested/not-listed.jar");
        Path invalid = boundaryJar("cli/not-a-jar.txt");
        Path empty = Files.createDirectory(tempDir.resolve("empty-working-directory"));
        return new BoundaryFixtures(cli, env, fallback, directory, first, last, invalid,
                tempDir.resolve("missing.jar"), tempDir.resolve("missing-directory"), empty);
    }

    private Path boundaryJar(String relativePath) throws IOException {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "discovery-only fixture; never a loaded JDBC driver\n");
        return path;
    }

    private static Function<String, String> forbiddenEnvironment() {
        return key -> { throw new AssertionError("lower-priority environment consulted: " + key); };
    }

    private static Function<String, String> configuredEnvironment(Path value) {
        return key -> {
            assertThat(key).isEqualTo("REGRESS_DRIVER_PATH");
            return value == null ? null : value.toString();
        };
    }

    private static void assertBoundary(JdbcDriverDiscovery.DiscoveryResult result,
                                       String source, String status, List<Path> paths, String actionPrefix) {
        assertThat(result.driverSource()).isEqualTo(source);
        assertThat(result.driverStatus()).isEqualTo(status);
        assertThat(result.driverPaths()).isEqualTo(paths);
        assertThat(result.found()).isEqualTo("found".equals(status));
        if ("found".equals(status)) {
            assertThat(result.findings()).isEmpty();
            assertThat(result.ownerAction()).isEmpty();
        } else {
            assertThat(result.findings()).hasSize(1);
            assertThat(result.ownerAction()).startsWith(actionPrefix);
        }
    }

    private record BoundaryFixtures(Path cli, Path env, Path fallback, Path directory,
                                    Path first, Path last, Path invalid, Path missing,
                                    Path missingDirectory, Path emptyWorkingDirectory) { }

    private JdbcDriverDiscovery discovery(Map<String, String> environment) {
        return new JdbcDriverDiscovery(tempDir, environment::get);
    }

    private Path jar(String relativePath) throws IOException {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "jar placeholder");
        return path;
    }
}

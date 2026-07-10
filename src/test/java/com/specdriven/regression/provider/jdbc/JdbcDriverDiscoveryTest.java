package com.specdriven.regression.provider.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

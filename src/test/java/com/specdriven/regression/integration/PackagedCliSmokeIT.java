package com.specdriven.regression.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PackagedCliSmokeIT {

    @TempDir
    Path tempDir;

    enum DriverSource { ORACLE_JAR, DB2_DIRECTORY }

    @ParameterizedTest
    @EnumSource(DriverSource.class)
    void ordinaryV03RunUsesTheExplicitDriverSource(DriverSource source) throws Exception {
        boolean explicitJar = source == DriverSource.ORACLE_JAR;
        String dialect = explicitJar ? "oracle" : "db2";
        String option = explicitJar ? "--driver-path" : "--driver-dir";
        String value = explicitJar ? "vendor/fixture.jar" : "vendor";
        Path suite = tempDir.resolve("jdbc");
        copyDirectory(Path.of("samples/20-provider-capability-p0/data/jdbc"), suite);
        Files.writeString(suite.resolve("queries/find_order_by_id.sql"), "SELECT 1\n");
        Files.writeString(suite.resolve("test_cases/order_query_success.yaml"), """
                dsl_version: v0.3
                test_case_id: DRIVER-SELECTION
                title: Discover the external driver
                execute:
                  - id: query_order
                    target: order_db
                    op: db_query
                    with:
                      query_ref: artifact://queries/find_order_by_id.sql
                verify:
                  - id: one_row
                    type: assertion
                    assert:
                      actual: step://query_order/row_count
                      operator: equals
                      expected: 1
                """);
        Files.createDirectory(tempDir.resolve("vendor"));
        // A real jar with no vendor class reaches the loader and yields JDBC_DRIVER_INVALID.
        try (var jar = new JarOutputStream(Files.newOutputStream(tempDir.resolve("vendor/fixture.jar")))) {
            jar.finish();
        }
        Path output = tempDir.resolve("cli.log");
        var cli = new ProcessBuilder("java", "-Xmx192m", "-jar",
                "target/spec-driven-auto-regression-0.3.2.jar", "run",
                "--root", tempDir.toString(), "--suite", "jdbc/suite_manifest.yaml",
                "--profile", "external_" + dialect, option, value)
                .redirectErrorStream(true).redirectOutput(output.toFile());
        cli.environment().keySet().removeIf(name -> name.startsWith("REGRESS_REVIEWED_"));
        cli.environment().put("REGRESS_DRIVER_PATH", tempDir.resolve("missing.jar").toString());
        cli.environment().put("JDBC_CONNECTION", "jdbc:" + dialect + ":fixture");
        cli.environment().put("REGRESS_JDBC_APPROVED_SQL_SHA256", dialect + ":JDBC_CONNECTION:db_query:"
                + "a0a22c9dfa428cdce6fb3e58a40d4052335e17e5fbe42efc2f17f9ca813be62c");
        Process process = cli.start();
        try {
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).isTrue();
            String log = Files.readString(output);
            assertThat(process.exitValue()).as(log).isOne();
            assertThat(log).contains("failure_code: JDBC_DRIVER_INVALID");
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void packagedJarDelegatesCheckReadinessCommand() throws Exception {
        Path jar = Path.of("target/spec-driven-auto-regression-0.3.2.jar").toAbsolutePath().normalize();
        assertThat(Files.isRegularFile(jar)).isTrue();

        Process process = new ProcessBuilder(
                javaBinary(),
                "-Xmx512m",
                "-XX:MaxMetaspaceSize=256m",
                "-jar",
                jar.toString(),
                "check-readiness",
                "--root",
                ".")
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(20).toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(output)
                .contains("status: pass")
                .contains("ready: true")
                .contains("next_required_step:");
    }

    @Test
    void packagedJarUsesBundledProviderContractsOutsideRepositoryCwd() throws Exception {
        Path jar = Path.of("target/spec-driven-auto-regression-0.3.2.jar").toAbsolutePath().normalize();
        assertThat(Files.isRegularFile(jar)).isTrue();
        Path copiedSuite = tempDir.resolve("usage-kit").resolve("nats");
        copyDirectory(Path.of("samples/20-provider-capability-p0/messaging/nats"), copiedSuite);

        Process process = new ProcessBuilder(
                javaBinary(),
                "-Xmx512m",
                "-XX:MaxMetaspaceSize=256m",
                "-jar",
                jar.toString(),
                "validate",
                "--suite",
                copiedSuite.resolve("suite_manifest.yaml").toString())
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(20).toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output)
                .contains("validation_status: passed")
                .contains("suite_id: NATS-v0.3")
                .contains("provider_contracts_used:")
                .contains("nats.v0.3");
    }

    private String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private void copyDirectory(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }
}

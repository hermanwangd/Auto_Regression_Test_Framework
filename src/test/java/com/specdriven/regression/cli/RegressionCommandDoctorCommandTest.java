package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegressionCommandDoctorCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void doctorRequiresSubcommand() {
        CommandResult result = execute("doctor");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.stderr())
                .contains("Missing doctor subcommand.")
                .contains("usage: regress doctor drivers");
        assertThat(result.stdout()).isBlank();
    }

    @Test
    void doctorRejectsUnknownSubcommand() {
        CommandResult result = execute("doctor", "network", "--root", tempDir.toString());

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.stderr())
                .contains("Unknown doctor subcommand: network")
                .contains("usage: regress doctor drivers");
        assertThat(result.stdout()).isBlank();
    }

    @Test
    void doctorDriversReportsMissingDriversWithOwnerAction() {
        CommandResult result = execute("doctor", "drivers", "--root", tempDir.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stderr()).isBlank();
        assertThat(result.stdout())
                .contains("driver_diagnostics_status: failed")
                .contains("driver_source: none")
                .contains("driver_status: missing")
                .contains("oracle_driver_loadable: false")
                .contains("db2_driver_loadable: false")
                .contains("oracle_failure_code: JDBC_DRIVER_NOT_FOUND")
                .contains("db2_failure_code: JDBC_DRIVER_NOT_FOUND")
                .contains("owner_action: Provide JDBC driver jars");
    }

    @Test
    void doctorDriversAcceptsRepeatedDriverPathOptionsAndMasksLoadFailures() throws Exception {
        Path oraclePlaceholder = jar("drivers/oracle/ojdbc11.jar");
        Path db2Placeholder = jar("drivers/db2/jcc.jar");

        CommandResult result = execute(
                "doctor",
                "drivers",
                "--root",
                tempDir.toString(),
                "--driver-path",
                oraclePlaceholder.toString(),
                "--driver-path",
                db2Placeholder.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stderr()).isBlank();
        assertThat(result.stdout())
                .contains("driver_diagnostics_status: failed")
                .contains("driver_source: cli_driver_path")
                .contains("driver_status: found")
                .contains("  - " + oraclePlaceholder.toAbsolutePath().normalize())
                .contains("  - " + db2Placeholder.toAbsolutePath().normalize())
                .contains("oracle_failure_code: JDBC_DRIVER_INVALID")
                .contains("db2_failure_code: JDBC_DRIVER_INVALID")
                .contains("owner_action: Place the matching vendor driver jar")
                .doesNotContain("JDBC_CONNECTION");
    }

    @Test
    void doctorDriversPassesWhenDriverDirContainsLoadableOracleOrDb2Driver() throws Exception {
        Path driverDir = Files.createDirectories(tempDir.resolve("drivers"));
        Path driverJar = driverJar(driverDir.resolve("test-jdbc-drivers.jar"));

        CommandResult result = execute(
                "doctor",
                "drivers",
                "--root",
                tempDir.toString(),
                "--driver-dir",
                driverDir.toString());

        assertThat(result.exit()).isZero();
        assertThat(result.stderr()).isBlank();
        assertThat(result.stdout())
                .contains("driver_diagnostics_status: passed")
                .contains("driver_source: cli_driver_dir")
                .contains("driver_status: found")
                .contains("  - " + driverJar.toAbsolutePath().normalize())
                .contains("oracle_driver_loadable: true")
                .contains("db2_driver_loadable: true")
                .doesNotContain("failure_code")
                .doesNotContain("owner_action");
    }

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, new PrintStream(stdout), new PrintStream(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private Path jar(String relativePath) throws Exception {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "jar placeholder");
        return path;
    }

    private Path driverJar(Path jar) throws Exception {
        Path sourceRoot = Files.createDirectories(tempDir.resolve("driver-src"));
        Path classRoot = Files.createDirectories(tempDir.resolve("driver-classes"));
        Path oracleSource = sourceRoot.resolve("oracle/jdbc/OracleDriver.java");
        Path db2Source = sourceRoot.resolve("com/ibm/db2/jcc/DB2Driver.java");
        Files.createDirectories(oracleSource.getParent());
        Files.createDirectories(db2Source.getParent());
        Files.writeString(oracleSource, driverSource("oracle.jdbc", "OracleDriver", "jdbc:oracle:"));
        Files.writeString(db2Source, driverSource("com.ibm.db2.jcc", "DB2Driver", "jdbc:db2:"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        assertThat(compiler.run(
                        null,
                        null,
                        null,
                        "-d",
                        classRoot.toString(),
                        oracleSource.toString(),
                        db2Source.toString()))
                .isZero();
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var paths = Files.walk(classRoot)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Path::toString))
                        .toList()) {
                    String entryName = classRoot.relativize(path).toString().replace('\\', '/');
                    output.putNextEntry(new JarEntry(entryName));
                    Files.copy(path, output);
                    output.closeEntry();
                }
            }
        }
        return jar;
    }

    private String driverSource(String packageName, String className, String urlPrefix) {
        return """
                package %s;

                import java.sql.Connection;
                import java.sql.Driver;
                import java.sql.DriverPropertyInfo;
                import java.sql.SQLFeatureNotSupportedException;
                import java.util.Properties;
                import java.util.logging.Logger;

                public class %s implements Driver {
                    public Connection connect(String url, Properties info) {
                        return null;
                    }

                    public boolean acceptsURL(String url) {
                        return url != null && url.startsWith("%s");
                    }

                    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
                        return new DriverPropertyInfo[0];
                    }

                    public int getMajorVersion() {
                        return 1;
                    }

                    public int getMinorVersion() {
                        return 0;
                    }

                    public boolean jdbcCompliant() {
                        return false;
                    }

                    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                        throw new SQLFeatureNotSupportedException();
                    }
                }
                """.formatted(packageName, className, urlPrefix);
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}

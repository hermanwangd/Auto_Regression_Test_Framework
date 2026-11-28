package com.specdriven.regression.cli;

import com.specdriven.regression.provider.jdbc.JdbcDriverDiscovery;
import com.specdriven.regression.provider.jdbc.JdbcDriverDiscovery.DiscoveryResult;
import com.specdriven.regression.provider.jdbc.JdbcDriverLoader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class DriverDiagnostics {

    private DriverDiagnostics() {}

    static int doctorDrivers(Path root, Map<String, String> options, PrintStream out) {
        JdbcDriverDiscovery.DiscoveryResult discovery = jdbcDriverDiscovery(root, options);
        JdbcDriverLoader loader = new JdbcDriverLoader();
        var oracle = loader.load("oracle", discovery);
        var db2 = loader.load("db2", discovery);
        var mariadb = loader.load("mariadb", discovery);
        boolean passed = oracle.loaded() || db2.loaded() || mariadb.loaded();
        out.println("driver_diagnostics_status: " + (passed ? "passed" : "failed"));
        out.println("driver_source: " + discovery.driverSource());
        out.println("driver_status: " + discovery.driverStatus());
        out.println("driver_paths:");
        for (Path path : discovery.driverPaths()) {
            out.println("  - " + path);
        }
        out.println("oracle_driver_loadable: " + oracle.loaded());
        out.println("db2_driver_loadable: " + db2.loaded());
        out.println("mariadb_driver_loadable: " + mariadb.loaded());
        if (!oracle.loaded()) {
            out.println("oracle_failure_code: " + oracle.failureCode());
        }
        if (!db2.loaded()) {
            out.println("db2_failure_code: " + db2.failureCode());
        }
        if (!mariadb.loaded()) {
            out.println("mariadb_failure_code: " + mariadb.failureCode());
        }
        String ownerAction = firstNonBlank(discovery.ownerAction(), oracle.ownerAction(), db2.ownerAction(), mariadb.ownerAction());
        if (!passed && !ownerAction.isBlank()) {
            out.println("owner_action: " + ownerAction);
        }
        return passed ? 0 : 1;
    }

    static Supplier<DiscoveryResult> forSuite(Path root, Map<String, String> options) {
        return () -> jdbcDriverDiscovery(root, options);
    }

    private static JdbcDriverDiscovery.DiscoveryResult jdbcDriverDiscovery(Path root, Map<String, String> options) {
        String driverPath = options.getOrDefault("--driver-path", "");
        List<String> driverPaths = driverPath.isBlank() ? List.of() : List.of(driverPath);
        return new JdbcDriverDiscovery(root, System::getenv)
                .discover(driverPaths, options.getOrDefault("--driver-dir", ""));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}

package com.specdriven.regression.provider.jdbc;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;

public class JdbcDriverLoader {

    public LoadResult load(String dialect, JdbcDriverDiscovery.DiscoveryResult discovery) {
        String driverClassName = driverClassName(dialect);
        if (driverClassName.isBlank()) {
            return LoadResult.failed(
                    "UNSUPPORTED_DIALECT",
                    "Unsupported JDBC dialect `" + dialect + "`.",
                    "Use JDBC dialect `oracle`, `db2` or `mariadb`.");
        }
        if (discovery == null || !discovery.found() || discovery.driverPaths().isEmpty()) {
            return LoadResult.failed(
                    "JDBC_DRIVER_NOT_FOUND",
                    "No JDBC driver jar is available for dialect `" + dialect + "`.",
                    discovery == null || discovery.ownerAction().isBlank()
                            ? "Provide JDBC driver jars through --driver-path, --driver-dir, REGRESS_DRIVER_PATH, or usage-kit/drivers/."
                            : discovery.ownerAction());
        }
        try {
            URL[] urls = discovery.driverPaths().stream()
                    .map(Path::toUri)
                    .map(uri -> {
                        try {
                            return uri.toURL();
                        } catch (java.net.MalformedURLException e) {
                            throw new IllegalArgumentException(e);
                        }
                    })
                    .toArray(URL[]::new);
            URLClassLoader classLoader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
            boolean registered = false;
            try {
                // Check the JDBC type before running any static initializer from a configured driver jar.
                Class<?> driverClass = switch (driverClassName) {
                    case "org.mariadb.jdbc.Driver" -> Class.forName("org.mariadb.jdbc.Driver", false, classLoader);
                    case "oracle.jdbc.OracleDriver" -> Class.forName("oracle.jdbc.OracleDriver", false, classLoader);
                    case "com.ibm.db2.jcc.DB2Driver" -> Class.forName("com.ibm.db2.jcc.DB2Driver", false, classLoader);
                    default -> throw new IllegalArgumentException("Unsupported JDBC driver.");
                };
                if (!Driver.class.isAssignableFrom(driverClass)) {
                    return invalid(dialect, driverClassName, "Class does not implement java.sql.Driver.");
                }
                Driver driver = driverClass.asSubclass(Driver.class).getConstructor().newInstance();
                DriverManager.registerDriver(new DriverShim(driver));
                registered = true;
                return new LoadResult(true, "", "Driver loaded: " + driverClassName, "");
            } finally {
                if (!registered) classLoader.close();
            }
        } catch (ClassNotFoundException e) {
            return invalid(dialect, driverClassName, "Driver class was not found in configured jars.");
        } catch (InvocationTargetException e) {
            return invalid(dialect, driverClassName, "Driver initialization failed.");
        } catch (ReflectiveOperationException | IOException | IllegalArgumentException | SQLException | LinkageError e) {
            return invalid(dialect, driverClassName, "Driver could not be initialized or registered.");
        }
    }

    public String driverClassName(String dialect) {
        return switch (dialect == null ? "" : dialect.toLowerCase(Locale.ROOT)) {
            case "oracle" -> "oracle.jdbc.OracleDriver";
            case "mariadb" -> "org.mariadb.jdbc.Driver";
            case "db2" -> "com.ibm.db2.jcc.DB2Driver";
            default -> "";
        };
    }

    private LoadResult invalid(String dialect, String driverClassName, String cause) {
        return LoadResult.failed(
                "JDBC_DRIVER_INVALID",
                "Unable to load JDBC driver `" + driverClassName + "` for dialect `" + dialect + "`."
                        + (cause == null || cause.isBlank() ? "" : " Cause: " + cause),
                "Place the matching vendor driver jar in --driver-path, --driver-dir, REGRESS_DRIVER_PATH, or usage-kit/drivers/.");
    }

    public record LoadResult(boolean loaded, String failureCode, String message, String ownerAction) {

        static LoadResult failed(String failureCode, String message, String ownerAction) {
            return new LoadResult(false, failureCode, message, ownerAction);
        }
    }

    private static final class DriverShim implements Driver {

        private final Driver delegate;

        private DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.sql.Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}

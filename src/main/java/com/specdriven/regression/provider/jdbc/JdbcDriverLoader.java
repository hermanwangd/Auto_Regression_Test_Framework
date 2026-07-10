package com.specdriven.regression.provider.jdbc;

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
                    "Use JDBC dialect `oracle` or `db2`.");
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
            URLClassLoader classLoader = new URLClassLoader(urls, Thread.currentThread().getContextClassLoader());
            Class<?> driverClass = Class.forName(driverClassName, true, classLoader);
            if (!Driver.class.isAssignableFrom(driverClass)) {
                return invalid(dialect, driverClassName, "Class does not implement java.sql.Driver.");
            }
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(new DriverShim(driver));
            return new LoadResult(true, "", "Driver loaded: " + driverClassName, "");
        } catch (ClassNotFoundException e) {
            return invalid(dialect, driverClassName, "Driver class was not found in configured jars.");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException() == null ? e : e.getTargetException();
            return invalid(dialect, driverClassName, cause.getMessage());
        } catch (ReflectiveOperationException | IllegalArgumentException | SQLException e) {
            return invalid(dialect, driverClassName, e.getMessage());
        }
    }

    public String driverClassName(String dialect) {
        return switch (dialect == null ? "" : dialect.toLowerCase(Locale.ROOT)) {
            case "oracle" -> "oracle.jdbc.OracleDriver";
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

package com.specdriven.regression.provider.jdbc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

public class JdbcDriverDiscovery {

    private static final String PATH_SEPARATOR = System.getProperty("path.separator");

    private final Path workingDirectory;
    private final Function<String, String> environment;

    public JdbcDriverDiscovery(Path workingDirectory, Function<String, String> environment) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public DiscoveryResult discover(List<String> cliDriverPaths, String cliDriverDir) {
        List<String> normalizedCliPaths = normalizePathValues(cliDriverPaths);
        if (!normalizedCliPaths.isEmpty()) {
            return validatePaths("cli_driver_path", normalizedCliPaths);
        }
        if (cliDriverDir != null && !cliDriverDir.isBlank()) {
            return scanDirectory("cli_driver_dir", resolve(cliDriverDir));
        }
        String envDriverPath = environment.apply("REGRESS_DRIVER_PATH");
        if (envDriverPath != null && !envDriverPath.isBlank()) {
            return validatePaths("env_regress_driver_path", splitPathList(envDriverPath));
        }
        Path usageKitDrivers = workingDirectory.resolve("drivers").normalize();
        if (Files.exists(usageKitDrivers)) {
            return scanDirectory("usage_kit_drivers", usageKitDrivers);
        }
        return new DiscoveryResult(
                "none",
                "missing",
                List.of(),
                "Provide JDBC driver jars through --driver-path, --driver-dir, REGRESS_DRIVER_PATH, or usage-kit/drivers/.",
                List.of("No JDBC driver source was configured."));
    }

    public static DiscoveryResult missing(Path workingDirectory) {
        return new JdbcDriverDiscovery(workingDirectory, ignored -> null).discover(List.of(), "");
    }

    private DiscoveryResult validatePaths(String source, List<String> values) {
        List<Path> resolvedPaths = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        String status = "found";
        for (String value : values) {
            Path path = resolve(value);
            if (!Files.exists(path)) {
                status = "missing";
                findings.add("Missing JDBC driver jar: " + path);
                continue;
            }
            if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jar")) {
                status = "invalid";
                findings.add("JDBC driver path must be a .jar file: " + path);
                continue;
            }
            resolvedPaths.add(path);
        }
        if ("found".equals(status)) {
            return new DiscoveryResult(source, status, List.copyOf(resolvedPaths), "", List.of());
        }
        return new DiscoveryResult(source, status, List.copyOf(resolvedPaths), ownerAction(status), List.copyOf(findings));
    }

    private DiscoveryResult scanDirectory(String source, Path directory) {
        if (!Files.exists(directory)) {
            return new DiscoveryResult(
                    source,
                    "missing",
                    List.of(),
                    ownerAction("missing"),
                    List.of("Missing JDBC driver directory: " + directory));
        }
        if (!Files.isDirectory(directory)) {
            return new DiscoveryResult(
                    source,
                    "invalid",
                    List.of(),
                    ownerAction("invalid"),
                    List.of("JDBC driver directory is not a directory: " + directory));
        }
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> jars = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            if (jars.isEmpty()) {
                return new DiscoveryResult(
                        source,
                        "missing",
                        List.of(),
                        ownerAction("missing"),
                        List.of("No direct child .jar files found in JDBC driver directory: " + directory));
            }
            return new DiscoveryResult(source, "found", jars, "", List.of());
        } catch (IOException e) {
            return new DiscoveryResult(
                    source,
                    "invalid",
                    List.of(),
                    ownerAction("invalid"),
                    List.of("Unable to scan JDBC driver directory `" + directory + "`: " + e.getMessage()));
        }
    }

    private List<String> normalizePathValues(List<String> values) {
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            normalized.addAll(splitPathList(value));
        }
        return List.copyOf(normalized);
    }

    private List<String> splitPathList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : value.split(java.util.regex.Pattern.quote(PATH_SEPARATOR))) {
            if (!part.isBlank()) {
                values.add(part);
            }
        }
        return List.copyOf(values);
    }

    private Path resolve(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = workingDirectory.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private String ownerAction(String status) {
        if ("invalid".equals(status)) {
            return "Supply readable JDBC driver .jar files only. Do not pass directories or placeholder files as --driver-path.";
        }
        return "Restore JDBC driver jar files or configure --driver-path, --driver-dir, REGRESS_DRIVER_PATH, or usage-kit/drivers/.";
    }

    public record DiscoveryResult(
            String driverSource,
            String driverStatus,
            List<Path> driverPaths,
            String ownerAction,
            List<String> findings) {

        public boolean found() {
            return "found".equals(driverStatus);
        }
    }
}

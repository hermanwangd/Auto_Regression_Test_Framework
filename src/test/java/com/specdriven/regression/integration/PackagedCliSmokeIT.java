package com.specdriven.regression.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedCliSmokeIT {

    @TempDir
    Path tempDir;

    @Test
    void packagedJarDelegatesCheckReadinessCommand() throws Exception {
        Path jar = Path.of("target/spec-driven-auto-regression-0.2.6.jar").toAbsolutePath().normalize();
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
        Path jar = Path.of("target/spec-driven-auto-regression-0.2.6.jar").toAbsolutePath().normalize();
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
                .contains("suite_id: NATS-CAPABILITY-v0.2")
                .contains("nats");
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

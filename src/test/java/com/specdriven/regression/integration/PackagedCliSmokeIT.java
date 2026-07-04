package com.specdriven.regression.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PackagedCliSmokeIT {

    @Test
    void packagedJarDelegatesCheckReadinessCommand() throws Exception {
        Path jar = Path.of("target/spec-driven-auto-regression-0.2.2.jar");
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

    private String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}

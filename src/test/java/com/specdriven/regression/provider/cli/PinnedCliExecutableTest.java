package com.specdriven.regression.provider.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PinnedCliExecutableTest {
    @TempDir Path temp;
    private final boolean windows = java.io.File.separatorChar == '\\';

    @Test void launchesInstalledJavaWithItsMatchingHashAndRejectsAnotherHash() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toRealPath();
        String digest = digest(java);
        try (var pinned = PinnedCliExecutable.open(java)) {
            assertThat(pinned.sha256()).isEqualTo(digest);
            assertThatThrownBy(() -> pinned.start("0".repeat(64), List.of("--version"), temp))
                    .isInstanceOf(IOException.class);
            Process child = pinned.start(digest, List.of("-Xmx32m", "--version"), temp);
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isZero();
            assertThat(new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).contains(Integer.toString(Runtime.version().feature()) + ".");
        }
    }

    @Test void pinsBytesWhenTheOriginalIsUpdatedBetweenVerificationAndLaunch() throws Exception {
        Path install = Files.createDirectory(temp.resolve("local install"));
        Path program = install.resolve(windows ? "hostname.exe" : "version");
        if (windows) {
            Files.copy(Path.of(System.getenv("SystemRoot"), "System32", "hostname.exe"), program);
        } else {
            Files.writeString(program, "#!/bin/sh\nprintf 'version-one'\n");
            assertThat(program.toFile().setExecutable(true)).isTrue();
        }
        String digest = digest(program);
        try (var pinned = PinnedCliExecutable.open(program)) {
            if (windows) {
                assertThatThrownBy(() -> Files.writeString(program, "", StandardOpenOption.WRITE))
                        .isInstanceOf(IOException.class);
                assertThatThrownBy(() -> Files.move(program, install.resolve("retired.exe")))
                        .isInstanceOf(IOException.class);
                assertThatThrownBy(() -> Files.move(install, temp.resolve("retired")))
                        .isInstanceOf(IOException.class);
            } else {
                assertThat(pinned.policy()).isEqualTo("private_verified_image");
                Files.writeString(program, "#!/bin/sh\nprintf 'version-two'\n");
                Files.move(program, install.resolve("old-version"));
                Files.writeString(program, "#!/bin/sh\nprintf 'version-three'\n");
            }
            Process child = pinned.start(digest, List.of(), temp);
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isZero();
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (windows) assertThat(output).isNotEmpty();
            else assertThat(output).isEqualTo("version-one");
        }
        Files.delete(program);
    }

    private static String digest(Path source) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (var bytes = new java.security.DigestInputStream(Files.newInputStream(source), sha)) {
            bytes.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(sha.digest());
    }
}

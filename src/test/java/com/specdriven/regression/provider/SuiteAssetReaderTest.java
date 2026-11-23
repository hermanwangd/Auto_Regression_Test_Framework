package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SuiteAssetReaderTest {
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void nativeReaderRequiresRegularFilesAndPhysicalPathComponents(@TempDir Path directory) throws Exception {
        Path root = directory.toRealPath();
        Path folder = Files.createDirectory(root.resolve("queries"));
        Path file = Files.writeString(folder.resolve("語句.sql"), "SELECT '資料'");
        try (var stream = PosixSuiteFile.open(file)) {
            assertThat(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("SELECT '資料'");
        }
        assertThatThrownBy(() -> PosixSuiteFile.open(folder)).isInstanceOf(java.io.IOException.class);

        Path alias = Files.createSymbolicLink(root.resolve("alias"), folder);
        assertThatThrownBy(() -> PosixSuiteFile.open(alias.resolve(file.getFileName())))
                .isInstanceOf(java.io.IOException.class);
        Path fileAlias = Files.createSymbolicLink(root.resolve("query-link.sql"), file);
        assertThatThrownBy(() -> PosixSuiteFile.open(fileAlias)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void readsTheOriginalBytesOfANativeFilename(@TempDir Path directory) throws Exception {
        Path root = directory.toRealPath();
        Path file = Path.of(root.toUri().resolve(URI.create("query-%25-%FF.sql")));
        Files.writeString(file, "SELECT 1");
        try (var input = SuiteAssetReader.openPhysical(file)) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("SELECT 1");
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void linuxReaderRejectsAnOpenedDevice() {
        assertThatThrownBy(() -> PosixSuiteFile.open(Path.of("/dev/null")))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("not a regular file");
    }

    @Test
    void acceptsOnlyFilesWhoseResolvedLocationBelongsToTheSuite(@TempDir Path directory) throws Exception {
        Path suite = Files.createDirectory(directory.resolve("suite"));
        Path inside = Files.writeString(suite.resolve("query.sql"), "SELECT 1");
        Files.createSymbolicLink(suite.resolve("alias.sql"), inside);
        assertThat(SuiteAssetReader.read(suite, "alias.sql", "outside suite")).isEqualTo("SELECT 1");

        Path sibling = Files.createDirectory(directory.resolve("other-suite"));
        Files.writeString(sibling.resolve("query.sql"), "SELECT 2");
        Files.createSymbolicLink(suite.resolve("sibling"), sibling);
        assertThatThrownBy(() -> SuiteAssetReader.read(suite, "sibling/query.sql", "outside suite"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("outside suite");
    }
}

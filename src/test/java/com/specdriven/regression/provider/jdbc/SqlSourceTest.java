package com.specdriven.regression.provider.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SqlSourceTest {
    @Test
    @EnabledOnOs(OS.MAC)
    void darwinReaderRequiresRegularFilesAndPhysicalPathComponents(@TempDir Path directory) throws Exception {
        Path root = directory.toRealPath();
        Path folder = Files.createDirectory(root.resolve("queries"));
        Path file = Files.writeString(folder.resolve("語句.sql"), "SELECT '資料'");
        assertThat(DarwinSqlFile.read(file)).isEqualTo("SELECT '資料'");
        assertThatThrownBy(() -> DarwinSqlFile.read(folder)).isInstanceOf(java.io.IOException.class);

        Path alias = Files.createSymbolicLink(root.resolve("alias"), folder);
        assertThatThrownBy(() -> DarwinSqlFile.read(alias.resolve(file.getFileName())))
                .isInstanceOf(java.io.IOException.class);
        Path fileAlias = Files.createSymbolicLink(root.resolve("query-link.sql"), file);
        assertThatThrownBy(() -> DarwinSqlFile.read(fileAlias)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void acceptsOnlyFilesWhoseResolvedLocationBelongsToTheSuite(@TempDir Path directory) throws Exception {
        Path suite = Files.createDirectory(directory.resolve("suite"));
        Path inside = Files.writeString(suite.resolve("query.sql"), "SELECT 1");
        Files.createSymbolicLink(suite.resolve("alias.sql"), inside);
        assertThat(SqlSource.read(suite, "alias.sql", "outside suite")).isEqualTo("SELECT 1");

        Path sibling = Files.createDirectory(directory.resolve("other-suite"));
        Files.writeString(sibling.resolve("query.sql"), "SELECT 2");
        Files.createSymbolicLink(suite.resolve("sibling"), sibling);
        assertThatThrownBy(() -> SqlSource.read(suite, "sibling/query.sql", "outside suite"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("outside suite");
    }
}

package com.specdriven.regression.provider.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlSourceTest {
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

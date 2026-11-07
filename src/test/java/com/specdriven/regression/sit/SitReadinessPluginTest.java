package com.specdriven.regression.sit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SitReadinessPluginTest {
    @TempDir Path temp;

    @Test
    void independentlyBuiltServiceJarsCanReplaceAnAdapterWithoutFrameworkChanges() throws Exception {
        for (String state : List.of("READY", "MISSING")) {
            Path pluginJar = buildPluginJar(state);
            try (var loader = new URLClassLoader(new java.net.URL[] {pluginJar.toUri().toURL()},
                    SitReadinessAdapter.class.getClassLoader())) {
                var registry = SitReadinessRegistry.discover(loader);
                var report = new SitReadinessService(registry).evaluate(
                        SitReadinessServiceTest.config("custom", 500), true);
                assertThat(report.state()).isEqualTo(SitReadinessState.valueOf(state));
                assertThat(report.checks().get(0).adapterVersion()).isEqualTo("jar-" + state);
                assertThat(report.checks().get(0).evidence()).containsEntry("source", "external test JAR");
            }
        }
    }

    private Path buildPluginJar(String state) throws Exception {
        Path root = Files.createDirectory(temp.resolve(state));
        Path source = root.resolve("ReplacementAdapter.java");
        Files.writeString(source, """
                package testplugin;
                import com.specdriven.regression.sit.*;
                import java.time.Instant;
                import java.util.Map;
                public final class ReplacementAdapter implements SitReadinessAdapter {
                    public String id() { return "custom"; }
                    public String version() { return "jar-%s"; }
                    public boolean simulated() { return true; }
                    public SitReadinessObservation check(SitReadinessRequest request) {
                        return new SitReadinessObservation(SitReadinessState.%s, "v1", Instant.now(),
                            Map.of("source", "external test JAR"), "Restore the fixture dataset.");
                    }
                }
                """.formatted(state, state));
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17",
                "-classpath", System.getProperty("java.class.path"), "-d", root.toString(), source.toString());
        assertThat(exit).isZero();
        Path jar = root.resolve("replacement.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("testplugin/ReplacementAdapter.class"));
            output.write(Files.readAllBytes(root.resolve("testplugin/ReplacementAdapter.class")));
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/services/" + SitReadinessAdapter.class.getName()));
            output.write("testplugin.ReplacementAdapter\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }
}

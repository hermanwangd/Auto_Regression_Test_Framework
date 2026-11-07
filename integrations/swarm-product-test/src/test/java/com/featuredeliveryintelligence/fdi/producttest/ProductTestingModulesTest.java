package com.featuredeliveryintelligence.fdi.producttest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductTestingModulesTest {
    private static final String SERVICE = "META-INF/services/" + ProductTestingModuleProvider.class.getName();
    @TempDir Path root;

    @Test void absentModuleFailsClosed() throws Exception {
        try (var loader = isolated()) {
            assertThatThrownBy(() -> ProductTestingModules.resolve("product-test", "1", r -> null, loader))
                    .hasMessageContaining("not installed");
        }
    }
    @Test void incompatibleVersionFailsClosed() {
        assertThatThrownBy(() -> ProductTestingModules.resolve("product-test", "2", r -> null, getClass().getClassLoader()))
                .hasMessageContaining("version");
    }
    @Test void unknownIdFailsClosed() {
        assertThatThrownBy(() -> ProductTestingModules.resolve("unknown", "1", r -> null, getClass().getClassLoader()))
                .hasMessageContaining("not installed");
    }
    @Test void independentlyCompiledReplacementJarReceivesInjectedPort() throws Exception {
        var jar = providerJar("ReplacementProvider"); var calls = new AtomicInteger();
        try (var loader = isolated(jar.toUri().toURL())) {
            var module = ProductTestingModules.resolve("product-test", "1", r -> { calls.incrementAndGet(); return null; }, loader);
            assertThat(module.getClass().getName()).isEqualTo("replacement.ReplacementProvider");
            module.execute(null); assertThat(calls.get()).isEqualTo(1);
        }
    }
    @Test void duplicateProviderJarsFailClosed() throws Exception {
        var first = providerJar("FirstProvider"); var second = providerJar("SecondProvider");
        try (var loader = isolated(first.toUri().toURL(), second.toUri().toURL())) {
            assertThatThrownBy(() -> ProductTestingModules.resolve("product-test", "1", r -> null, loader))
                    .hasMessageContaining("Duplicate");
        }
    }
    private URLClassLoader isolated(URL... urls) {
        return new URLClassLoader(urls, getClass().getClassLoader()) {
            @Override public Enumeration<URL> getResources(String name) throws java.io.IOException {
                return SERVICE.equals(name) ? findResources(name) : super.getResources(name);
            }
        };
    }
    private Path providerJar(String name) throws Exception {
        Path output = Files.createDirectories(root.resolve(name)); Path source = output.resolve(name + ".java");
        Files.writeString(source, """
                package replacement;
                import com.featuredeliveryintelligence.fdi.producttest.*;
                public final class %s implements ProductTestingModuleProvider, ProductTestingModule {
                  private FrameworkExecutionPort port;
                  public String id() { return "product-test"; }
                  public String version() { return "1"; }
                  public ProductTestingModule create(FrameworkExecutionPort port) { this.port = port; return this; }
                  public ProductTestReceipt execute(ProductTestRequest request) { return port.execute(request); }
                }
                """.formatted(name));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", System.getProperty("java.class.path"),
                "-d", output.toString(), source.toString())).isZero();
        Path jar = root.resolve(name + ".jar");
        try (var stream = new JarOutputStream(Files.newOutputStream(jar))) {
            stream.putNextEntry(new JarEntry("replacement/" + name + ".class"));
            stream.write(Files.readAllBytes(output.resolve("replacement/" + name + ".class"))); stream.closeEntry();
            stream.putNextEntry(new JarEntry(SERVICE)); stream.write(("replacement." + name + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); stream.closeEntry();
        }
        return jar;
    }
}

package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class NoIbmMqRegistryTest {
    public static final String NO_MQ = "schema=runner-build.v1\nvariant=no-ibm-mq\nibm_mq=unsupported\n";
    public static final String RESOURCE = "META-INF/regression/runner-build.properties";

    @Test void omittedAdapterConstructsWithoutSdkAndDeclaresUnsupported() throws Exception {
        try (NoMqLoader loader = new NoMqLoader(NO_MQ)) {
            Object adapter = null; Throwable failure = null;
            try { adapter = loader.loadClass("com.specdriven.regression.contract.v03.adapter.IbmMqV03Adapter")
                    .getConstructor().newInstance(); } catch (Throwable error) { failure = error; }
            assertThat(failure).as("omitted MQ must not fail during startup/class linkage").isNull();
            assertThat((Boolean) adapter.getClass().getMethod("supports", String.class, String.class)
                    .invoke(adapter, "ibm_mq.v0.3", "mq_put")).isFalse();
            assertThat(loader.vendorLoads).isEmpty();
        }
    }

    @Test void v03RejectsAnInjectedMqAdapterBeforeExecute() throws Exception {
        try (NoMqLoader loader = new NoMqLoader(NO_MQ)) {
            AtomicInteger operations = new AtomicInteger();
            Class<?> type = loader.loadClass("com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter");
            Object adapter = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getName().equals("providerType")) return "ibm_mq";
                if (method.getName().equals("supports")) return true;
                if (method.getName().equals("execute")) { operations.incrementAndGet(); return null; }
                return null;
            });
            Class<?> registryType = loader.loadClass("com.specdriven.regression.contract.v03.V03ProviderRuntimeRegistry");
            Object registry = registryType.getConstructor(List.class).newInstance(List.of(adapter));
            Throwable failure = null;
            try { registryType.getMethod("resolve", String.class).invoke(registry, "ibm_mq"); }
            catch (InvocationTargetException error) { failure = error.getCause(); }
            assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("IBM MQ UNSUPPORTED");
            assertThat(operations).hasValue(0);
            assertThat(loader.vendorLoads).isEmpty();
        }
    }

    @Test void genericRegistryRefusesOmittedMqEvenWhenAnAdapterWasSupplied() throws Exception {
        try (NoMqLoader loader = new NoMqLoader(NO_MQ)) {
            Class<?> runtime = loader.loadClass("com.specdriven.regression.provider.runtime.ProviderRuntime");
            AtomicInteger operations = new AtomicInteger();
            Object adapter = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{runtime}, (p, m, a) -> {
                operations.incrementAndGet(); return null;
            });
            Class<?> registryType = loader.loadClass("com.specdriven.regression.provider.runtime.ProviderRuntimeRegistry");
            Object registry = registryType.getConstructor(Map.class).newInstance(Map.of("ibm_mq", adapter));
            Object resolution = registryType.getMethod("tryResolve", String.class).invoke(registry, "IBM_MQ");
            assertThat((Boolean)resolution.getClass().getMethod("valid").invoke(resolution)).isFalse();
            assertThat(resolution.getClass().getMethod("failure").invoke(resolution).toString())
                    .contains("IBM MQ UNSUPPORTED");
            assertThat(operations).hasValue(0);
            assertThat(loader.vendorLoads).isEmpty();
        }
    }

    /** Synthetic descriptor/classloading fixture; never a provider or broker. */
    public static final class NoMqLoader extends URLClassLoader {
        public final List<String> vendorLoads = new ArrayList<>();
        private final String descriptor;
        private final boolean duplicate;
        public NoMqLoader(String descriptor) throws IOException { this(descriptor, false); }
        public NoMqLoader(String descriptor, boolean duplicate) throws IOException {
            super(new URL[]{Path.of(System.getProperty("test.framework.classes", "target/classes"))
                    .toAbsolutePath().toUri().toURL()}, NoIbmMqRegistryTest.class.getClassLoader());
            this.descriptor = descriptor; this.duplicate = duplicate;
        }
        private URL descriptorUrl() throws MalformedURLException {
            return new URL(null, "memory:runner-build", new URLStreamHandler() {
                protected URLConnection openConnection(URL url) {
                    return new URLConnection(url) {
                        public void connect() { }
                        public InputStream getInputStream() {
                            return new ByteArrayInputStream(descriptor.getBytes(StandardCharsets.UTF_8));
                        }
                    };
                }
            });
        }
        @Override public URL getResource(String name) {
            if (name.startsWith("com/ibm/mq/")) return null; // simulate SDK physically absent, not classloading failure
            if (!RESOURCE.equals(name)) return super.getResource(name);
            if (descriptor == null) return null;
            try { return descriptorUrl(); } catch (MalformedURLException e) { throw new IllegalStateException(e); }
        }
        @Override public Enumeration<URL> getResources(String name) throws IOException {
            if (!RESOURCE.equals(name)) return super.getResources(name);
            if (descriptor == null) return Collections.emptyEnumeration();
            URL url = descriptorUrl();
            return Collections.enumeration(duplicate ? List.of(url, url) : List.of(url));
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.startsWith("com.ibm.mq.")) {
                    vendorLoads.add(name); throw new ClassNotFoundException("SDK denied by the test fixture");
                }
                if (name.startsWith("com.specdriven.regression.")) {
                    Class<?> loaded = findLoadedClass(name);
                    // No unrestricted parent fallback for framework classes.
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        }
    }
}

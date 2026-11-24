package com.specdriven.regression.testsupport;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/** Starts an owned fixture on the same JVM image as this test process. */
public final class TestJvm {
    private TestJvm() { }

    private static ProcessBuilder currentJvm(List<String> vmOptions) throws IOException {
        String command = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IOException("Cannot identify the running test JVM"));
        Path image = Path.of(command).toRealPath();
        String name = File.separatorChar == '\\' ? "java.exe" : "java";
        if (!image.getFileName().toString().equalsIgnoreCase(name)
                || !Files.isRegularFile(image) || !Files.isExecutable(image))
            throw new IOException("Test worker must run on a Java launcher");
        ProcessBuilder builder = new ProcessBuilder();
        builder.command().add(image.toString());
        builder.command().addAll(vmOptions);
        for (String key : List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"))
            builder.environment().remove(key);
        return builder;
    }

    /** Callers supply test-owned VM options and a class literal. Only application data follows the main class. */
    public static ProcessBuilder forMain(Class<?> main, List<String> vmOptions, String... arguments) throws IOException {
        ProcessBuilder builder = currentJvm(vmOptions);
        // Keep the observed nonce visible even on systems with limited process argument readback.
        // CLASSPATH carries paths literally, without interpreting quotes or JVM argument files.
        StringJoiner entries = new StringJoiner(File.pathSeparator);
        String classpath = ManagementFactory.getRuntimeMXBean().getClassPath();
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator), -1)) {
            Path path = Path.of(entry).toRealPath();
            if (!(Files.isDirectory(path) || Files.isRegularFile(path)))
                throw new IOException("Invalid fixture classpath entry");
            entries.add(path.toString());
        }
        builder.environment().put("CLASSPATH", entries.toString());
        builder.command().add(main.getName());
        builder.command().addAll(List.of(arguments));
        return builder;
    }

    public static Path packagedJar() throws IOException {
        Path root = Path.of("").toRealPath();
        Path jar = root.resolve("target/spec-driven-auto-regression-0.3.2.jar");
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || !jar.equals(jar.toRealPath()))
            throw new IOException("Expected the packaged framework JAR under this build's target directory");
        return jar;
    }

    public static ProcessBuilder forPackagedJar(List<String> vmOptions, String... arguments) throws IOException {
        ProcessBuilder builder = currentJvm(vmOptions);
        builder.command().addAll(List.of("-jar", packagedJar().toString()));
        builder.command().addAll(List.of(arguments));
        return builder;
    }
}

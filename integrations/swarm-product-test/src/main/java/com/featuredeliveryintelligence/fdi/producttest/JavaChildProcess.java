package com.featuredeliveryintelligence.fdi.producttest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Starts a command with the Java launcher from an operator-selected runtime image. */
public final class JavaChildProcess {
    private JavaChildProcess() {}

    public static ProcessBuilder fromRuntime(Path configuredJava) throws IOException {
        Path candidate = configuredJava;
        if (java.io.File.separatorChar == '\\' && candidate.endsWith("java") && !Files.exists(candidate))
            candidate = candidate.resolveSibling("java.exe");
        Path launcher = candidate.toRealPath();
        String name = launcher.getFileName().toString().toLowerCase(Locale.ROOT);
        Path bin = launcher.getParent();
        boolean runtimeImage = bin != null && bin.endsWith("bin") && bin.getParent() != null
                && Files.isRegularFile(bin.getParent().resolve("lib/modules"));
        // macOS provides a system launcher that selects the installed runtime.
        boolean systemLauncher = java.io.File.separatorChar == '/' && Files.exists(Path.of("/usr/bin/java"))
                && Files.isSameFile(launcher, Path.of("/usr/bin/java"));
        if (!(name.equals("java") || name.equals("java.exe")) || !Files.isRegularFile(launcher)
                || !Files.isExecutable(launcher) || !(runtimeImage || systemLauncher))
            throw new IOException("Configured executable must be a Java runtime launcher");
        var builder = new ProcessBuilder();
        builder.command().add(launcher.toString());
        // Embedding hosts and harnesses must opt in to each child VM option at the call site.
        for (String key : new String[]{"JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS"})
            builder.environment().remove(key);
        return builder;
    }
}

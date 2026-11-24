package com.specdriven.regression.testsupport;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** The local Git operations used to construct synthetic SIT repositories. */
public final class FixtureGit {
    private FixtureGit() { }

    public static void init(Path repo) throws Exception { run(repo, "init", "--template=", "-q"); }
    public static void add(Path repo, String path) throws Exception {
        run(repo, "add", "--", fixturePath(path));
    }
    public static void commit(Path repo, String message) throws Exception {
        run(repo, "commit", "-qm", message);
    }
    public static String head(Path repo) throws Exception {
        return objectId(run(repo, "rev-parse", "HEAD"));
    }
    public static boolean hasStagedChanges(Path repo) throws Exception {
        return !run(repo, "diff", "--cached", "--name-only").isBlank();
    }
    public static String orphanCommit(Path repo) throws Exception {
        String tree = objectId(run(repo, "rev-parse", "HEAD^{tree}"));
        return objectId(run(repo, "commit-tree", tree, "-m", "unrelated source commit"));
    }
    public static void indexAsSymlink(Path repo, String path) throws Exception {
        String relative = fixturePath(path);
        String blob = objectId(run(repo, "hash-object", "-w", "--", relative));
        run(repo, "update-index", "--cacheinfo", "120000," + blob + "," + relative);
    }
    public static void indexAsExecutable(Path repo, String path) throws Exception {
        run(repo, "update-index", "--chmod=+x", "--", fixturePath(path));
    }

    private static String objectId(String id) throws IOException {
        if (!id.matches("[0-9a-f]{40}")) throw new IOException("Expected a fixture SHA-1 object ID");
        return id;
    }

    private static String fixturePath(String path) {
        if (!Set.of(".", "tests/sit/cases/same.yaml", "tests/sit/data", "tests/sit/dependencies.lock").contains(path))
            throw new IllegalArgumentException("Unknown SIT fixture path");
        return path;
    }

    private static String run(Path repo, String... arguments) throws Exception {
        boolean windows = File.separatorChar == '\\';
        ProcessBuilder builder = windows
                ? new ProcessBuilder("C:\\Program Files\\Git\\cmd\\git.exe") : new ProcessBuilder("/usr/bin/git");
        builder.directory(repo.toRealPath().toFile());
        String nullFile = windows ? "NUL" : "/dev/null";
        builder.command().addAll(List.of("-c", "core.hooksPath=" + nullFile, "-c", "commit.gpgSign=false",
                "-c", "user.name=Public Fixture", "-c", "user.email=fixture@example.invalid"));
        builder.command().addAll(List.of(arguments));
        builder.environment().clear();
        if (windows) builder.environment().put("SystemRoot", System.getenv("SystemRoot"));
        else builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().putAll(Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", nullFile,
                "GIT_OPTIONAL_LOCKS", "0", "GIT_TERMINAL_PROMPT", "0", "GIT_LITERAL_PATHSPECS", "1",
                "GIT_AUTHOR_DATE", "2026-01-01T00:00:00Z", "GIT_COMMITTER_DATE", "2026-01-01T00:00:00Z"));
        Process child = builder.start();
        try {
            if (!child.waitFor(10, TimeUnit.SECONDS)) throw new IOException("Fixture Git timed out");
            byte[] output = child.getInputStream().readNBytes(8193);
            if (output.length > 8192) throw new IOException("Fixture Git exceeded its output bound");
            String text = new String(output, StandardCharsets.UTF_8).strip();
            if (child.exitValue() != 0) {
                String error = new String(child.getErrorStream().readNBytes(8192), StandardCharsets.UTF_8);
                throw new IOException("Fixture Git failed: " + error + text);
            }
            return text;
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                if (!child.waitFor(2, TimeUnit.SECONDS)) throw new IOException("Fixture Git did not terminate");
            }
        }
    }
}

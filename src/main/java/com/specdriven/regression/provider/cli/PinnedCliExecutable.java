package com.specdriven.regression.provider.cli;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Holds the verified launch image until child and stream cleanup is complete. */
final class PinnedCliExecutable implements AutoCloseable {
    private final Path image;
    private final Path staging;
    private final FileChannel heldFile;
    private final String sha256;
    private final String policy;

    private PinnedCliExecutable(Path image, Path staging, FileChannel heldFile, String sha256, String policy) {
        this.image = image;
        this.staging = staging;
        this.heldFile = heldFile;
        this.sha256 = sha256;
        this.policy = policy;
    }

    static PinnedCliExecutable open(Path source) throws IOException {
        if (source.getFileSystem().getSeparator().equals("\\")) {
            if (!source.getFileSystem().getPathMatcher("glob:*.exe").matches(source.getFileName()))
                throw new IOException("Windows CLI bindings require a native .exe image");
            // Deny writes and replacement while retaining the installed DLL search directory.
            FileChannel lock = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                    ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE);
            return fromHeldFile(source, lock, "deny_write_and_delete");
        }
        // macOS verifies sealed system tools in place. A copied image can be killed by the OS.
        if ("Mac OS X".equals(System.getProperty("os.name")) && Files.getFileStore(source).isReadOnly()
                && (source.startsWith("/usr/bin") || source.startsWith("/bin")
                    || source.startsWith("/usr/sbin") || source.startsWith("/sbin"))) {
            return fromHeldFile(source, FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                    "sealed_system_volume");
        }
        Path staging = Files.createTempDirectory("reviewed-cli-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path image = staging.resolve("bin").resolve(source.getFileName());
        try {
            Files.createDirectory(image.getParent());
            String hash;
            try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                    OutputStream out = Files.newOutputStream(image, StandardOpenOption.CREATE_NEW)) {
                hash = hashAndCopy(in, out);
            }
            Files.setPosixFilePermissions(image, PosixFilePermissions.fromString("r-x------"));
            // The JDK launcher looks up libjli relative to bin/java. Use the running JDK's libraries.
            Path javaHome = Path.of(System.getProperty("java.home")).toRealPath();
            if (source.getFileName().toString().equals("java") && Files.isSameFile(source, javaHome.resolve("bin/java"))) {
                Files.createSymbolicLink(staging.resolve("lib"), javaHome.resolve("lib"));
                Files.createSymbolicLink(staging.resolve("conf"), javaHome.resolve("conf"));
            }
            return new PinnedCliExecutable(image, staging, null, hash, "private_verified_image");
        } catch (IOException | RuntimeException error) {
            removeStaging(staging, image);
            throw error;
        }
    }

    private static PinnedCliExecutable fromHeldFile(Path source, FileChannel channel, String policy) throws IOException {
        try {
            String hash = hashAndCopy(Channels.newInputStream(channel), OutputStream.nullOutputStream());
            return new PinnedCliExecutable(source, null, channel, hash, policy);
        } catch (IOException | RuntimeException error) {
            channel.close();
            throw error;
        }
    }

    private static String hashAndCopy(InputStream in, OutputStream out) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new DigestInputStream(in, digest).transferTo(out);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    String sha256() { return sha256; }
    String policy() { return policy; }

    Process start(String expectedHash, List<String> argv, Path cwd) throws IOException {
        if (!sha256.equals(expectedHash)) throw new IOException("unapproved executable image");
        ProcessBuilder builder = new ProcessBuilder();
        builder.command().add(image.toString());
        builder.command().addAll(argv);
        builder.directory(cwd.toFile());
        builder.environment().clear();
        builder.environment().put("LANG", "C");
        builder.environment().put("LC_ALL", "C");
        return builder.start();
    }

    @Override public void close() throws IOException {
        if (heldFile != null) heldFile.close();
        else removeStaging(staging, image);
    }

    private static void removeStaging(Path staging, Path image) throws IOException {
        Files.deleteIfExists(image);
        Files.deleteIfExists(image.getParent());
        Files.deleteIfExists(staging.resolve("lib"));
        Files.deleteIfExists(staging.resolve("conf"));
        Files.deleteIfExists(staging);
    }
}

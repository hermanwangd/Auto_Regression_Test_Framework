package com.featuredeliveryintelligence.fdi.producttest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class LocalFiles {
    private LocalFiles() {}
    static Path real(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path part = absolute; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Symlink path is not approved: " + path);
        return absolute.toRealPath();
    }
    static Path contained(Path root, Path file) throws IOException {
        Path actual = real(file);
        if (!actual.startsWith(root) || actual.equals(root)) throw new IOException("Path escapes approved directory");
        return actual;
    }
    static byte[] read(Path file, long limit) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > limit)
            throw new IOException("Missing or oversized regular file: " + file.getFileName());
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(Math.toIntExact(limit + 1));
            if (bytes.length > limit) throw new IOException("File exceeds size limit");
            return bytes;
        }
    }
    static String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536]; int length;
                while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

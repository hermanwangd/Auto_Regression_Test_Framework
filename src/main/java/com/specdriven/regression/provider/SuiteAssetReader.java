package com.specdriven.regression.provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** Opens assets from the checked-out suite or RP package. */
public final class SuiteAssetReader {
    private static final Set<OpenOption> READ_FILE = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private SuiteAssetReader() { }

    public static String read(Path root, String reference, String outsideRootMessage) throws IOException {
        try (InputStream input = open(root, reference, outsideRootMessage);
                Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder())) {
            StringWriter text = new StringWriter();
            reader.transferTo(text);
            return text.toString();
        }
    }

    /** The returned stream owns the final file descriptor and must be closed by the caller. */
    public static InputStream open(Path root, String reference, String outsideRootMessage) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path candidate = normalizedRoot.resolve(reference).normalize();
        if (!candidate.startsWith(normalizedRoot)) throw new IllegalArgumentException(outsideRootMessage);
        Path realRoot = normalizedRoot.toRealPath();
        Path file = candidate.toRealPath();
        if (!file.startsWith(realRoot)) throw new IllegalArgumentException(outsideRootMessage);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(reference);

        // Anchor traversal at the filesystem root so even the suite root's parents
        // are opened without following links after the canonical paths were checked.
        Path anchor = realRoot.getRoot();
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(anchor)) {
            if (directory instanceof SecureDirectoryStream<Path> secure) {
                return openRelative(secure, anchor.relativize(file));
            }
        }
        if (file.getFileSystem() == FileSystems.getDefault() && com.sun.jna.Platform.isMac()) {
            try {
                return DarwinSuiteFile.open(file);
            } catch (LinkageError unavailable) {
                throw new IOException("Native macOS suite file access is unavailable.", unavailable);
            }
        }
        throw new IOException("The filesystem does not support confined suite file access.");
    }

    // Open each resolved component relative to its parent handle, without following new links.
    private static InputStream openRelative(SecureDirectoryStream<Path> directory, Path relative) throws IOException {
        if (relative.getNameCount() == 1) {
            return Channels.newInputStream(directory.newByteChannel(relative, READ_FILE));
        }
        try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(relative.getName(0), LinkOption.NOFOLLOW_LINKS)) {
            return openRelative(child, relative.subpath(1, relative.getNameCount()));
        }
    }
}

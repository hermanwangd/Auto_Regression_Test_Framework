package com.specdriven.regression.provider;

import com.sun.jna.Library;
import com.sun.jna.Native;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Descriptor-relative suite asset access on macOS, whose JDK lacks SecureDirectoryStream. */
final class DarwinSuiteFile {
    // Darwin <sys/fcntl.h>. All opens are read-only and reject a link in the opened component.
    private static final int O_NONBLOCK = 0x4;
    private static final int O_NOFOLLOW = 0x100;
    private static final int O_DIRECTORY = 0x100000;
    private static final int O_CLOEXEC = 0x1000000;
    private static final int DIRECTORY_FLAGS = O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC;
    private static final LibSystem LIB = Native.load("/usr/lib/libSystem.B.dylib", LibSystem.class,
            Map.of(Library.OPTION_STRING_ENCODING, StandardCharsets.UTF_8.name()));

    private DarwinSuiteFile() { }

    interface LibSystem extends Library {
        int open(String path, int flags);
        int openat(int directory, String component, int flags);
        int close(int descriptor);
    }

    static InputStream open(Path absoluteFile) throws IOException {
        if (!absoluteFile.isAbsolute() || absoluteFile.getNameCount() == 0
                || !absoluteFile.normalize().equals(absoluteFile)) {
            throw new IOException("Expected a canonical absolute suite asset path.");
        }
        int descriptor = checked(LIB.open("/", DIRECTORY_FLAGS));
        try {
            for (int i = 0; i < absoluteFile.getNameCount(); i++) {
                boolean last = i == absoluteFile.getNameCount() - 1;
                int flags = last ? O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC : DIRECTORY_FLAGS;
                int child = checked(LIB.openat(descriptor, absoluteFile.getName(i).toString(), flags));
                LIB.close(descriptor);
                descriptor = child;
            }
            // Darwin /dev/fd/N duplicates our open descriptor. Check and read that
            // same file while N is held; no lookup through the suite tree is repeated.
            Path openedFile = Path.of("/dev/fd", Integer.toString(descriptor));
            if (!Files.isRegularFile(openedFile)) throw new IOException("suite asset is not a regular file.");
            return Files.newInputStream(openedFile);
        } finally {
            LIB.close(descriptor);
        }
    }

    private static int checked(int descriptor) throws IOException {
        if (descriptor < 0) throw new IOException("Cannot securely open suite asset (errno " + Native.getLastError() + ").");
        return descriptor;
    }
}

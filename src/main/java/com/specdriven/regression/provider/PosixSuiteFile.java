package com.specdriven.regression.provider;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HexFormat;

/** Opens a physical path one component at a time, keeping the final inode pinned until the stream is open. */
final class PosixSuiteFile {
    // Values from Darwin sys/fcntl.h and the Linux UAPI asm/fcntl.h for each ABI.
    // Linux uses O_PATH to pin the leaf without opening devices or waiting on a FIFO.
    private record OpenFlags(int directory, int leaf, String descriptors) {
        static OpenFlags linux(int directory, int noFollow, int closeOnExec, int path) {
            int pin = path | noFollow | closeOnExec;
            return new OpenFlags(pin | directory, pin, "/proc/self/fd");
        }
    }

    private static OpenFlags flags() throws IOException {
        if (Platform.isMac()) {
            int noFollowCloseOnExec = 0x100 | 0x1000000;
            return new OpenFlags(noFollowCloseOnExec | 0x100000, noFollowCloseOnExec | 0x4, "/dev/fd");
        }
        if (Platform.isLinux()) {
            if (Platform.isARM() || Platform.isPPC()) return OpenFlags.linux(0x4000, 0x8000, 0x80000, 0x200000);
            if (Platform.isSPARC()) return OpenFlags.linux(0x10000, 0x20000, 0x400000, 0x1000000);
            if (Platform.isIntel() || Platform.ARCH.startsWith("mips") || Platform.ARCH.startsWith("s390")
                    || Platform.ARCH.startsWith("riscv") || Platform.ARCH.startsWith("loongarch")) {
                return OpenFlags.linux(0x10000, 0x20000, 0x80000, 0x200000);
            }
        }
        throw new IOException("Unsupported ABI for confined suite file access.");
    }

    private static final LibSystem LIB = Native.load(Platform.isMac() ? "/usr/lib/libSystem.B.dylib" : Platform.C_LIBRARY_NAME,
            LibSystem.class);

    private PosixSuiteFile() { }

    interface LibSystem extends Library {
        int open(String path, int flags);
        int openat(int directory, byte[] component, int flags);
        int close(int descriptor);
    }

    static InputStream open(Path absoluteFile) throws IOException {
        if (!absoluteFile.isAbsolute() || absoluteFile.getNameCount() == 0
                || !absoluteFile.normalize().equals(absoluteFile)) {
            throw new IOException("Expected a canonical absolute suite asset path.");
        }
        OpenFlags flags = flags();
        // The default Unix provider's file URI preserves the original filename bytes.
        // A Path's display string can replace bytes that its charset cannot decode.
        String[] components = absoluteFile.toUri().getRawPath().substring(1).split("/");
        if (components.length != absoluteFile.getNameCount()) throw new IOException("Invalid physical suite asset path.");
        int descriptor = checked(LIB.open("/", flags.directory()));
        try {
            for (int i = 0; i < components.length; i++) {
                boolean last = i == components.length - 1;
                int openFlags = last ? flags.leaf() : flags.directory();
                int child = checked(LIB.openat(descriptor, nativeComponent(components[i]), openFlags));
                LIB.close(descriptor);
                descriptor = child;
            }
            // The descriptor stays live across both operations. Neither looks up the suite path again.
            Path openedFile = Path.of(flags.descriptors(), Integer.toString(descriptor));
            if (!Files.readAttributes(openedFile, BasicFileAttributes.class).isRegularFile()) {
                throw new IOException("suite asset is not a regular file.");
            }
            return Files.newInputStream(openedFile);
        } finally {
            LIB.close(descriptor);
        }
    }

    private static byte[] nativeComponent(String escaped) throws IOException {
        var bytes = new ByteArrayOutputStream(escaped.length() + 1);
        for (int i = 0; i < escaped.length(); i++) {
            int value = escaped.charAt(i);
            if (value == '%') {
                value = HexFormat.fromHexDigits(escaped, i + 1, i + 3);
                i += 2;
            } else if (value > 127) {
                throw new IOException("Expected ASCII in an escaped file URI.");
            }
            if (value == 0 || value == '/') throw new IOException("Invalid native filename component.");
            bytes.write(value);
        }
        bytes.write(0);
        return bytes.toByteArray();
    }

    private static int checked(int descriptor) throws IOException {
        if (descriptor < 0) throw new IOException("Cannot securely open suite asset (errno " + Native.getLastError() + ").");
        return descriptor;
    }
}

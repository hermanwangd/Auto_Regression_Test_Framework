package com.specdriven.regression.provider.grpc;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrpcDescriptorMaterializerTest {
    @TempDir Path temp;

    @Test
    void materializesBinaryAndEncodedSuiteDescriptors() throws Exception {
        Path suite = Files.createDirectory(temp.resolve("suite"));
        Path out = temp.resolve("generated");
        byte[] descriptor = {0, 7, -1, 10};
        Files.write(suite.resolve("binary.pb"), descriptor);
        Files.writeString(suite.resolve("encoded.pb.b64"), Base64.getEncoder().encodeToString(descriptor));
        Files.createSymbolicLink(suite.resolve("link.pb"), Path.of("binary.pb"));
        for (String name : new String[]{"binary.pb", "link.pb", "encoded.pb.b64"}) {
            Path file = GrpcDescriptorMaterializer.materialize(suite, out, name);
            assertEquals(out.resolve(name.replace(".b64", "")), file);
            assertArrayEquals(descriptor, Files.readAllBytes(file));
        }
    }

    @Test
    void requiresDescriptorInsideSuiteAndPreservesPriorOutputOnDecodeFailure() throws Exception {
        Path suite = Files.createDirectory(temp.resolve("suite"));
        Path out = temp.resolve("generated");
        Path sibling = Files.writeString(temp.resolve("sibling.pb"), "fixture");
        Files.createSymbolicLink(suite.resolve("other.pb"), sibling);
        assertThrows(IllegalArgumentException.class,
                () -> GrpcDescriptorMaterializer.materialize(suite, out, "other.pb"));
        assertFalse(Files.exists(out));
        Files.createDirectory(out);
        Path prior = Files.writeString(out.resolve("example.pb"), "previous");
        Files.writeString(suite.resolve("example.pb.b64"), "Q===");
        assertThrows(IllegalArgumentException.class,
                () -> GrpcDescriptorMaterializer.materialize(suite, out, "example.pb.b64"));
        assertEquals("previous", Files.readString(prior));
        try (var files = Files.list(out)) {
            assertEquals(1L, files.count());
        }
    }
}

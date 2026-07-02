package com.specdriven.regression.provider.grpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

final class GrpcDescriptorMaterializer {

    private GrpcDescriptorMaterializer() {
    }

    static Path materialize(Path suiteRoot, Path outputDir, String descriptorRef) {
        if (descriptorRef == null || descriptorRef.isBlank()) {
            throw new IllegalArgumentException("gRPC descriptor_ref is required.");
        }
        Path source = suiteRoot.resolve(descriptorRef).normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("gRPC descriptor_ref does not exist: " + descriptorRef);
        }
        try {
            Files.createDirectories(outputDir);
            String fileName = source.getFileName().toString();
            if (fileName.endsWith(".b64")) {
                String decodedName = fileName.substring(0, fileName.length() - ".b64".length());
                Path target = outputDir.resolve(decodedName).normalize();
                byte[] decoded = Base64.getMimeDecoder().decode(Files.readString(source));
                Files.write(target, decoded);
                return target;
            }
            Path target = outputDir.resolve(fileName).normalize();
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to materialize gRPC descriptor: " + descriptorRef, e);
        }
    }
}

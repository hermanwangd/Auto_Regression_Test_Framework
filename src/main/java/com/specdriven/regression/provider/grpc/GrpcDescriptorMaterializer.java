package com.specdriven.regression.provider.grpc;

import com.specdriven.regression.provider.SuiteAssetReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

final class GrpcDescriptorMaterializer {

    private GrpcDescriptorMaterializer() {
    }

    static Path materialize(Path suiteRoot, Path outputDir, String descriptorRef) {
        if (descriptorRef == null || descriptorRef.isBlank()) {
            throw new IllegalArgumentException("gRPC descriptor_ref is required.");
        }
        Path basename = Path.of(descriptorRef).getFileName();
        String fileName = basename == null ? "" : basename.toString();
        boolean encoded = fileName.endsWith(".b64");
        String targetName = encoded ? fileName.substring(0, fileName.length() - 4) : fileName;
        if (targetName.isBlank() || ".".equals(targetName) || "..".equals(targetName)) {
            throw new IllegalArgumentException("gRPC descriptor_ref must name a descriptor file.");
        }
        String scopeError = "gRPC descriptor_ref must stay under the suite root.";
        try (InputStream input = SuiteAssetReader.open(suiteRoot, descriptorRef, scopeError)) {
            Files.createDirectories(outputDir);
            Path target = outputDir.resolve(targetName);
            Path temporary = Files.createTempFile(outputDir, "descriptor-", ".tmp");
            try {
                if (encoded) {
                    Files.write(temporary, Base64.getMimeDecoder().decode(input.readAllBytes()));
                } else {
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                return Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("gRPC descriptor_ref does not exist: " + descriptorRef, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to materialize gRPC descriptor.", e);
        }
    }
}

package com.featuredeliveryintelligence.fdi.producttest;

import java.nio.file.Path;
import java.time.Duration;

/** Independently provisioned deployment binding; never loaded from mission text or the request. */
public record FrameworkBinding(Path javaExecutable, Path frameworkJar, String frameworkSha256, String approvedHandoffSha256,
                               Path approvedArtifactRoot, Path outputParent, Duration totalTimeout, int maximumOutputBytes) {
    public FrameworkBinding {
        if (frameworkSha256 == null || !frameworkSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("A pinned framework SHA-256 is required");
        if (approvedHandoffSha256 == null || !approvedHandoffSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("An independently approved handoff SHA-256 is required");
        if (totalTimeout == null || totalTimeout.isZero() || totalTimeout.isNegative() || totalTimeout.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("Total timeout must be positive and at most 120 seconds");
        if (maximumOutputBytes < 1024 || maximumOutputBytes > 1024 * 1024)
            throw new IllegalArgumentException("Output limit must be between 1024 and 1048576 bytes");
    }
}

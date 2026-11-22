package com.specdriven.regression.summary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Parent-issued identity and output boundary shared by all suites in one execution batch. */
public record SuiteExecutionContext(
        String parentBatchId,
        String profile,
        Instant startTime,
        Path outputRoot,
        boolean standaloneInvocation) {

    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final String STANDALONE_UNSPECIFIED_PROFILE = "__standalone_unspecified_profile__";

    public SuiteExecutionContext {
        parentBatchId = requireText(parentBatchId, "parentBatchId");
        profile = requireText(profile, "profile");
        startTime = Objects.requireNonNull(startTime, "startTime");
        outputRoot = canonicalOutputRoot(Objects.requireNonNull(outputRoot, "outputRoot"));
    }

    public SuiteExecutionContext(String parentBatchId, String profile, Instant startTime, Path outputRoot) {
        this(parentBatchId, profile, startTime, outputRoot, false);
    }

    public static SuiteExecutionContext standalone(String profile, Path outputRoot) {
        return standalone(profile, outputRoot, "STANDALONE");
    }

    public static SuiteExecutionContext standalone(String profile, Path outputRoot, String runtimeLabel) {
        String suffix = ID_TIME.format(Instant.now()) + "-" + SEQUENCE.incrementAndGet();
        return new SuiteExecutionContext(
                "BATCH-" + safeSegment(runtimeLabel).toUpperCase(Locale.ROOT) + "-" + suffix,
                standaloneProfile(profile),
                Instant.now(),
                outputRoot,
                true);
    }

    public static SuiteExecutionContext standaloneWithBatchId(String profile, Path outputRoot, String batchId) {
        return new SuiteExecutionContext(batchId, standaloneProfile(profile), Instant.now(), outputRoot, true);
    }

    public boolean matchesRequestedProfile(String requestedProfile) {
        return STANDALONE_UNSPECIFIED_PROFILE.equals(profile)
                ? requestedProfile == null || requestedProfile.isBlank()
                : profile.equals(requestedProfile);
    }

    public SuiteExecutionContext withOutputRoot(Path childOutputRoot) {
        return new SuiteExecutionContext(parentBatchId, profile, startTime, childOutputRoot, standaloneInvocation);
    }

    public SuiteExecutionContext forChildProfile(String childProfile) {
        return new SuiteExecutionContext(parentBatchId, childProfile, startTime, outputRoot, false);
    }

    public String newRunId(String runtimeLabel) {
        String label = safeSegment(runtimeLabel).toUpperCase(Locale.ROOT);
        return "RUN-" + label + "-" + UUID.randomUUID();
    }

    public Path childRunRoot(String suiteId, String runId) {
        Path candidate = outputRoot
                .resolve(safeSegment(suiteId))
                .resolve(safeSegment(parentBatchId))
                .resolve(safeSegment(runId))
                .normalize();
        if (!candidate.startsWith(outputRoot)) {
            throw new IllegalArgumentException("Child run root escapes supplied output root: " + candidate);
        }
        Path existing = candidate;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        try {
            if (existing == null || !existing.toRealPath().startsWith(outputRoot)) {
                throw new IllegalArgumentException("Child run root escapes supplied output root through a symbolic link: " + candidate);
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot validate child run root containment: " + candidate, error);
        }
        return candidate;
    }

    private static String standaloneProfile(String profile) {
        return profile == null || profile.isBlank() ? STANDALONE_UNSPECIFIED_PROFILE : profile;
    }

    private static Path canonicalOutputRoot(Path outputRoot) {
        try {
            Files.createDirectories(outputRoot.toAbsolutePath().normalize());
            return outputRoot.toAbsolutePath().normalize().toRealPath();
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot establish execution output root: " + outputRoot, error);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String safeSegment(String value) {
        String text = requireText(value, "path segment");
        String safe = text.replaceAll("[^A-Za-z0-9._-]", "-");
        if (safe.equals(".") || safe.equals("..") || safe.isBlank()) {
            throw new IllegalArgumentException("Unsafe child run path segment: " + value);
        }
        return safe;
    }
}

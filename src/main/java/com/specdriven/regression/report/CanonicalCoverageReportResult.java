package com.specdriven.regression.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

/** External derived artifacts; never a replacement for the canonical test verdict. */
public record CanonicalCoverageReportResult(Path json, Path markdown, String status, JsonNode document) {
    public String jsonSha256() { return digest(json); }
    public String markdownSha256() { return digest(markdown); }
    private static String digest(Path path) {
        try (var input = java.nio.file.Files.newInputStream(path)) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) { throw new IllegalStateException("Coverage artifact digest failed", e); }
    }
    public boolean valid() { return !"INVALID".equals(status); }
}

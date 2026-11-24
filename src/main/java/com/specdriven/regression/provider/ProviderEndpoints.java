package com.specdriven.regression.provider;

import java.net.URI;
import java.net.URISyntaxException;
import org.yaml.snakeyaml.Yaml;

/** Validates endpoint syntax without resolving DNS or including credentials in errors. */
public final class ProviderEndpoints {
    private ProviderEndpoints() { }

    public static boolean localHost(String host) {
        return "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    }

    static String yamlEvidenceRef(String safeReference) {
        return safeReference.isEmpty() ? "" : new Yaml().dump(safeReference).stripTrailing();
    }

    static String httpEvidenceRef(String reference) {
        if (reference.isEmpty() || reference.matches("env://[A-Za-z_][A-Za-z0-9_]*")) return reference;
        try {
            URI uri = new URI(reference);
            if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null) {
                return reference;
            }
        } catch (URISyntaxException ignored) {
            // A rejected endpoint must not echo credential material into evidence.
        }
        return "[MASKED]";
    }

    static String natsConnectionEvidenceRef(String reference) {
        if (reference.isEmpty() || reference.matches("env://[A-Za-z_][A-Za-z0-9_]*")
                || reference.matches("mock://[A-Za-z0-9_.-]+")) {
            return reference;
        }
        try {
            nats(reference, reference, true);
            return reference;
        } catch (IllegalArgumentException ignored) {
            return "[MASKED]";
        }
    }

    static String natsTargetEvidenceRef(String reference, String field) {
        if ("endpoint_ref".equals(field)) return natsConnectionEvidenceRef(reference);
        if (reference.isEmpty() || reference.matches("(?:(?:nats|mock)://)?[A-Za-z0-9_.*>$-]+")) {
            return reference;
        }
        return "[MASKED]";
    }

    public static URI nats(String value, String reference, boolean compatibility) {
        try {
            String address = compatibility && !value.contains("://") ? "nats://" + value : value;
            URI uri = new URI(address);
            boolean local = localHost(uri.getHost());
            String userInfo = uri.getUserInfo();
            if (userInfo != null && (!local || compatibility || !reference.startsWith("env://")
                    || userInfo.codePoints().anyMatch(Character::isISOControl))) {
                throw new IllegalArgumentException("NATS credentials require a local environment-backed endpoint.");
            }
            if (!"nats".equals(uri.getScheme()) || uri.getHost() == null
                    || !local
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || (uri.getPort() < 0 && (!compatibility || uri.getRawAuthority().endsWith(":")))
                    || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("NATS requires a numeric loopback endpoint. For a remote broker, configure a secure local tunnel.");
            }
            return uri;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Invalid NATS endpoint syntax.");
        }
    }
}

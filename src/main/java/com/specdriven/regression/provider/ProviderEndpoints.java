package com.specdriven.regression.provider;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
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
                || reference.matches("mock://[A-Za-z0-9_-]+(?:[.][A-Za-z0-9_-]+)*")) {
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
            boolean approvedRemote = Set.of("env://NATS_CONNECTION", "env://NATS_SERVER").contains(reference);
            if (!"nats".equals(uri.getScheme()) || uri.getHost() == null
                    || (!localHost(uri.getHost()) && !approvedRemote)
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || (uri.getPort() < 0 && (!compatibility || uri.getRawAuthority().endsWith(":")))
                    || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawUserInfo() != null && (compatibility || !reference.startsWith("env://")))) {
                throw new IllegalArgumentException("Invalid NATS endpoint or unapproved remote broker reference.");
            }
            return uri;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Invalid NATS endpoint syntax.");
        }
    }
}

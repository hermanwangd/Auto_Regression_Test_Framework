package com.specdriven.regression.provider.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** The runner owns authorized origins. The profile selects a base URL within that policy. */
final class RestDestinationPolicy {
    private final String configured;
    private final String ownerBase;

    RestDestinationPolicy(String configured, String ownerBase) {
        this.configured = configured;
        this.ownerBase = ownerBase;
    }

    private boolean allows(Origin selected) {
        if (configured == null) {
            return ownerBase != null && !ownerBase.isBlank() && selected.equals(origin(parse(ownerBase)));
        }
        if (configured.isBlank()) return false;
        boolean allowed = false;
        for (String value : configured.split(",", -1)) {
            URI uri = parse(value.strip());
            Origin entry = origin(uri);
            if (!uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())) throw invalid();
            allowed |= selected.equals(entry);
        }
        return allowed;
    }

    URI destination(String base, String target, String approvedOrigin) {
        URI baseUri = parse(base);
        Origin selected = origin(baseUri);
        if (!allows(selected) && (approvedOrigin == null || !selected.equals(origin(parse(approvedOrigin))))) {
            throw new IllegalArgumentException("REST destination is not authorized by the runner.");
        }
        URI relative = parse(target);
        if (relative.isAbsolute() || relative.getRawAuthority() != null || target.startsWith("//")
                || relative.getRawFragment() != null) throw invalid();
        String separator = base.endsWith("/") || target.startsWith("/") ? "" : "/";
        return parse(base + separator + (base.endsWith("/") && target.startsWith("/") ? target.substring(1) : target));
    }

    private record Origin(String scheme, String host, int port) {}

    private static Origin origin(URI uri) {
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawAuthority().endsWith(":") || uri.getPort() == 0 || uri.getPort() > 65535) throw invalid();
        int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(scheme) ? 443 : 80) : uri.getPort();
        return new Origin(scheme.toLowerCase(Locale.ROOT), uri.getHost().toLowerCase(Locale.ROOT), port);
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException invalidInput) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("REST requires an HTTP(S) base and an origin-relative request target.");
    }
}

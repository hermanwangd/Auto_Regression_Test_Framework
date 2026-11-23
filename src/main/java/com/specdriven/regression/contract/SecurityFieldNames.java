package com.specdriven.regression.contract;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Comparison form for credential labels. Keep the original spelling in evidence. */
public final class SecurityFieldNames {
    private static final List<String> CREDENTIAL_NAMES = List.of("password", "token", "secret", "authorization",
            "api_key", "apikey", "credential", "private_key", "privatekey");

    private SecurityFieldNames() { }

    public static String canonical(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public static boolean isCredential(String name) {
        String normalized = canonical(name);
        return CREDENTIAL_NAMES.stream().anyMatch(normalized::contains);
    }

    public static boolean endsWithCredentialLabel(String canonicalName) {
        return CREDENTIAL_NAMES.stream().anyMatch(canonicalName::endsWith);
    }
}

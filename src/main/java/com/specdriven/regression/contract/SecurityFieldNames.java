package com.specdriven.regression.contract;

import com.ibm.icu.text.Normalizer2;
import java.util.List;
import java.util.regex.Pattern;

/** Comparison form for credential labels. Keep the original spelling in evidence. */
public final class SecurityFieldNames {
    private static final List<String> CREDENTIAL_NAMES = List.of("password", "token", "secret", "authorization",
            "api_key", "apikey", "credential", "private_key", "privatekey");

    private static final Pattern FORMAT = Pattern.compile("\\p{Cf}");
    private static final Normalizer2 IDENTIFIER_FOLD = Normalizer2.getNFKCCasefoldInstance();

    private SecurityFieldNames() { }

    public static String canonical(String name) {
        return IDENTIFIER_FOLD.normalize(FORMAT.matcher(name).replaceAll("")).replace('-', '_');
    }

    public static boolean isCredential(String name) {
        String normalized = canonical(name);
        return isSessionOrCookie(normalized) || CREDENTIAL_NAMES.stream().anyMatch(normalized::contains);
    }

    public static boolean endsWithCredentialLabel(String canonicalName) {
        return isSessionOrCookie(canonicalName) || CREDENTIAL_NAMES.stream().anyMatch(canonicalName::endsWith);
    }

    public static boolean isCookieLabel(String canonicalName) {
        return matchesLabel(canonicalName, "cookie") || matchesLabel(canonicalName, "set_cookie");
    }

    private static boolean isSessionOrCookie(String name) {
        return isCookieLabel(name) || matchesLabel(name, "session") || matchesLabel(name, "sessionid")
                || matchesLabel(name, "session_id") || matchesLabel(name, "jsessionid");
    }

    private static boolean matchesLabel(String name, String label) {
        return name.equals(label) || name.endsWith("." + label) || name.endsWith("_" + label);
    }
}

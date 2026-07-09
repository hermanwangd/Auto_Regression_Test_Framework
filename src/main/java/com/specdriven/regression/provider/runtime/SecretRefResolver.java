package com.specdriven.regression.provider.runtime;

import java.util.function.Function;
import java.util.regex.Pattern;

public final class SecretRefResolver {

    private static final Pattern ENV_NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");

    private SecretRefResolver() {
    }

    public static ResolvedSecret resolveEnvSecretRef(
            String secretRef,
            String providerLabel,
            String bindingPath) {
        return resolveEnvSecretRef(secretRef, providerLabel, bindingPath, System::getenv);
    }

    public static ResolvedSecret resolveEnvSecretRef(
            String secretRef,
            String providerLabel,
            String bindingPath,
            Function<String, String> environment) {
        if (!secretRef.startsWith("env://")) {
            return ResolvedSecret.failed(ProviderFailure.of(
                    "UNSUPPORTED_SECRET_REF_SCHEME",
                    "SECRET_RESOLUTION_ERROR",
                    "Unsupported " + providerLabel + " secret_ref scheme for `" + bindingPath + "`.",
                    "Use env://<ENV_NAME> for externally materialized secret refs."));
        }
        String envName = secretRef.substring("env://".length());
        if (!ENV_NAME.matcher(envName).matches()) {
            return ResolvedSecret.failed(ProviderFailure.of(
                    "SECRET_RESOLUTION_ERROR",
                    "SECRET_RESOLUTION_ERROR",
                    providerLabel + " " + bindingPath + " env ref `" + secretRef + "` has an invalid environment variable name.",
                    "Use env://<ENV_NAME> with uppercase letters, digits, and underscores, starting with a letter or underscore."));
        }
        String resolved = stringValue(environment.apply(envName));
        if (resolved.isBlank()) {
            return ResolvedSecret.failed(ProviderFailure.of(
                    "SECRET_RESOLUTION_ERROR",
                    "SECRET_RESOLUTION_ERROR",
                    providerLabel + " " + bindingPath + " env ref `" + secretRef + "` is not set.",
                    "Set environment variable `" + envName + "` before running this execution profile."));
        }
        return new ResolvedSecret(resolved, null);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record ResolvedSecret(String value, ProviderFailure failure) {

        public static ResolvedSecret failed(ProviderFailure failure) {
            return new ResolvedSecret("", failure);
        }
    }
}

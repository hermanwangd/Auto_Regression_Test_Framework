package com.specdriven.regression.provider;

import com.specdriven.regression.contract.v03.V03OutputRedactor;

/** Shared text masking before providers write evidence. */
public final class ProviderDiagnosticRedactor {
    private ProviderDiagnosticRedactor() { }

    // Assertions require the original observation. A masked copy cannot stand in for it.
    static boolean canPersistObservation(String text) {
        return text != null && (text.isBlank() || text.equals(maskSensitiveText(text)));
    }

    public static String maskSensitiveText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return V03OutputRedactor.redactProviderDiagnostic(text)
                .replaceAll("(?i)(\"(?:password|token|api_key|authorization|secret|credential)\"\\s*:\\s*\")[^\"]*(\")", "$1***MASKED***$2")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1***MASKED***")
                .replaceAll("(?i)(jdbc:[^\\s,}\\]]+)", "***MASKED***")
                .replaceAll("(?i)(mongodb(?:\\+srv)?://[^\\s,}\\]]+)", "***MASKED***")
                .replaceAll("(?i)(nats://[^\\s,}\\]]+)", "***MASKED***")
                .replaceAll("(?i)((?:password|token|secret|credential|api_key)\\s*[=:]\\s*)[^\\s,}\\]]+", "$1***MASKED***");
    }
}

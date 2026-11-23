package com.specdriven.regression.contract;

import java.text.Normalizer;
import java.util.Locale;

/** Comparison form for credential labels. Keep the original spelling in evidence. */
public final class SecurityFieldNames {
    private SecurityFieldNames() { }

    public static String canonical(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('-', '_');
    }
}

package com.specdriven.regression.provider;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Provider inputs and evidence are data documents with unambiguous mapping keys. */
public final class ProviderDataYaml {
    private ProviderDataYaml() { }

    public static Yaml create() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        return new Yaml(new SafeConstructor(options));
    }
}

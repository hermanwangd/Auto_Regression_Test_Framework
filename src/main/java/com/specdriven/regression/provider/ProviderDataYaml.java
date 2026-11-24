package com.specdriven.regression.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Provider inputs and evidence are data documents with unambiguous mapping keys. */
public final class ProviderDataYaml {
    private ProviderDataYaml() { }

    public static Yaml create() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        DumperOptions output = new DumperOptions();
        output.setSplitLines(false);
        return new Yaml(new SafeConstructor(options), new Representer(output), output, options);
    }

    public static void createParent(Path output) throws IOException {
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    public static void writeEvidence(Path output, Map<String, Object> fields) throws IOException {
        createParent(output);
        Files.writeString(output, create().dumpAsMap(fields));
    }
}

package com.specdriven.regression.provider;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

/** Provider inputs and evidence are data documents with unambiguous mapping keys. */
public final class ProviderDataYaml {
    private ProviderDataYaml() { }

    public static Yaml create() {
        return create(false);
    }

    /** Keep finite decimal operands exact when assertion evidence is loaded for redaction. */
    public static Yaml createForAssertionEvidence() {
        return create(true);
    }

    private static Yaml create(boolean exactDecimals) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        DumperOptions output = new DumperOptions();
        output.setSplitLines(false);
        SafeConstructor constructor = exactDecimals ? new ExactDecimals(options) : new SafeConstructor(options);
        return new Yaml(constructor, new Representer(output), output, options);
    }

    private static final class ExactDecimals extends SafeConstructor {
        private ExactDecimals(LoaderOptions options) {
            super(options);
            yamlConstructors.put(Tag.FLOAT, new ConstructYamlFloat() {
                @Override
                public Object construct(Node node) {
                    String scalar = ((ScalarNode) node).getValue();
                    try {
                        return new BigDecimal(scalar);
                    } catch (NumberFormatException otherYamlFloat) {
                        return super.construct(node);
                    }
                }
            });
        }
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

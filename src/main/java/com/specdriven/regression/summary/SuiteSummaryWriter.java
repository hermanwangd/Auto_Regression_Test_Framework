package com.specdriven.regression.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

public final class SuiteSummaryWriter {
    private final ObjectMapper mapper;

    public SuiteSummaryWriter() {
        mapper = new ObjectMapper()
                .findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path write(Path outputDirectory, SuiteSummaryDocument document, boolean writeYaml) throws IOException {
        Files.createDirectories(outputDirectory);
        Path json = outputDirectory.resolve("suite_summary.json");
        mapper.writeValue(json.toFile(), document);
        if (writeYaml) {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            Map<String, Object> canonicalModel = mapper.convertValue(document, Map.class);
            Files.writeString(outputDirectory.resolve("suite_summary.yaml"), new Yaml(options).dump(canonicalModel));
        }
        return json;
    }
}

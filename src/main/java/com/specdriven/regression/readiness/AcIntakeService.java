package com.specdriven.regression.readiness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class AcIntakeService {

    private static final List<String> REQUIRED_READY_FIELDS = List.of(
            "ac_id",
            "rp_id",
            "title",
            "owner",
            "classification",
            "input",
            "behavior",
            "expected_output",
            "pass_fail_rule",
            "status");

    public AcIntakeReport intake(Path acceptanceCriteriaFile) {
        Map<String, Object> document = readYamlMap(acceptanceCriteriaFile);
        Object acEntries = document.get("acceptance_criteria");
        if (!(acEntries instanceof List<?> entries)) {
            return new AcIntakeReport(List.of());
        }

        List<AcReadinessItem> items = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> ac) {
                items.add(toReadinessItem(ac));
            }
        }
        return new AcIntakeReport(List.copyOf(items));
    }

    private AcReadinessItem toReadinessItem(Map<?, ?> ac) {
        String acId = stringValue(ac.get("ac_id"));
        String rpId = stringValue(ac.get("rp_id"));
        String title = stringValue(ac.get("title"));
        String classification = stringValue(ac.get("classification"));
        List<String> linkedProductContext = stringList(ac.get("linked_product_context"));
        List<AcReadinessGap> gaps = new ArrayList<>();

        for (String field : REQUIRED_READY_FIELDS) {
            if (isMissing(ac.get(field))) {
                gaps.add(new AcReadinessGap(
                        field,
                        "Clarify owner-authored AC `" + acId + "` by adding `" + field + "`."));
            }
        }

        String inputRef = stringValue(ac.get("input"));
        String expectedOutputRef = stringValue(ac.get("expected_output"));
        if (gaps.isEmpty() && !"not_ready_for_generation".equals(ac.get("status"))) {
            return AcReadinessItem.readyWithRefs(
                    acId, rpId, title, classification, linkedProductContext, inputRef, expectedOutputRef);
        }
        return AcReadinessItem.notReadyWithRefs(
                acId, rpId, title, classification, gaps, inputRef, expectedOutputRef);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlMap(Path path) {
        try {
            Object loaded = new Yaml().load(Files.readString(path));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read acceptance criteria: " + path, e);
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(Object::toString).toList();
    }

    private boolean isMissing(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}

package com.specdriven.regression.agentskill;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ProductRepoReadinessSkillTest {

    private static final Path SKILL = Path.of("agent-skills/product-repo-readiness/SKILL.md");

    @Test
    void productRepoReadinessSkillExplainsReadinessReportWithoutMutatingRepoOrInventingScope()
            throws IOException {
        assertThat(SKILL).exists();
        String content = Files.readString(SKILL);
        Map<String, Object> frontmatter = new Yaml().load(frontmatter(content));

        assertThat(frontmatter).containsEntry("name", "product-repo-readiness");
        assertThat(frontmatter.get("description").toString())
                .contains("Use when")
                .contains("Product Repo readiness report");
        assertThat(content).contains("readiness report");
        assertThat(content).contains("readiness_status");
        assertThat(content).contains("missing_item_summary");
        assertThat(content).contains("owner_actions");
        assertThat(content).contains("next_command_or_document");
        assertThat(content).contains("Do not mutate");
        assertThat(content).contains("Do not invent Product scope, RP scope, RP AC, or RP/RU membership");
    }

    private String frontmatter(String content) {
        String[] parts = content.split("---", 3);
        assertThat(parts).hasSizeGreaterThanOrEqualTo(3);
        return parts[1];
    }
}

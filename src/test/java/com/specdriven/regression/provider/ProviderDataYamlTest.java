package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.error.YAMLException;

class ProviderDataYamlTest {
    @Test
    void readsOrdinaryDataAndRejectsAmbiguousMappings() {
        var yaml = ProviderDataYaml.create();
        assertThat(yaml.<Map<String, String>>load("status: ready")).containsEntry("status", "ready");
        assertThatThrownBy(() -> yaml.load("status: ready\nstatus: waiting")).isInstanceOf(YAMLException.class);
    }

    @Test
    void rejectsCollectionAliasesInProviderDocuments() {
        assertThatThrownBy(() -> ProviderDataYaml.create().load("first: &item [one]\nsecond: *item"))
                .isInstanceOf(YAMLException.class);
    }
}

package com.specdriven.regression.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeBindingResolverTest {

    @TempDir
    Path tempDir;

    private final RuntimeBindingResolver resolver = new RuntimeBindingResolver();

    @Test
    void envProfileBindingsNormalizeToRuntimeBindingValues() throws Exception {
        Path envProfiles = tempDir.resolve("env_profiles");
        Files.createDirectories(envProfiles);
        Files.writeString(envProfiles.resolve("local.yaml"), """
                env_profile_id: local
                execution_mode: local
                providers:
                  oracle-like-db:
                    runtime_mode: ephemeral
                    bindings:
                      dialect: oracle
                      connection:
                        secret_ref: env://JDBC_CONNECTION
                      masking_policy:
                        redact: [connection, password]
                """);

        Map<String, Object> binding = resolver.providerBinding(tempDir, "local", "oracle-like-db");

        assertThat(binding).containsEntry("provider_id", "oracle-like-db")
                .containsEntry("runtime_mode", "ephemeral");
        assertThat(bindingValues(binding))
                .containsEntry("dialect", "oracle")
                .containsKey("connection")
                .containsKey("masking_policy");
        assertThat(map(bindingValues(binding).get("connection")))
                .containsEntry("secret_ref", "env://JDBC_CONNECTION");
        assertThat(map(bindingValues(binding).get("masking_policy")))
                .containsEntry("redact", java.util.List.of("connection", "password"));
    }

    @Test
    void deprecatedEnvProfileBindingKeysStillNormalizeToRuntimeBindingValues() throws Exception {
        Path envProfiles = tempDir.resolve("env_profiles");
        Files.createDirectories(envProfiles);
        Files.writeString(envProfiles.resolve("local.yaml"), """
                env_profile_id: local
                execution_mode: local
                providers:
                  oracle-like-db:
                    runtime_mode: ephemeral
                    binding_keys:
                      connection:
                        secret_ref: env://JDBC_CONNECTION
                      dialect:
                        value: oracle
                """);

        Map<String, Object> binding = resolver.providerBinding(tempDir, "local", "oracle-like-db");

        assertThat(bindingValues(binding)).containsEntry("dialect", "oracle");
        assertThat(map(bindingValues(binding).get("connection")))
                .containsEntry("secret_ref", "env://JDBC_CONNECTION");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bindingValues(Map<String, Object> binding) {
        return (Map<String, Object>) binding.get("binding_values");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}

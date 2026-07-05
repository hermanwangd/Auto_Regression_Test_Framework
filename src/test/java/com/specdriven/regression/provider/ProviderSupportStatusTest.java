package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ProviderSupportStatusTest {

    private static final Path CONTRACT_ROOT = Path.of("docs/02-architecture/contracts");
    private static final Path REGISTRY = CONTRACT_ROOT.resolve("provider_capability_registry.v0.2.yaml");
    private static final Set<String> SUPPORT_STATUS_VALUES =
            Set.of("supported", "contract_only", "deprecated", "unsupported");

    @Test
    void registryUsesSupportStatusAsTheOnlyPublicSupportVocabulary() throws Exception {
        Map<?, ?> registry = map(loadYaml(REGISTRY));
        Set<String> catalog = keys(registry.get("support_status_catalog"));

        assertThat(catalog).containsExactlyInAnyOrderElementsOf(SUPPORT_STATUS_VALUES);
        assertThat(keys(registry)).contains("support_status_catalog");
        assertThat(keys(registry)).doesNotContain("runtime_status_catalog");
        assertThat(strings(registry.get("registry_entry_required_fields")))
                .contains("support_status")
                .doesNotContain("runtime_status");

        for (Map.Entry<?, ?> entry : map(registry.get("provider_types")).entrySet()) {
            Map<?, ?> provider = map(entry.getValue());
            assertThat(keys(provider))
                    .as("provider_type " + entry.getKey())
                    .contains("support_status")
                    .doesNotContain("runtime_status");
            assertThat(String.valueOf(provider.get("support_status")))
                    .as("provider_type " + entry.getKey())
                    .isIn(SUPPORT_STATUS_VALUES);
        }
    }

    @Test
    void registryHasAContractForEveryPublishedProviderType() throws Exception {
        Map<?, ?> registry = map(loadYaml(REGISTRY));
        Map<?, ?> providerTypes = map(registry.get("provider_types"));

        for (Map.Entry<?, ?> entry : providerTypes.entrySet()) {
            String providerType = String.valueOf(entry.getKey());
            Path contract = CONTRACT_ROOT.resolve(String.valueOf(map(entry.getValue()).get("contract_ref")));
            assertThat(contract).as(providerType).isRegularFile();
            assertThat(map(loadYaml(contract)).get("provider_type")).as(providerType).isEqualTo(providerType);
        }
    }

    @Test
    void everyProviderContractIsRepresentedInThePublicRegistry() throws Exception {
        Map<?, ?> registry = map(loadYaml(REGISTRY));
        Set<String> providerTypes = keys(registry.get("provider_types"));
        Set<String> missing = new LinkedHashSet<>();

        try (var paths = Files.list(CONTRACT_ROOT.resolve("provider-contracts"))) {
            for (Path contract : paths
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList()) {
                String providerType = String.valueOf(map(loadYaml(contract)).get("provider_type"));
                if (!providerTypes.contains(providerType)) {
                    missing.add(providerType);
                }
            }
        }

        assertThat(missing).isEmpty();
    }

    @Test
    void kafkaAndIbmMqAreSupportedAfterExternalClientRuntimeBaselineExists() throws Exception {
        Map<?, ?> providerTypes = map(map(loadYaml(REGISTRY)).get("provider_types"));

        assertThat(supportStatus(providerTypes, "kafka")).isEqualTo("supported");
        assertThat(supportStatus(providerTypes, "ibm_mq")).isEqualTo("supported");
        assertThat(supportStatus(providerTypes, "kafka_messaging")).isEqualTo("deprecated");
    }

    @Test
    void supportedKafkaAndIbmMqContractsOnlyPublishRuntimeBackedBindings() throws Exception {
        Map<?, ?> kafka = map(loadYaml(CONTRACT_ROOT.resolve("provider-contracts/kafka.yaml")));
        Map<?, ?> ibmMq = map(loadYaml(CONTRACT_ROOT.resolve("provider-contracts/ibm_mq.yaml")));

        assertThat(keys(kafka.get("binding_keys")))
                .containsExactlyInAnyOrder("bootstrap_servers", "topic", "consumer_group", "timeout", "poll_interval");
        assertThat(map(kafka.get("defaults")).keySet())
                .map(String::valueOf)
                .doesNotContain("serialization");
        Map<?, ?> kafkaOperations = map(kafka.get("operations"));
        assertThat(strings(map(kafkaOperations.get("kafka_publish")).get("allowed_inputs")))
                .containsExactlyInAnyOrder("key", "payload_ref", "payload", "timeout");
        assertThat(strings(map(kafkaOperations.get("kafka_observe")).get("allowed_inputs")))
                .containsExactlyInAnyOrder("key", "consume_from", "timeout", "poll_interval");
        assertThat(strings(map(kafkaOperations.get("kafka_payload_match")).get("allowed_inputs")))
                .containsExactlyInAnyOrder("expected_ref", "key", "consume_from", "timeout", "poll_interval");

        assertThat(keys(ibmMq.get("binding_keys")))
                .containsExactlyInAnyOrder(
                        "queue_manager", "channel", "conn_name", "queue", "credential.secret_ref",
                        "timeout", "poll_interval");
        Map<?, ?> ibmMqOperations = map(ibmMq.get("operations"));
        assertThat(strings(map(ibmMqOperations.get("mq_put")).get("allowed_inputs")))
                .containsExactlyInAnyOrder("payload_ref", "payload", "correlation_id", "timeout");
        assertThat(strings(map(ibmMqOperations.get("mq_browse")).get("allowed_inputs")))
                .containsExactlyInAnyOrder("correlation_id", "timeout", "poll_interval");
        assertThat(strings(map(map(ibmMq.get("operations")).get("mq_browse")).get("allowed_inputs")))
                .doesNotContain("message_selector", "message_id", "headers", "properties", "max_messages");
        assertThat(strings(map(map(ibmMq.get("operations")).get("mq_message_exists")).get("allowed_inputs")))
                .doesNotContain("message_selector");
        assertThat(strings(map(map(ibmMq.get("operations")).get("mq_payload_match")).get("allowed_inputs")))
                .doesNotContain("message_selector");
    }

    private String supportStatus(Map<?, ?> providerTypes, String providerType) {
        return String.valueOf(map(providerTypes.get(providerType)).get("support_status"));
    }

    private Object loadYaml(Path path) throws IOException {
        return new Yaml().load(Files.readString(path));
    }

    private Set<String> keys(Object value) {
        return map(value).keySet().stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private java.util.List<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return java.util.List.of();
        }
        return collection.stream().map(String::valueOf).toList();
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }
}

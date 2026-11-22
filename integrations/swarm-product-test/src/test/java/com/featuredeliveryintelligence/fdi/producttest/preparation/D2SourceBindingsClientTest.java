package com.featuredeliveryintelligence.fdi.producttest.preparation;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Consumer trust boundary only: no second lock/root/Git verifier or assembler. */
class D2SourceBindingsClientTest {
    @TempDir Path root;
    @Test void externallyWrongJarPinBlocksBeforeProducerLaunch() throws Exception {
        Path jar=fakeJar();
        var binding=binding(jar,"a".repeat(64),PreparationFixture.hash("{}".getBytes()));
        assertThatThrownBy(()->new D2SourceBindingsClient(binding).acquire()).hasMessage("PRODUCER_PIN_MISMATCH");
    }
    @Test void externallyWrongExactRuntimeSchemaPinBlocksBeforeProducerLaunch() throws Exception {
        Path jar=fakeJar();
        var binding=binding(jar,PreparationFixture.hash(Files.readAllBytes(jar)),"b".repeat(64));
        assertThatThrownBy(()->new D2SourceBindingsClient(binding).acquire()).hasMessage("PRODUCER_PIN_MISMATCH");
    }
    @Test void observedOrSelfPinnedLabelCannotOverrideIndependentProducerPins() throws Exception {
        final var selfPinned=wire();var observer=selfPinned.path("validator").deepCopy();var pins=pins(selfPinned);
        D2SourceBindingsClient.validateMetadata(selfPinned,observer,pins,publicRefs(selfPinned));
        ((ObjectNode)selfPinned.path("validator")).put("identity_state","PINNED_JAR");
        assertThatThrownBy(()->D2SourceBindingsClient.validateMetadata(selfPinned,observer,pins,publicRefs(selfPinned))).hasMessage("PRODUCER_PIN_MISMATCH");
        final var changed=wire();((ObjectNode)changed.path("validator")).put("artifact_sha256","e".repeat(64));
        assertThatThrownBy(()->D2SourceBindingsClient.validateMetadata(changed,observer,pins,publicRefs(changed))).hasMessage("PRODUCER_PIN_MISMATCH");
    }
    @Test void rootCompositionXAndArtifactAArePreservedWithoutCrossRepoBareIdCredit() throws Exception {
        var wire=wire();var pins=pins(wire);var safe=D2SourceBindingsClient.validateMetadata(wire,wire.path("validator"),pins,publicRefs(wire));
        assertThat(safe.path("lock").path("composition_root_version").asText()).isNotEqualTo(safe.path("lock").path("artifact_source_testrepo_version").asText());
        var cases=new HashSet<String>();for(var a:safe.path("artifacts"))if(a.path("role").asText().equals("case"))cases.add(a.path("artifact_id").asText());
        assertThat(cases).hasSize(2);assertThat(safe.path("execution_binding_state").asText()).isEqualTo("NOT_EVALUATED");
    }
    @Test void metadataUnknownFieldAndUnapprovedPrivateReferenceAreBlocked() throws Exception {
        var wire=wire();wire.put("private_context","PRIVATE-CANARY");var observer=wire.path("validator");var pins=pins(wire);
        assertThatThrownBy(()->D2SourceBindingsClient.validateMetadata(wire,observer,pins,publicRefs(wire))).hasMessage("SOURCE_BINDING_MISMATCH");
        var clean=wire();var policy=publicRefs(clean);policy.remove(clean.path("repositories").get(0).path("source_owner_ref").asText());
        assertThatThrownBy(()->D2SourceBindingsClient.validateMetadata(clean,clean.path("validator"),pins(clean),policy)).hasMessage("METADATA_NOT_APPROVED");
    }
    private Path fakeJar() throws Exception {
        Path jar=root.resolve("fake-producer.jar");try(var zip=new ZipOutputStream(Files.newOutputStream(jar))){zip.putNextEntry(new ZipEntry("BOOT-INF/classes/schemas/source_bindings.v1.schema.yaml"));zip.write("{}".getBytes());zip.closeEntry();}return jar;
    }
    private D2ProducerBinding binding(Path jar,String expectedJar,String schema) throws Exception {
        Path lock=root.resolve("dependencies.lock"),roots=root.resolve("protected-roots.json");Files.writeString(lock,"public fixture");Files.writeString(roots,"public fixture");
        return new D2ProducerBinding(Path.of(System.getProperty("java.home"),"bin/java"),jar,expectedJar,schema,lock,PreparationFixture.hash(Files.readAllBytes(lock)),roots,PreparationFixture.hash(Files.readAllBytes(roots)),root,Duration.ofSeconds(30),Set.of());
    }
    static ObjectNode wire() throws Exception {try(var in=D2SourceBindingsClientTest.class.getResourceAsStream("/preparation/d2-public-source-bindings.json")){return (ObjectNode)new ObjectMapper().readTree(in);}}
    static D2SourceBindingsClient.ExpectedPins pins(JsonNode w) {return new D2SourceBindingsClient.ExpectedPins(w.path("validator").path("artifact_sha256").asText(),w.path("validator").path("schema_sha256").asText(),w.path("lock").path("sha256").asText(),w.path("trusted_root_binding_sha256").asText());}
    static Set<String> publicRefs(JsonNode wire) {
        // Test-only PUBLIC_SYNTHETIC fixture policy; never derive production authority from received metadata.
        var result=new HashSet<String>();var lock=wire.path("lock");result.add(lock.path("source_repo").asText());result.add(lock.path("lock_id").asText());result.add(lock.path("lock_version").asText());
        for(var r:wire.path("repositories")){result.add(r.path("source_repo").asText());result.add(r.path("source_owner_ref").asText());}
        for(var a:wire.path("artifacts")){result.add(a.path("artifact_id").asText());result.add(a.path("source_repo").asText());result.add(a.path("source_owner_ref").asText());result.add(a.path("cleanup_owner_ref").asText());}
        for(var s:wire.path("sut_expectations"))for(String key:List.of("sut_id","owner_ref","source_repo","expected_deployed_revision","readiness_binding_ref","cleanup_owner_ref"))result.add(s.path(key).asText());
        return result;
    }
}

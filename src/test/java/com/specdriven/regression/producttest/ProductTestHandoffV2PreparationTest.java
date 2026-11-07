package com.specdriven.regression.producttest;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Field dispatch is tested before capability rejection; no native provider or receipt is substituted. */
class ProductTestHandoffV2PreparationTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    private Path review(String version)throws Exception{
        Path source=Path.of("samples/41-product-testing/reviewed_local");try(var files=Files.walk(source)){for(Path p:files.toList()){Path dest=root.resolve(source.relativize(p));if(Files.isDirectory(p))Files.createDirectories(dest);else Files.copy(p,dest);}}
        Path input=root.resolve("review-input.json");Map<String,Object>h=json.readValue(input.toFile(),LinkedHashMap.class);h.put("contract_version",version);h.put("knowledge_selection_proof",Map.of("path","handoff/proof.json","sha256","a".repeat(64)));h.put("expected_sut_revisions",Map.of("fixture:sut","fixture:revision"));h.put("sut_revision_evidence",Map.of("path","handoff/runtime.json","sha256","b".repeat(64),"sut_id","fixture:sut"));json.writeValue(input.toFile(),h);return input;
    }
    private void prepare(Path review){new ProductTestHandoffService(Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"),ZoneOffset.UTC)).prepare(root.resolve("suite_manifest.yaml"),"local_v03",review,null,root.resolve("handoff/reviewed-tests.json"));}
    @Test void v2FieldsReachTheExistingStrictNativeRequiredCapabilityGate()throws Exception{
        Path input=review("product-test-reviewed-handoff.v2");assertThatThrownBy(()->prepare(input)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("v2_capability").hasMessageNotContaining("unknown field");
    }
    @Test void legacyV1StillRejectsEveryV2OnlyField()throws Exception{
        Path input=review("product-test-reviewed-handoff.v1");assertThatThrownBy(()->prepare(input)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("review_input").hasMessageContaining("unknown field");
    }
}

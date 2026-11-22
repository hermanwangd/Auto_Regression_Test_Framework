package com.specdriven.regression.producttest;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Actual encoded-byte ceiling is checked before materialization/dispatch, with bounded serialization. */
class KnowledgeProvenanceBudgetTest {
    @Test void ordinaryMetadataRemainsWithinRuntimeImportBudget(){assertThatCode(()->ProductTestHandoffService.checkProvenanceBudget(Map.of("qualified_source_ref","fixture:pk@"+"a".repeat(40)+":knowledge.md#PK1","context_sha256","b".repeat(64)))).doesNotThrowAnyException();}
    @Test void oversizedProjectedMetadataIsRejectedBeforeRunnerMaterialization(){assertThatThrownBy(()->ProductTestHandoffService.checkProvenanceBudget(Map.of("trace","x".repeat(2*1024*1024)))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("provenance_output_limit");}
    @Test void serializerAbortsWithoutExpandingAnUnboundedProjection(){
        AtomicInteger expanded=new AtomicInteger();JsonSerializable lazy=new JsonSerializable(){public void serialize(JsonGenerator g,SerializerProvider p)throws IOException{g.writeStartArray();for(int i=0;i<1_000_000;i++){expanded.incrementAndGet();g.writeString("x".repeat(2048));}g.writeEndArray();}public void serializeWithType(JsonGenerator g,SerializerProvider p,TypeSerializer t)throws IOException{serialize(g,p);}};
        assertThatThrownBy(()->ProductTestHandoffService.checkProvenanceBudget(Map.of("trace",lazy))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("provenance_output_limit");assertThat(expanded.get()).isLessThan(1100);
    }
}

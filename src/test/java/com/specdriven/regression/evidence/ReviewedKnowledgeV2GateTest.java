package com.specdriven.regression.evidence;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specdriven.regression.producttest.KnowledgeSelectionProof;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** New EHS proposal contracts; the positive is expected RED while the guard proposal is UNAPPLIED. */
class ReviewedKnowledgeV2GateTest {
    @TempDir Path root;
    private ReviewedGrpcProofIdentityTest fixture()throws Exception {var f=new ReviewedGrpcProofIdentityTest();f.root=root;f.validHistoricalFixture();KnowledgeV2Fixture.augment(f);return f;}
    @Test void historicalValidatorAcceptsAtInvocationWithoutCurrentClockOrOwner()throws Exception {
        var f=fixture();assertThatCode(()->KnowledgeSelectionProof.validateProvenance(KnowledgeV2Fixture.JSON.convertValue(f.provenance,Map.class),KnowledgeV2Fixture.JSON.convertValue(f.result,Map.class))).doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(strings={"executed_not_selected","missing_selected_case","wrong_pk_commit","expiry_after_context","expiry_after_review_budget"})
    void historicalMetadataJoinsRejectDirectlyWithoutEhsRouting(String mutation)throws Exception {
        var f=fixture();
        switch(mutation){
            case "executed_not_selected"->((ObjectNode)f.provenance.path("source_case_selection").get(0)).put("state","NOT_SELECTED");
            case "missing_selected_case"->f.provenance.putArray("source_case_selection").add(KnowledgeV2Fixture.obj("qualified_case_ref",KnowledgeV2Fixture.REPO+"@"+KnowledgeV2Fixture.A+":tests/sit/test_cases/other.yaml#OTHER","source_test_id","OTHER","state","NOT_SELECTED"));
            case "wrong_pk_commit"->{String ref=KnowledgeV2Fixture.PK+"@"+"d".repeat(40)+":knowledge.md#PK1";((ObjectNode)f.provenance.path("knowledge_sources").get(0)).put("qualified_source_ref",ref);((ObjectNode)f.provenance.path("knowledge_trace").get(0)).put("qualified_source_ref",ref);}
            case "expiry_after_context"->f.provenance.put("valid_until","2030-01-01T00:00:00Z");
            case "expiry_after_review_budget"->{f.provenance.put("valid_until","2000-01-01T00:10:01Z").put("knowledge_valid_until","2000-01-01T00:20:00Z");((ObjectNode)f.provenance.path("knowledge_sources").get(0)).put("valid_until","2000-01-01T00:20:00Z");((ObjectNode)f.provenance.path("knowledge_trace").get(0)).put("source_valid_until","2000-01-01T00:20:00Z");}
        }
        assertThatThrownBy(()->KnowledgeSelectionProof.validateProvenance(KnowledgeV2Fixture.JSON.convertValue(f.provenance,Map.class),KnowledgeV2Fixture.JSON.convertValue(f.result,Map.class))).as("direct helper, unchanged EHS cannot mask: "+mutation).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void historicalSourceFromExecutionRepositoryUsesArtifactAWhileCompositionRemainsX()throws Exception {
        var f=fixture();String ref=KnowledgeV2Fixture.REPO+"@"+KnowledgeV2Fixture.A+":knowledge.md#PK1";
        ((ObjectNode)f.provenance.path("knowledge_sources").get(0)).put("qualified_source_ref",ref);((ObjectNode)f.provenance.path("knowledge_trace").get(0)).put("qualified_source_ref",ref);
        assertThatCode(()->KnowledgeSelectionProof.validateProvenance(KnowledgeV2Fixture.JSON.convertValue(f.provenance,Map.class),KnowledgeV2Fixture.JSON.convertValue(f.result,Map.class))).doesNotThrowAnyException();
        String compositionRef=ref.replace(KnowledgeV2Fixture.A,KnowledgeV2Fixture.X);
        ((ObjectNode)f.provenance.path("knowledge_sources").get(0)).put("qualified_source_ref",compositionRef);((ObjectNode)f.provenance.path("knowledge_trace").get(0)).put("qualified_source_ref",compositionRef);
        assertThatThrownBy(()->KnowledgeSelectionProof.validateProvenance(KnowledgeV2Fixture.JSON.convertValue(f.provenance,Map.class),KnowledgeV2Fixture.JSON.convertValue(f.result,Map.class))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void proposedExactV2RouteAcceptsOnlyTheReviewedSyntheticMetadataControl()throws Exception {
        var f=fixture();assertThat(f.validate().valid()).as("Expected RED until exact minimal EHS proposal is independently approved/applied: %s",f.validate().findings()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"dependency","provider_count","sit","source_owner","source_owner_mismatch","source_version","oracle","sut_revision","sut_pin","trace_case","expiry_at_invocation"})
    void malformedOrBroadenedV2RemainsRejectedAfterLocalReseal(String mutation)throws Exception {
        var f=fixture();
        switch(mutation){
            case "dependency"->f.provenance.putObject("dependency_modes").put("unexpected","real");
            case "provider_count"->((ObjectNode)f.provenance.path("provider_modes")).put("unexpected","real");
            case "sit"->f.provenance.put("environment_stage","sit");
            case "source_owner"->((ObjectNode)f.provenance.path("executed_source_bindings").get(0)).put("source_owner_ref","UNBOUND");
            case "source_owner_mismatch"->((ObjectNode)f.provenance.path("executed_source_bindings").get(0)).put("source_owner_ref","fixture:wrong-owner");
            case "source_version"->((ObjectNode)f.provenance.path("source_lock")).put("composition_root_version","d".repeat(40));
            case "oracle"->((ObjectNode)f.provenance.path("knowledge_trace").get(0).path("oracle")).put("sha256","0".repeat(64));
            case "sut_revision"->((ObjectNode)f.provenance.path("sut_revisions").path("fixture:sut")).put("observed","wrong-version");
            case "sut_pin"->f.provenance.remove("sut_revision_binding_sha256");
            case "trace_case"->((ObjectNode)f.provenance.path("knowledge_trace").get(0)).put("qualified_case_ref","fixture:other@"+KnowledgeV2Fixture.A+":tests/sit/test_cases/case.yaml#TC-1");
            case "expiry_at_invocation"->f.provenance.put("knowledge_valid_until","2000-01-01T00:00:01Z");
        }
        f.save();
        if(!Set.of("dependency","provider_count","sit").contains(mutation))
            assertThatThrownBy(()->KnowledgeSelectionProof.validateProvenance(KnowledgeV2Fixture.JSON.convertValue(f.provenance,Map.class),KnowledgeV2Fixture.JSON.convertValue(f.result,Map.class))).as("direct historical helper: "+mutation).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.validate().valid()).as(mutation).isFalse();
    }
}

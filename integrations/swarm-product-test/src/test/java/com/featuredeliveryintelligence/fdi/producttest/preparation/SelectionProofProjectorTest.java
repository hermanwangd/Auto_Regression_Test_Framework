package com.featuredeliveryintelligence.fdi.producttest.preparation;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SelectionProofProjectorTest {
    @Test void verifiedOriginalBecomesDistinctMetadataProofWithoutPrivateBody() {
        byte[] original=PreparationFixture.bytes(PreparationFixture.receipt());var replayCalls=new AtomicInteger();
        var projector=new SelectionProofProjector(bytes -> { assertThat(bytes).isEqualTo(original);replayCalls.incrementAndGet(); },PreparationFixture.publicRefs(),Clock.fixed(PreparationFixture.NOW,ZoneOffset.UTC));
        try(var context=PreparationFixture.context()) {
            var proof=projector.project(PreparationFixture.request(),original,context);
            assertThat(replayCalls.get()).isEqualTo(1);
            assertThat(proof.selected().get(0).qualifiedSourceRef()).isEqualTo(PreparationFixture.qualified());
            assertThat(proof.privateOriginalReceiptSha256()).isEqualTo(PreparationFixture.hash(original));
            assertThat(proof.projectionSha256()).isNotEqualTo(proof.privateOriginalReceiptSha256());
            String json=new String(proof.jsonBytes(),StandardCharsets.UTF_8);
            assertThat(json).contains("knowledge-selection-proof.v1","UNVERIFIED_BY_HTTP_CLIENT").doesNotContain("PRIVATE-CANARY","private:reviewer","private:policy","statement","Ignore required");
        }
    }
    @Test void verifierFailureCannotBeConvertedToProjectedAuthority() {
        var projector=new SelectionProofProjector(bytes->{throw new IllegalArgumentException("PRIVATE-CANARY");},PreparationFixture.publicRefs(),Clock.fixed(PreparationFixture.NOW,ZoneOffset.UTC));
        try(var context=PreparationFixture.context()) {
            assertThatThrownBy(()->projector.project(PreparationFixture.request(),PreparationFixture.bytes(PreparationFixture.receipt()),context)).hasMessage("TAMPERED");
        }
    }
    @Test void equalityAtExpiryAndRehashedWrongScopeAreBlocked() {
        for(String mutation:List.of("expired","scope","conflict","partial","unselected","content")) {
            var receipt=PreparationFixture.receipt(); var selected=(ObjectNode)receipt.path("context").path("selected").get(0);
            switch(mutation) {
                case "expired" -> selected.put("validUntil",PreparationFixture.NOW.toString());
                case "scope" -> ((ObjectNode)receipt.path("context").path("request")).put("workspaceRef","other:workspace");
                case "conflict" -> selected.withArray("sourceConflictRefs").add("private:conflict");
                case "partial" -> receipt.put("providerMappingStatus","PARTIAL");
                case "unselected" -> ((ObjectNode)receipt.path("cogneeSelection").path("candidateSearch").path("candidates").get(0)).put("sourceId","other:record");
                case "content" -> ((ObjectNode)receipt.path("cogneeSelection").path("candidateSearch").path("candidates").get(0)).put("contentSha256","c".repeat(64));
            }
            PreparationFixture.resign(receipt);
            try(var context=PreparationFixture.context()) {
                assertThatThrownBy(()->projector().project(PreparationFixture.request(),PreparationFixture.bytes(receipt),context)).as(mutation).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }
    @Test void unapprovedReferenceAndMalformedOrOversizeReceiptNeverLeakDiagnostics() {
        var refs=new HashSet<>(PreparationFixture.publicRefs());refs.remove(PreparationFixture.OWNER);
        try(var context=PreparationFixture.context()) {
            var projector=new SelectionProofProjector(b->{},refs,Clock.fixed(PreparationFixture.NOW,ZoneOffset.UTC));
            assertThatThrownBy(()->projector.project(PreparationFixture.request(),PreparationFixture.bytes(PreparationFixture.receipt()),context)).hasMessage("METADATA_NOT_APPROVED");
            for(byte[] raw:List.of("{\"status\":\"one\",\"status\":\"two\"}".getBytes(StandardCharsets.UTF_8),new byte[4*1024*1024+1],new byte[]{(byte)0xc3,(byte)0x28}))
                assertThatThrownBy(()->projector().project(PreparationFixture.request(),raw,context)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("PRIVATE-CANARY");
        }
    }
    private SelectionProofProjector projector() { return new SelectionProofProjector(b->{},PreparationFixture.publicRefs(),Clock.fixed(PreparationFixture.NOW,ZoneOffset.UTC)); }
}

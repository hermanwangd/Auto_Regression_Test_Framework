package com.featuredeliveryintelligence.fdi.producttest.preparation;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PreparationServiceTest {
    @Test void proposalKeepsCitationsAndIndependentOracleButRemainsNonExecutable() {
        var context=PreparationFixture.context();
        var proof=new SelectionProofProjector(b->{},PreparationFixture.publicRefs(),Clock.fixed(PreparationFixture.NOW,ZoneOffset.UTC))
                .project(PreparationFixture.request(),PreparationFixture.bytes(PreparationFixture.receipt()),context);
        var service=new PreparationService(request->ContextAcquisition.ready(context,proof),Clock.fixed(PreparationFixture.NOW,ZoneOffset.UTC),proposalRefs());
        var result=service.prepare(PreparationFixture.request(),"fixture:author",List.of(trace()));
        assertThat(result.status()).isEqualTo(PreparationService.Status.PREPARED_NOT_APPROVED);
        var proposal=result.proposal();assertThat(proposal.state()).isEqualTo("prepared_not_approved");
        assertThat(proposal.executionBindingState()).isEqualTo("D2_BINDING_API_UNBOUND");
        assertThat(proposal.knowledgeMode()).isEqualTo("required");
        assertThat(proposal.testCases().get(0).citations().get(0).qualifiedSourceRef()).isEqualTo(PreparationFixture.qualified());
        assertThat(proposal.testCases().get(0).oracle().sha256()).isEqualTo("d".repeat(64));
        assertThat(new String(proposal.jsonBytes(),StandardCharsets.UTF_8)).doesNotContain("PRIVATE-CANARY","Ignore required","private:policy");
        assertThatThrownBy(context::digest).hasMessage("CONTEXT_CLOSED");
    }
    @Test void unavailableContextBlocksWithoutInvokingAnyTestProviderOrChangingMode() {
        var result=new PreparationService(KnowledgeContextPort.unavailable(),Clock.systemUTC()).prepare(PreparationFixture.request(),"fixture:author",List.of(trace()));
        assertThat(result.status()).isEqualTo(PreparationService.Status.BLOCKED);
        assertThat(result.reason()).isEqualTo(ContextAcquisition.Reason.REQUIRED_CONTEXT_MISSING);
        assertThat(result.proposal()).isNull();
    }
    @Test void missingOracleReviewOrWrongCitationCannotManufacturePreparedProposal() {
        for(String change:List.of("oracle","citation","requirement","same-reviewer")) {
            try(var context=PreparationFixture.context()) {
                var proof=new SelectionProofProjector(b->{},PreparationFixture.publicRefs(),Clock.fixed(PreparationFixture.NOW,ZoneOffset.UTC))
                        .project(PreparationFixture.request(),PreparationFixture.bytes(PreparationFixture.receipt()),context);
                ProposalTrace t=trace();
                if(change.equals("citation"))t=new ProposalTrace(t.testCaseId(),t.qualifiedCaseRef(),t.caseSha256(),t.requirementRefs(),t.acceptanceRefs(),t.riskRefs(),t.apiRefs(),t.codeRefs(),List.of(new ProposalTrace.Citation(PreparationFixture.qualified(),"e".repeat(64))),t.oracle());
                if(change.equals("requirement"))t=new ProposalTrace(t.testCaseId(),t.qualifiedCaseRef(),t.caseSha256(),Set.of(q("spec.md","OTHER")),t.acceptanceRefs(),t.riskRefs(),t.apiRefs(),t.codeRefs(),t.citations(),t.oracle());
                if(change.equals("oracle"))t=new ProposalTrace(t.testCaseId(),t.qualifiedCaseRef(),t.caseSha256(),t.requirementRefs(),t.acceptanceRefs(),t.riskRefs(),t.apiRefs(),t.codeRefs(),t.citations(),new ProposalTrace.OracleRef(q("oracle.json","O1"),"d".repeat(64),"","fixture:reviewer"));
                var result=new PreparationService(r->ContextAcquisition.ready(context,proof),Clock.fixed(PreparationFixture.NOW,ZoneOffset.UTC))
                        .prepare(PreparationFixture.request(),change.equals("same-reviewer")?"fixture:reviewer":"fixture:author",List.of(t));
                assertThat(result.status()).as(change).isEqualTo(PreparationService.Status.BLOCKED);assertThat(result.proposal()).isNull();
            }
        }
    }
    @Test void privateOracleReviewReferenceCannotBeExportedAsProposalMetadata() {
        try(var context=PreparationFixture.context()) {
            var proof=new SelectionProofProjector(b->{},PreparationFixture.publicRefs(),Clock.fixed(PreparationFixture.NOW,ZoneOffset.UTC))
                    .project(PreparationFixture.request(),PreparationFixture.bytes(PreparationFixture.receipt()),context);
            var t=trace();var poisoned=new ProposalTrace(t.testCaseId(),t.qualifiedCaseRef(),t.caseSha256(),t.requirementRefs(),t.acceptanceRefs(),t.riskRefs(),t.apiRefs(),t.codeRefs(),t.citations(),new ProposalTrace.OracleRef(t.oracle().qualifiedRef(),t.oracle().sha256(),PreparationFixture.SECRET,"fixture:reviewer"));
            var result=new PreparationService(r->ContextAcquisition.ready(context,proof),Clock.fixed(PreparationFixture.NOW,ZoneOffset.UTC),proposalRefs()).prepare(PreparationFixture.request(),"fixture:author",List.of(poisoned));
            assertThat(result.status()).isEqualTo(PreparationService.Status.BLOCKED);
            assertThat(result.reason()).isEqualTo(ContextAcquisition.Reason.METADATA_NOT_APPROVED);
            assertThat(result.proposal()).isNull();
        }
    }
    static Set<String> proposalRefs() {
        var refs=new HashSet<>(PreparationFixture.publicRefs());var t=trace();refs.addAll(List.of("fixture:author","fixture:reviewer","fixture:oracle-review",t.testCaseId(),t.qualifiedCaseRef(),t.oracle().qualifiedRef()));
        refs.addAll(t.requirementRefs());refs.addAll(t.acceptanceRefs());refs.addAll(t.riskRefs());refs.addAll(t.apiRefs());refs.addAll(t.codeRefs());return refs;
    }
    static ProposalTrace trace() { return new ProposalTrace("TC-1",q("tests/sit/cases/case.yaml","TC-1"),"c".repeat(64),PreparationFixture.request().requirementRefs(),Set.of(q("spec.md","AC1")),Set.of(q("risk.md","R1")),Set.of(q("api.yaml","API1")),Set.of(q("src/Service.java","METHOD1")),List.of(new ProposalTrace.Citation(PreparationFixture.qualified(),PreparationFixture.hash(PreparationFixture.SECRET.getBytes(StandardCharsets.UTF_8)))),new ProposalTrace.OracleRef(q("oracle.json","O1"),"d".repeat(64),"fixture:oracle-review","fixture:reviewer")); }
    static String q(String path,String id) { return "fixture:repo@"+"a".repeat(40)+":"+path+"#"+id; }
}

package com.featuredeliveryintelligence.fdi.producttest.preparation;

import java.time.*;
import java.util.*;
import static com.featuredeliveryintelligence.fdi.producttest.preparation.ContextAcquisition.Reason.*;

/** Test-design preparation only. No runner/provider interface, execution, oracle generation or retrieval fallback. */
public final class PreparationService {
    public enum Status { PREPARED_NOT_APPROVED, BLOCKED }
    public record Result(Status status,ContextAcquisition.Reason reason,PreparedTestProposal proposal) {}
    private final KnowledgeContextPort knowledge;
    private final Clock clock;
    private final Set<String> approvedPublicReferences;
    public PreparationService(KnowledgeContextPort knowledge,Clock clock) { this(knowledge,clock,Set.of()); }
    public PreparationService(KnowledgeContextPort knowledge,Clock clock,Set<String> approvedPublicReferences) { this.knowledge=Objects.requireNonNull(knowledge);this.clock=Objects.requireNonNull(clock);this.approvedPublicReferences=Set.copyOf(approvedPublicReferences); }
    public Result prepare(ContextRequest request,String proposalAuthorRef,List<ProposalTrace> candidates) {
        return prepare(request,proposalAuthorRef,candidates,null);
    }
    public Result prepare(ContextRequest request,String proposalAuthorRef,List<ProposalTrace> candidates,D2SourceBindingsClient.Admitted admittedSource) {
        try(ContextAcquisition acquired=knowledge.acquire(request)) {
            PreparationContracts.require(acquired!=null,REQUIRED_CONTEXT_MISSING);
            if(acquired.status()!=ContextAcquisition.Status.READY) return new Result(Status.BLOCKED,acquired.reason(),null);
            PreparationContracts.ref(proposalAuthorRef);allowed(proposalAuthorRef);
            var context=acquired.context().orElseThrow(()->PreparationContracts.fail(REQUIRED_CONTEXT_MISSING));
            var proof=acquired.proof().orElseThrow(()->PreparationContracts.fail(REQUIRED_CONTEXT_MISSING));
            PreparationContracts.require(proof.scope().equals(request),SCOPE_MISMATCH);
            if(admittedSource!=null)for(var expected:admittedSource.currentRepositoryRevisions().entrySet())
                PreparationContracts.require(expected.getValue().equals(request.repositoryRevisions().get(expected.getKey())),SOURCE_VERSION_MISMATCH);
            PreparationContracts.require(proof.validUntil().isAfter(clock.instant())&&context.validUntil().isAfter(clock.instant()),EXPIRED);
            PreparationContracts.require(candidates!=null&&!candidates.isEmpty()&&candidates.size()<=512,INVALID_INPUT);
            Set<String> tests=new HashSet<>(),qualifiedCases=new HashSet<>(),requirements=new HashSet<>();
            Map<String,String> selected=new HashMap<>();proof.selected().forEach(v->selected.put(v.qualifiedSourceRef(),v.contentSha256()));
            for(var candidate:candidates) {
                PreparationContracts.require(tests.add(candidate.testCaseId())&&qualifiedCases.add(candidate.qualifiedCaseRef()),CONFLICT);
                if(admittedSource==null){bound(candidate.qualifiedCaseRef(),request);bound(candidate.oracle().qualifiedRef(),request);}
                else {
                    var caseSource=admittedSource.artifact(candidate.qualifiedCaseRef());var oracleSource=admittedSource.artifact(candidate.oracle().qualifiedRef());
                    PreparationContracts.require("case".equals(caseSource.path("role").asText())&&candidate.testCaseId().equals(caseSource.path("source_id").asText())&&candidate.caseSha256().equals(caseSource.path("sha256").asText()),SOURCE_BINDING_MISMATCH);
                    PreparationContracts.require("oracle".equals(oracleSource.path("role").asText())&&candidate.oracle().sha256().equals(oracleSource.path("sha256").asText()),SOURCE_BINDING_MISMATCH);
                }
                allowed(candidate.testCaseId());allowed(candidate.qualifiedCaseRef());allowed(candidate.oracle().qualifiedRef());
                for(var refs:List.of(candidate.requirementRefs(),candidate.acceptanceRefs(),candidate.riskRefs(),candidate.apiRefs(),candidate.codeRefs()))
                    for(String ref:refs) {if(admittedSource==null)bound(ref,request);allowed(ref);}
                PreparationContracts.require(request.requirementRefs().containsAll(candidate.requirementRefs()),PROPOSAL_MAPPING_MISMATCH);
                requirements.addAll(candidate.requirementRefs());
                var oracle=candidate.oracle();
                PreparationContracts.require(oracle.reviewRef()!=null&&!oracle.reviewRef().isBlank()&&oracle.reviewedBy()!=null&&!oracle.reviewedBy().isBlank()&&!proposalAuthorRef.equals(oracle.reviewedBy()),ORACLE_REVIEW_MISSING);
                PreparationContracts.ref(oracle.reviewRef());PreparationContracts.ref(oracle.reviewedBy());allowed(oracle.reviewRef());allowed(oracle.reviewedBy());
                for(var citation:candidate.citations()) PreparationContracts.require(citation.contentSha256().equals(selected.get(citation.qualifiedSourceRef())),PROPOSAL_MAPPING_MISMATCH);
            }
            PreparationContracts.require(requirements.equals(request.requirementRefs()),PROPOSAL_MAPPING_MISMATCH);
            var proposal=PreparedTestProposal.create(proof,List.copyOf(candidates),admittedSource);proposal.jsonBytes();
            return new Result(Status.PREPARED_NOT_APPROVED,admittedSource==null?D2_BINDING_API_UNBOUND:SUT_BINDING_UNBOUND,proposal);
        } catch(PreparationContracts.Blocked e) { return new Result(Status.BLOCKED,e.reason,null); }
        catch(Exception e) { return new Result(Status.BLOCKED,INVALID_INPUT,null); }
    }
    private void allowed(String ref) { PreparationContracts.require(approvedPublicReferences.contains(ref),METADATA_NOT_APPROVED); }
    private static void bound(String qualified,ContextRequest request) {
        int at=qualified.indexOf('@'),colon=qualified.indexOf(':',at);String repo=qualified.substring(0,at),commit=qualified.substring(at+1,colon);
        PreparationContracts.require(commit.equals(request.repositoryRevisions().get(repo)),SOURCE_VERSION_MISMATCH);
    }
}

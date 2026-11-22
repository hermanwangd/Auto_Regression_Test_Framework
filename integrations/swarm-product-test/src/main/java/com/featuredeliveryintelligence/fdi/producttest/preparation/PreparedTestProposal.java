package com.featuredeliveryintelligence.fdi.producttest.preparation;

import java.util.*;

/** Prepared metadata remains nonexecutable until D2 admission and independent protected review. */
public record PreparedTestProposal(String schemaVersion,String state,String executionBindingState,String knowledgeMode,
                                  SelectionProof selectionProof,List<ProposalTrace> testCases,String proposalSha256,SourceBindingProof sourceBindings) {
    public PreparedTestProposal(String schemaVersion,String state,String executionBindingState,String knowledgeMode,SelectionProof proof,List<ProposalTrace> cases,String digest) {
        this(schemaVersion,state,executionBindingState,knowledgeMode,proof,cases,digest,null);
    }
    public record SourceBindingProof(String schemaVersion,String metadataSha256,String producerJarSha256,String producerSchemaSha256,
                                     String lockSha256,String trustedRootsSha256,Map<String,String> compositionRepositoryRevisions) {
        public SourceBindingProof { compositionRepositoryRevisions=Map.copyOf(compositionRepositoryRevisions); }
    }
    public PreparedTestProposal {
        PreparationContracts.require("prepared-test-proposal.v1".equals(schemaVersion)&&"prepared_not_approved".equals(state)
                &&(sourceBindings==null?"D2_BINDING_API_UNBOUND":"SOURCE_ADMITTED_EXECUTION_NOT_EVALUATED").equals(executionBindingState)&&"required".equals(knowledgeMode),ContextAcquisition.Reason.INVALID_INPUT);
        PreparationContracts.require(testCases!=null&&!testCases.isEmpty()&&testCases.size()<=512,ContextAcquisition.Reason.INVALID_INPUT);
        testCases=List.copyOf(testCases);PreparationContracts.sha(proposalSha256);
    }
    static PreparedTestProposal create(SelectionProof proof,List<ProposalTrace> cases) { return create(proof,cases,null); }
    static PreparedTestProposal create(SelectionProof proof,List<ProposalTrace> cases,D2SourceBindingsClient.Admitted admitted) {
        String execution=admitted==null?"D2_BINDING_API_UNBOUND":"SOURCE_ADMITTED_EXECUTION_NOT_EVALUATED";
        SourceBindingProof source=admitted==null?null:new SourceBindingProof("runner-source-bindings.v1",admitted.metadataSha256(),admitted.producerPins().jarSha256(),admitted.producerPins().schemaSha256(),admitted.producerPins().lockSha256(),admitted.producerPins().rootsSha256(),admitted.currentRepositoryRevisions());
        var body=new TreeMap<String,Object>();body.put("selection_proof",proof);body.put("test_cases",cases);body.put("state","prepared_not_approved");body.put("knowledge_mode","required");body.put("execution_binding_state",execution);if(source!=null)body.put("source_bindings",source);
        String digest=PreparationContracts.hash(PreparationContracts.bytes(PreparationContracts.canonical(PreparationContracts.read(PreparationContracts.bytes(body,1048576),1048576)),1048576));
        return new PreparedTestProposal("prepared-test-proposal.v1","prepared_not_approved",execution,"required",proof,cases,digest,source);
    }
    public byte[] jsonBytes() { return PreparationContracts.bytes(this,1048576); }
}

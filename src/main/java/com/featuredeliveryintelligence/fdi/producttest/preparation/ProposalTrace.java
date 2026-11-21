package com.featuredeliveryintelligence.fdi.producttest.preparation;

import java.util.*;

/** Qualified citation/oracle relationships, not D2 artifact bindings or executable source copies. */
public record ProposalTrace(String testCaseId, String qualifiedCaseRef, String caseSha256,
        Set<String> requirementRefs, Set<String> acceptanceRefs, Set<String> riskRefs, Set<String> apiRefs,
        Set<String> codeRefs, List<Citation> citations, OracleRef oracle) {
    public ProposalTrace {
        PreparationContracts.require(testCaseId!=null&&testCaseId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"),ContextAcquisition.Reason.INVALID_INPUT);
        PreparationContracts.qualified(qualifiedCaseRef); PreparationContracts.sha(caseSha256);
        requirementRefs=refs(requirementRefs);acceptanceRefs=refs(acceptanceRefs);riskRefs=refs(riskRefs);apiRefs=refs(apiRefs);codeRefs=refs(codeRefs);
        PreparationContracts.require(citations!=null&&!citations.isEmpty()&&citations.size()<=15,ContextAcquisition.Reason.INVALID_INPUT);
        citations=List.copyOf(citations);
        PreparationContracts.require(!requirementRefs.isEmpty()&&!acceptanceRefs.isEmpty()&&!citations.isEmpty()&&citations.size()<=15&&oracle!=null,ContextAcquisition.Reason.INVALID_INPUT);
    }
    private static Set<String> refs(Set<String> input) { PreparationContracts.require(input!=null&&input.size()<=512,ContextAcquisition.Reason.OUTPUT_LIMIT);var result=Set.copyOf(input);result.forEach(PreparationContracts::qualified);return result; }
    public record Citation(String qualifiedSourceRef,String contentSha256) {
        public Citation { PreparationContracts.qualified(qualifiedSourceRef);PreparationContracts.sha(contentSha256); }
    }
    public record OracleRef(String qualifiedRef,String sha256,String reviewRef,String reviewedBy) {
        public OracleRef { PreparationContracts.qualified(qualifiedRef);PreparationContracts.sha(sha256); }
    }
}

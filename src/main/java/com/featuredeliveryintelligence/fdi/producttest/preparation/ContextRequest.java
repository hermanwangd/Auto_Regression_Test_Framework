package com.featuredeliveryintelligence.fdi.producttest.preparation;

import java.util.*;

/** Correlation and required source scope only; never carries endpoint, credential or authorization. */
public record ContextRequest(String missionRef, String workspaceRef, String knowledgeProjectRef,
                             String consumerProjectRef, Map<String,String> repositoryRevisions,
                             Set<String> requirementRefs) {
    public ContextRequest {
        PreparationContracts.ref(missionRef); PreparationContracts.ref(workspaceRef);
        PreparationContracts.ref(knowledgeProjectRef); PreparationContracts.ref(consumerProjectRef);
        PreparationContracts.require(repositoryRevisions!=null&&!repositoryRevisions.isEmpty()&&repositoryRevisions.size()<=64,ContextAcquisition.Reason.INVALID_INPUT);
        repositoryRevisions = Map.copyOf(repositoryRevisions);
        PreparationContracts.require(!repositoryRevisions.isEmpty() && repositoryRevisions.size() <= 64, ContextAcquisition.Reason.INVALID_INPUT);
        repositoryRevisions.forEach((repo, sha) -> { PreparationContracts.ref(repo); PreparationContracts.require(sha.matches("[a-f0-9]{40}|[a-f0-9]{64}"), ContextAcquisition.Reason.INVALID_INPUT); });
        PreparationContracts.require(requirementRefs!=null&&!requirementRefs.isEmpty()&&requirementRefs.size()<=512,ContextAcquisition.Reason.INVALID_INPUT);
        requirementRefs = Set.copyOf(requirementRefs);
        PreparationContracts.require(!requirementRefs.isEmpty() && requirementRefs.size() <= 512, ContextAcquisition.Reason.INVALID_INPUT);
        requirementRefs.forEach(PreparationContracts::qualified);
    }
    public String knowledgeMode() { return "required"; }
}

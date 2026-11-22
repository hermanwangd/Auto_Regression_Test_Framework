package com.featuredeliveryintelligence.fdi.producttest.preparation;

import java.time.Instant;
import java.util.*;

/** Closed metadata projection; its bytes and original private receipt have DISTINCT hashes. */
public record SelectionProof(String schemaVersion, String privateOriginalReceiptSha256, String selectionReceiptSha256,
        String sourceSnapshotSha256, String candidateSearchSha256, String querySha256, String contextSha256, String providerVersion,
        ContextRequest scope, Instant fetchedAt, Instant evaluatedAt, Instant validUntil, List<Source> selected, List<Exclusion> excluded) {
    public SelectionProof {
        PreparationContracts.require("knowledge-selection-proof.v1".equals(schemaVersion),ContextAcquisition.Reason.INVALID_INPUT);
        for (String v : List.of(privateOriginalReceiptSha256,selectionReceiptSha256,sourceSnapshotSha256,candidateSearchSha256,querySha256,contextSha256)) PreparationContracts.sha(v);
        PreparationContracts.require("UNVERIFIED_BY_HTTP_CLIENT".equals(providerVersion),ContextAcquisition.Reason.INVALID_INPUT);
        PreparationContracts.require(selected!=null&&!selected.isEmpty()&&selected.size()<=15&&excluded!=null&&excluded.size()<=256,ContextAcquisition.Reason.INVALID_INPUT);
        selected=List.copyOf(selected); excluded=List.copyOf(excluded);
        PreparationContracts.require(scope!=null && fetchedAt!=null && evaluatedAt!=null && validUntil!=null && !selected.isEmpty() && selected.size()<=15 && excluded.size()<=256,ContextAcquisition.Reason.INVALID_INPUT);
    }
    public record Source(String qualifiedSourceRef, String governedRecordKey, int recordVersion, String providerRevision,
            String sourceRef, String contentSha256, String ownerRef, String knowledgeRef, String proposalDigest,
            String decisionRef, String decisionRecordKey, int decisionRecordVersion, String decisionProposalDigest,
            Instant observedAt, Instant validUntil, String documentId, int chunkCount, String datasetId) {}
    public record Exclusion(String recordKey, String knowledgeRef, String reason) {}
    public byte[] jsonBytes() { return PreparationContracts.bytes(this,262144); }
    public String projectionSha256() { return PreparationContracts.hash(jsonBytes()); }
}

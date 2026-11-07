package com.featuredeliveryintelligence.fdi.producttest.preparation;

import java.util.Optional;

public final class ContextAcquisition implements AutoCloseable {
    public enum Status { READY, BLOCKED }
    public enum Reason { READY, REQUIRED_CONTEXT_MISSING, DENIED, EMPTY, UNAVAILABLE, PARTIAL, EXPIRED, CONFLICT,
        UNSELECTED, TAMPERED, SCOPE_MISMATCH, SOURCE_VERSION_MISMATCH, PK_SOURCE_CONTENT_UNBOUND,
        SOURCE_OWNER_UNBOUND, CONTENT_DIGEST_MISMATCH, METADATA_NOT_APPROVED, INVALID_INPUT, OUTPUT_LIMIT,
        D2_BINDING_API_UNBOUND, PRODUCER_PIN_MISMATCH, SOURCE_BINDING_MISMATCH, SUT_BINDING_UNBOUND, PROCESS_TIMEOUT, PROCESS_FAILED, CLEANUP_UNCONFIRMED, ORACLE_REVIEW_MISSING, PROPOSAL_MAPPING_MISMATCH, CONTEXT_CLOSED }
    private final Status status;
    private final Reason reason;
    private final PinnedKnowledgeContext context;
    private final SelectionProof proof;
    private ContextAcquisition(Status status, Reason reason, PinnedKnowledgeContext context, SelectionProof proof) {
        this.status=status; this.reason=reason; this.context=context; this.proof=proof;
    }
    public static ContextAcquisition blocked(Reason reason) {
        if (reason == null || reason == Reason.READY) throw new IllegalArgumentException("INVALID_INPUT");
        return new ContextAcquisition(Status.BLOCKED, reason, null, null);
    }
    public static ContextAcquisition ready(PinnedKnowledgeContext context, SelectionProof proof) {
        if (context == null || proof == null || !context.digest().equals(proof.contextSha256())) throw new IllegalArgumentException("INVALID_INPUT");
        return new ContextAcquisition(Status.READY, Reason.READY, context, proof);
    }
    public Status status() { return status; }
    public Reason reason() { return reason; }
    public String knowledgeMode() { return "required"; }
    public Optional<PinnedKnowledgeContext> context() { return Optional.ofNullable(context); }
    public Optional<SelectionProof> proof() { return Optional.ofNullable(proof); }
    @Override public void close() { if (context != null) context.close(); }
    @Override public String toString() { return "ContextAcquisition[" + status + "," + reason + "]"; }
}

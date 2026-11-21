package com.featuredeliveryintelligence.fdi.producttest.preparation;

/** Preparation-only acquisition. There is deliberately no provider execution or optional downgrade method. */
@FunctionalInterface
public interface KnowledgeContextPort {
    ContextAcquisition acquire(ContextRequest request);
    static KnowledgeContextPort unavailable() { return request -> ContextAcquisition.blocked(ContextAcquisition.Reason.REQUIRED_CONTEXT_MISSING); }
}

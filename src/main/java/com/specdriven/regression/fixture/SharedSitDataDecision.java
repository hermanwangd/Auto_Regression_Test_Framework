package com.specdriven.regression.fixture;

import com.specdriven.regression.sit.SitReadinessState;
import java.util.List;

/** Metadata assessment only. This type never authorizes provider effects or shared SIT execution. */
public record SharedSitDataDecision(
        ContractState contractState, Scope scope, Acceptance acceptance,
        SitReadinessState readinessState, OwnershipState ownershipState, CleanupState cleanupState,
        CanonicalIdentityState canonicalIdentityState, String canonicalVerdict, String canonicalSha256,
        String contractSha256, String controllerSha256, String idempotencyKey,
        OriginState contractOriginState, boolean persistentSharedOwnershipBound,
        boolean preCleanupSaveBound, List<String> gaps) {
    public enum ContractState { VALID, INVALID }
    public enum Scope { CONTRACT, ISOLATED_ONLY, SHARED_BLOCKED }
    public enum Acceptance { CONTRACT_ONLY, BLOCKED_INPUT, BLOCKED_PRECONDITIONS,
        BLOCKED_NO_SHARED_OWNERSHIP_OR_PRECLEANUP_SAVE_BINDING }
    public enum OwnershipState { UNKNOWN, LOCAL_FAKE }
    public enum CleanupState { NOT_RUN, CONFIRMED, UNKNOWN }
    public enum CanonicalIdentityState { MATCHED, MISSING, INVALID }
    public enum OriginState { UNBOUND }
    public SharedSitDataDecision {
        gaps = List.copyOf(gaps);
        if (persistentSharedOwnershipBound || preCleanupSaveBound || contractOriginState != OriginState.UNBOUND)
            throw new IllegalArgumentException("Stage A has no persistent or product authority");
        if (scope == Scope.SHARED_BLOCKED && acceptance != Acceptance.BLOCKED_NO_SHARED_OWNERSHIP_OR_PRECLEANUP_SAVE_BINDING)
            throw new IllegalArgumentException("Shared execution is unavailable in Stage A");
    }
}

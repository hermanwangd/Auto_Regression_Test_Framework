package com.specdriven.regression.sit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Synthetic resource ownership shared only by callers of this store in the same JVM.
 * This is not a distributed lease or a boundary for real provider resources. Every
 * conditional effect checks its token and replaces immutable state under the same
 * namespace lock; no caller can separate permission checking from the mutation.
 */
public final class LocalFakeSitOwnership {
    public enum State { UNCLAIMED, OWNED, RETAINED, RELEASED }

    private final ConcurrentHashMap<Namespace, Slot> namespaces = new ConcurrentHashMap<>();

    /** An opaque, identity-compared capability issued only by this store. */
    public static final class Token {
        private final LocalFakeSitOwnership store;
        private final Namespace namespace;
        private final String owner;
        private final String runId;

        private Token(LocalFakeSitOwnership store, Namespace namespace, String owner, String runId) {
            this.store = store;
            this.namespace = namespace;
            this.owner = owner;
            this.runId = runId;
        }

        @Override public String toString() { return "LocalFakeSitOwnership.Token[opaque]"; }
    }

    public record Resource(String owner, String runId, String data) { }

    /** Immutable observation, deliberately excluding ownership tokens. */
    public record Snapshot(State state, String owner, String runId, Map<String, Resource> resources) {
        public Snapshot { resources = Map.copyOf(resources); }
    }

    /** Claims an unclaimed or released namespace once, without retry or takeover. */
    public Token claim(String environmentId, String namespace, String owner, String runId) {
        Namespace key = namespace(environmentId, namespace);
        requireText(owner, "owner");
        requireText(runId, "runId");
        Slot slot = namespaces.computeIfAbsent(key, ignored -> new Slot());
        synchronized (slot) {
            if (slot.current.state() == State.OWNED) throw new IllegalStateException("NAMESPACE_OWNED");
            if (slot.current.state() == State.RETAINED) throw new IllegalStateException("NAMESPACE_RETAINED");
            Token token = new Token(this, key, owner, runId);
            slot.current = new NamespaceState(State.OWNED, owner, runId, token, Map.of());
            return token;
        }
    }

    public void create(Token token, String resourceKey, String data) {
        requireText(resourceKey, "resourceKey");
        Objects.requireNonNull(data, "data");
        Slot slot = slotFor(token);
        synchronized (slot) {
            NamespaceState current = requireOwned(slot, token);
            if (current.resources().containsKey(resourceKey)) throw new IllegalStateException("RESOURCE_EXISTS");
            Map<String, OwnedResource> resources = new LinkedHashMap<>(current.resources());
            resources.put(resourceKey, new OwnedResource(token, new Resource(current.owner(), current.runId(), data)));
            slot.current = current.withResources(resources);
        }
    }

    public void write(Token token, String resourceKey, String data) {
        requireText(resourceKey, "resourceKey");
        Objects.requireNonNull(data, "data");
        Slot slot = slotFor(token);
        synchronized (slot) {
            NamespaceState current = requireOwned(slot, token);
            OwnedResource resource = current.resources().get(resourceKey);
            if (resource == null) throw new IllegalStateException("RESOURCE_MISSING");
            requireResourceOwner(resource, token);
            Map<String, OwnedResource> resources = new LinkedHashMap<>(current.resources());
            resources.put(resourceKey, new OwnedResource(token, new Resource(current.owner(), current.runId(), data)));
            slot.current = current.withResources(resources);
        }
    }

    public boolean delete(Token token, String resourceKey) {
        requireText(resourceKey, "resourceKey");
        Slot slot = slotFor(token);
        synchronized (slot) {
            NamespaceState current = requireOwned(slot, token);
            OwnedResource resource = current.resources().get(resourceKey);
            if (resource == null) return false;
            requireResourceOwner(resource, token);
            Map<String, OwnedResource> resources = new LinkedHashMap<>(current.resources());
            resources.remove(resourceKey);
            slot.current = current.withResources(resources);
            return true;
        }
    }

    /** Removes only this exact claim's recorded resources, with no wildcard or prefix matching. */
    public int cleanup(Token token) {
        Slot slot = slotFor(token);
        synchronized (slot) {
            NamespaceState current = requireOwned(slot, token);
            for (OwnedResource resource : current.resources().values()) requireResourceOwner(resource, token);
            int removed = current.resources().size();
            slot.current = current.withResources(Map.of());
            return removed;
        }
    }

    /**
     * Finalizes without performing cleanup. With cleanup:none, callers leave resources
     * in place and they are retained. Any cleanup failure retains the claim even if
     * empty. Retained claims have no reclaim or resume path in this local proof.
     */
    public Snapshot finish(Token token, boolean cleanupFailed) {
        Slot slot = slotFor(token);
        synchronized (slot) {
            NamespaceState current = requireOwned(slot, token);
            State finalState = cleanupFailed || !current.resources().isEmpty() ? State.RETAINED : State.RELEASED;
            slot.current = new NamespaceState(finalState, current.owner(), current.runId(), token, current.resources());
            return slot.current.snapshot();
        }
    }

    public Snapshot snapshot(String environmentId, String namespace) {
        Slot slot = namespaces.get(namespace(environmentId, namespace));
        if (slot == null) return new Snapshot(State.UNCLAIMED, "", "", Map.of());
        synchronized (slot) {
            return slot.current.snapshot();
        }
    }

    /** Point-in-time read-only observation; never authorizes a later mutation. */
    public void assertCurrent(Token token) {
        Slot slot = slotFor(token);
        synchronized (slot) {
            requireOwned(slot, token);
        }
    }

    private Slot slotFor(Token token) {
        if (token == null || token.store != this) throw ownershipLost();
        Slot slot = namespaces.get(token.namespace);
        if (slot == null) throw ownershipLost();
        return slot;
    }

    /** Must be called only while holding the namespace slot used for the effect. */
    private NamespaceState requireOwned(Slot slot, Token token) {
        NamespaceState current = slot.current;
        if (current.state() != State.OWNED || current.token() != token
                || !current.owner().equals(token.owner) || !current.runId().equals(token.runId)) {
            throw ownershipLost();
        }
        return current;
    }

    private static void requireResourceOwner(OwnedResource resource, Token token) {
        if (resource.token() != token || !resource.value().owner().equals(token.owner)
                || !resource.value().runId().equals(token.runId)) {
            throw ownershipLost();
        }
    }

    private static IllegalStateException ownershipLost() {
        return new IllegalStateException("OWNERSHIP_LOST");
    }

    private static Namespace namespace(String environmentId, String namespace) {
        requireText(environmentId, "environmentId");
        requireText(namespace, "namespace");
        return new Namespace(environmentId, namespace);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private record Namespace(String environmentId, String namespace) { }

    private record OwnedResource(Token token, Resource value) { }

    private static final class Slot {
        private NamespaceState current = new NamespaceState(State.UNCLAIMED, "", "", null, Map.of());
    }

    private record NamespaceState(State state, String owner, String runId, Token token,
                                  Map<String, OwnedResource> resources) {
        private NamespaceState { resources = Map.copyOf(resources); }

        private NamespaceState withResources(Map<String, OwnedResource> resources) {
            return new NamespaceState(state, owner, runId, token, resources);
        }

        private Snapshot snapshot() {
            Map<String, Resource> values = new LinkedHashMap<>();
            resources.forEach((key, resource) -> values.put(key, resource.value()));
            return new Snapshot(state, owner, runId, values);
        }
    }
}

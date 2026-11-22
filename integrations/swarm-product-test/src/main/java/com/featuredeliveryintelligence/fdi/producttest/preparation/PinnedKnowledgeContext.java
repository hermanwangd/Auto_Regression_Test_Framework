package com.featuredeliveryintelligence.fdi.producttest.preparation;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Owned preparation lifecycle. Content is never a member of a runner request, proof or proposal. */
@JsonSerialize(using = PinnedKnowledgeContext.PrivateSerializer.class)
public final class PinnedKnowledgeContext implements AutoCloseable {
    private final Map<String,byte[]> bytes = new LinkedHashMap<>();
    private final Map<String,SourceIdentity> identities;
    private final Instant validUntil;
    private final String digest;
    private boolean closed;
    public record SourceIdentity(String qualifiedRef, String sourceRef, String ownerRef, String contentSha256) {}
    private PinnedKnowledgeContext(List<PinnedSourceContentPort.Content> sources, Instant validUntil) {
        PreparationContracts.require(sources != null && !sources.isEmpty() && sources.size() <= 15 && validUntil != null, ContextAcquisition.Reason.INVALID_INPUT);
        this.validUntil=validUntil;
        var ids = new LinkedHashMap<String,SourceIdentity>(); int total=0;
        for (var source : sources) {
            byte[] content=source.bytes(); total+=content.length;
            PreparationContracts.require(total <= 262144, ContextAcquisition.Reason.OUTPUT_LIMIT);
            PreparationContracts.require(!ids.containsKey(source.qualifiedRef()) && ids.values().stream().noneMatch(v -> v.sourceRef().equals(source.sourceRef())), ContextAcquisition.Reason.CONFLICT);
            bytes.put(source.qualifiedRef(), content);
            ids.put(source.qualifiedRef(), new SourceIdentity(source.qualifiedRef(),source.sourceRef(),source.ownerRef(),PreparationContracts.hash(content)));
        }
        identities=Collections.unmodifiableMap(ids);
        digest=PreparationContracts.hash(PreparationContracts.bytes(new TreeMap<>(ids),524288));
    }
    public static PinnedKnowledgeContext capture(List<PinnedSourceContentPort.Content> sources, Instant validUntil) { return new PinnedKnowledgeContext(sources,validUntil); }
    public synchronized byte[] sourceBytes(String qualifiedRef) { open(); byte[] value=bytes.get(qualifiedRef); PreparationContracts.require(value!=null,ContextAcquisition.Reason.UNSELECTED); return value.clone(); }
    public synchronized Map<String,SourceIdentity> identities() { open(); return identities; }
    public synchronized String digest() { open(); return digest; }
    public synchronized Instant validUntil() { open(); return validUntil; }
    private void open() { PreparationContracts.require(!closed,ContextAcquisition.Reason.CONTEXT_CLOSED); }
    @Override public synchronized void close() { if (!closed) { bytes.values().forEach(v -> Arrays.fill(v,(byte)0)); bytes.clear(); closed=true; } }
    @Override public String toString() { return "PinnedKnowledgeContext[private]"; }
    public static final class PrivateSerializer extends JsonSerializer<PinnedKnowledgeContext> {
        @Override public void serialize(PinnedKnowledgeContext value, JsonGenerator out, SerializerProvider provider) throws IOException { throw new IOException("PRIVATE_CONTEXT_SERIALIZATION_FORBIDDEN"); }
    }
}

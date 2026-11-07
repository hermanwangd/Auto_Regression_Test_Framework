package com.featuredeliveryintelligence.fdi.producttest.preparation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static com.featuredeliveryintelligence.fdi.producttest.preparation.ContextAcquisition.Reason.*;

/** Preparation-only adapter to unchanged actual-host selection and exact private receipt replay. */
public final class HostKnowledgeContextPort implements KnowledgeContextPort {
    public interface HostSession {
        /** Private fresh governed provider/query snapshot from trusted composition, at most 4 MiB. */
        byte[] sourceEvidence(ContextRequest request);
        /** Owned isolated bridge process calls original Dev204Cli with separately injected authorization. */
        byte[] select(byte[] sourceEvidence);
        /** Original host replay must verify exact input+receipt, with no retrieval or adoption claim. */
        void replay(byte[] sourceEvidence,byte[] originalReceipt);
    }
    private final HostSession session;
    private final PinnedSourceContentPort content;
    private final Set<String> publicReferences;
    private final Clock clock;
    public HostKnowledgeContextPort(HostSession session,PinnedSourceContentPort content,Set<String> approvedPublicReferences,Clock clock) {
        this.session=Objects.requireNonNull(session);this.content=Objects.requireNonNull(content);this.publicReferences=Set.copyOf(approvedPublicReferences);this.clock=Objects.requireNonNull(clock);
    }
    @Override public ContextAcquisition acquire(ContextRequest request) {
        byte[] input=null,original=null;PinnedKnowledgeContext context=null;
        try {
            byte[] suppliedInput=session.sourceEvidence(request);PreparationContracts.require(suppliedInput!=null&&suppliedInput.length>0&&suppliedInput.length<=4*1024*1024,OUTPUT_LIMIT);input=suppliedInput.clone();JsonNode source=PreparationContracts.read(input,4*1024*1024);
            JsonNode scope=source.path("consumerRequest");
            PreparationContracts.require(request.missionRef().equals(scope.path("missionRef").asText())&&request.workspaceRef().equals(scope.path("workspaceRef").asText())
                    &&request.knowledgeProjectRef().equals(scope.path("knowledgeProjectRef").asText())&&request.consumerProjectRef().equals(scope.path("consumerProjectRef").asText())
                    &&PreparationContracts.JSON.valueToTree(request.repositoryRevisions()).equals(scope.path("currentRepositoryRevisions")),SCOPE_MISMATCH);
            JsonNode query=source.get("cogneeQuery");PreparationContracts.require(query!=null&&query.isTextual()&&!query.asText().isBlank(),INVALID_INPUT);
            PreparationContracts.require(query.asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=4096,OUTPUT_LIMIT);
            byte[] suppliedReceipt=session.select(input.clone());PreparationContracts.require(suppliedReceipt!=null&&suppliedReceipt.length>0&&suppliedReceipt.length<=4*1024*1024,OUTPUT_LIMIT);original=suppliedReceipt.clone();JsonNode receipt=PreparationContracts.read(original,4*1024*1024);
            String status=receipt.path("status").asText("");
            if(status.contains("UNAVAILABLE"))throw PreparationContracts.fail(UNAVAILABLE);
            if("PARTIAL".equals(receipt.path("providerMappingStatus").asText()))throw PreparationContracts.fail(PARTIAL);
            JsonNode rawContext=receipt.path("context"),search=receipt.path("cogneeSelection").path("candidateSearch");
            if(search.path("rejections").isArray()&&!search.path("rejections").isEmpty())throw PreparationContracts.fail(PARTIAL);
            if(!"ELIGIBLE_CONTEXT".equals(status)||!rawContext.path("selected").isArray()||rawContext.path("selected").isEmpty()) {
                for(JsonNode excluded:rawContext.path("excluded")) {
                    String reason=excluded.path("reason").asText();
                    if("STALE".equals(reason)||"FRESHNESS_UNKNOWN".equals(reason))throw PreparationContracts.fail(EXPIRED);
                    if("UNRESOLVED_CONFLICT".equals(reason)||"DUPLICATE_RECORD_KEY".equals(reason))throw PreparationContracts.fail(CONFLICT);
                    if("NOT_APPROVED".equals(reason)||"VISIBILITY_NOT_AUTHORIZED".equals(reason))throw PreparationContracts.fail(DENIED);
                }
                if(search.path("candidates").isArray()&&!search.path("candidates").isEmpty())throw PreparationContracts.fail(UNSELECTED);
                throw PreparationContracts.fail(EMPTY);
            }
            try { session.replay(input.clone(),original.clone()); } catch(Exception e) { throw PreparationContracts.fail(TAMPERED); }
            // Replay is complete before private source resolution and projection. Bind exact verified bytes.
            final String verifiedSha=PreparationContracts.hash(original);
            PreparationContracts.require(rawContext.path("selected").size()<=15&&search.path("candidates").isArray()&&search.path("candidates").size()<=15,OUTPUT_LIMIT);
            Instant valid=clock.instant().plusSeconds(600);List<PinnedSourceContentPort.Content> sources=new ArrayList<>();
            for(JsonNode selected:rawContext.path("selected")) {
                Instant expiry=Instant.parse(selected.path("validUntil").asText());if(expiry.isBefore(valid))valid=expiry;
                var candidates=new ArrayList<JsonNode>();for(JsonNode candidate:search.path("candidates"))if(selected.path("recordKey").asText().equals(candidate.path("sourceId").asText()))candidates.add(candidate);
                PreparationContracts.require(candidates.size()==1,UNSELECTED);
                PinnedSourceContentPort.Content resolved=content.resolve(candidates.get(0).path("sourceRef").asText());
                PreparationContracts.require(resolved!=null,PK_SOURCE_CONTENT_UNBOUND);sources.add(resolved);
            }
            context=PinnedKnowledgeContext.capture(sources,valid);
            var projector=new SelectionProofProjector(bytes->PreparationContracts.require(verifiedSha.equals(PreparationContracts.hash(bytes)),TAMPERED),publicReferences,clock);
            var proof=projector.project(request,original,context);return ContextAcquisition.ready(context,proof);
        } catch(PreparationContracts.Blocked e) { if(context!=null)context.close();return ContextAcquisition.blocked(e.reason); }
        catch(Exception e) { if(context!=null)context.close();return ContextAcquisition.blocked(UNAVAILABLE); }
        finally { if(input!=null)Arrays.fill(input,(byte)0);if(original!=null)Arrays.fill(original,(byte)0); }
    }
}

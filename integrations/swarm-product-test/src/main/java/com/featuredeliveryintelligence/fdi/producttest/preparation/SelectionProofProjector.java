package com.featuredeliveryintelligence.fdi.producttest.preparation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import static com.featuredeliveryintelligence.fdi.producttest.preparation.ContextAcquisition.Reason.*;

/** Verify private receipt during preparation; serialize only an explicit public-reference allowlist. */
public final class SelectionProofProjector {
    @FunctionalInterface public interface ReceiptVerifier {
        /** Must replay exact source/query/candidate snapshot through the actual trusted host before returning. */
        void verify(byte[] originalReceipt);
    }
    private final ReceiptVerifier verifier;
    private final Set<String> publicReferences;
    private final Clock clock;
    private static final Set<String> EXCLUSIONS=Set.of("READ_SCOPE_MISMATCH","IDENTITY_INCOMPLETE","IDENTITY_MISMATCH",
        "WRONG_WORKSPACE","DUPLICATE_RECORD_KEY","PROJECT_NOT_APPLICABLE","VISIBILITY_NOT_AUTHORIZED",
        "GOVERNANCE_INCOMPLETE","NOT_APPROVED","LIFECYCLE_NOT_CURRENT","UNRESOLVED_CONFLICT","DIGEST_MISSING",
        "DIGEST_MISMATCH","DECISION_BINDING_MISMATCH","FRESHNESS_UNKNOWN","STALE","SOURCE_REVISION_MISMATCH","NOT_RETRIEVED");
    public SelectionProofProjector(ReceiptVerifier verifier, Set<String> approvedPublicReferences, Clock clock) {
        this.verifier=Objects.requireNonNull(verifier); this.publicReferences=Set.copyOf(approvedPublicReferences); this.clock=Objects.requireNonNull(clock);
    }
    public SelectionProof project(ContextRequest request, byte[] originalReceipt, PinnedKnowledgeContext context) {
        Objects.requireNonNull(request); Objects.requireNonNull(context);
        PreparationContracts.require(originalReceipt!=null&&originalReceipt.length>0&&originalReceipt.length<=4*1024*1024,OUTPUT_LIMIT);
        byte[] original=originalReceipt.clone();
        JsonNode receipt=PreparationContracts.read(original,4*1024*1024);
        try { verifier.verify(original.clone()); }
        catch(Exception e) { throw PreparationContracts.fail(TAMPERED); }
        eq("selection",text(receipt,"phase"),INVALID_INPUT);
        String status=text(receipt,"status");
        if(status.contains("UNAVAILABLE")) throw PreparationContracts.fail(UNAVAILABLE);
        PreparationContracts.require("ELIGIBLE_CONTEXT".equals(status),EMPTY);
        PreparationContracts.require(!receipt.has("providerMappingStatus") || "COMPLETE".equals(text(receipt,"providerMappingStatus")),PARTIAL);
        verifyDigest(receipt,"selectionReceiptSha256");
        JsonNode rawContext=receipt.path("context"), scope=rawContext.path("request");
        eq(request.missionRef(),text(scope,"missionRef"),SCOPE_MISMATCH);
        eq(request.workspaceRef(),text(scope,"workspaceRef"),SCOPE_MISMATCH);
        eq(request.knowledgeProjectRef(),text(scope,"knowledgeProjectRef"),SCOPE_MISMATCH);
        eq(request.consumerProjectRef(),text(scope,"consumerProjectRef"),SCOPE_MISMATCH);
        eq(request.workspaceRef(),text(rawContext,"readWorkspaceRef"),SCOPE_MISMATCH);
        eq(request.knowledgeProjectRef(),text(rawContext,"readProjectRef"),SCOPE_MISMATCH);
        eq(PreparationContracts.JSON.valueToTree(request.repositoryRevisions()),scope.path("currentRepositoryRevisions"),SOURCE_VERSION_MISMATCH);
        Instant now=clock.instant(), fetched=instant(rawContext,"fetchedAt"),evaluated=instant(rawContext,"evaluatedAt");
        PreparationContracts.require(!fetched.isAfter(evaluated) && !evaluated.isAfter(now),EXPIRED);
        PreparationContracts.require(context.validUntil().isAfter(now) && !context.validUntil().isAfter(now.plusSeconds(600)),EXPIRED);
        JsonNode trace=receipt.path("cogneeSelection");
        eq("CHUNKS",text(trace,"searchType"),INVALID_INPUT);eq("SEARCHED",text(trace,"searchStatus"),UNAVAILABLE);
        eq("UNVERIFIED_BY_HTTP_CLIENT",text(trace,"providerVersion"),INVALID_INPUT);
        PreparationContracts.require(number(trace,"topK")==5,INVALID_INPUT);verifyDigest(trace,"candidateSearchSha256");
        String dataset=text(trace,"datasetId");try { eq(UUID.fromString(dataset).toString(),dataset,INVALID_INPUT); } catch(IllegalArgumentException e) { throw PreparationContracts.fail(INVALID_INPUT); }
        var candidates=array(trace.path("candidateSearch"),"candidates",15,false);
        PreparationContracts.require(array(trace.path("candidateSearch"),"rejections",256,true).isEmpty(),PARTIAL);
        var selected=array(rawContext,"selected",15,false);var excluded=array(rawContext,"excluded",256,true);
        Set<String> excludedKeys=new HashSet<>(),excludedRefs=new HashSet<>();List<SelectionProof.Exclusion> projectedExclusions=new ArrayList<>();
        for(JsonNode e:excluded) {
            String key=e.path("recordKey").asText(""),ref=e.path("knowledgeRef").asText(""),reason=text(e,"reason");
            PreparationContracts.require(EXCLUSIONS.contains(reason),INVALID_INPUT);excludedKeys.add(key);excludedRefs.add(ref);
            projectedExclusions.add(new SelectionProof.Exclusion(publicReferences.contains(key)?key:"",publicReferences.contains(ref)?ref:"",reason));
        }
        var projected=new ArrayList<SelectionProof.Source>();Set<String> keys=new HashSet<>(),knowledgeRefs=new HashSet<>(),usedContent=new HashSet<>();
        for(JsonNode record:selected) {
            String key=text(record,"recordKey"),knowledge=text(record,"knowledgeRef");int version=number(record,"recordVersion");
            PreparationContracts.require(keys.add(key)&&knowledgeRefs.add(knowledge),CONFLICT);
            PreparationContracts.require(!excludedKeys.contains(key)&&!excludedRefs.contains(knowledge),UNSELECTED);
            var matches=candidates.stream().filter(c->key.equals(c.path("sourceId").asText())).toList();
            PreparationContracts.require(matches.size()==1,UNSELECTED);JsonNode candidate=matches.get(0);
            eq(version,number(candidate,"sourceRevision"),SOURCE_VERSION_MISMATCH);
            String provider=text(record,"providerRevision");eq(provider,text(candidate,"providerRevision"),SOURCE_VERSION_MISMATCH);
            eq(dataset,text(candidate,"datasetId"),SCOPE_MISMATCH);eq(request.workspaceRef(),text(candidate,"workspaceRef"),SCOPE_MISMATCH);eq(request.knowledgeProjectRef(),text(candidate,"projectRef"),SCOPE_MISMATCH);
            JsonNode proposal=record.path("proposal"),governance=record.path("governance");
            eq(key,text(proposal,"proposalRef"),UNSELECTED);eq(request.workspaceRef(),text(proposal,"workspaceRef"),SCOPE_MISMATCH);
            eq("CURRENT",text(record,"lifecycle"),UNSELECTED);eq("WORKSPACE_AUTHORIZED",text(record,"visibility"),DENIED);
            eq("APPROVED",text(governance,"decision"),DENIED);eq(proposal,governance.path("proposal"),TAMPERED);
            PreparationContracts.require(array(proposal,"conflictRefs",256,true).isEmpty() && (!record.has("sourceConflictRefs") || array(record,"sourceConflictRefs",256,true).isEmpty()),CONFLICT);
            PreparationContracts.require(!array(governance,"decisionEvidenceRefs",256,false).isEmpty(),DENIED);
            text(governance,"decidedBy");text(governance,"policyRef");instant(governance,"decidedAt");
            eq(PreparationContracts.JSON.valueToTree(request.repositoryRevisions()),record.path("repositoryRevisions"),SOURCE_VERSION_MISMATCH);
            PreparationContracts.require(array(record,"applicableProjectRefs",256,false).stream().anyMatch(v->request.consumerProjectRef().equals(v.asText())),SCOPE_MISMATCH);
            String proposalDigest=hashField(record,"proposalDigest");eq(proposalDigest,PreparationContracts.canonicalHash(proposal),TAMPERED);
            eq(key,text(record,"decisionRecordKey"),TAMPERED);eq(version,number(record,"decisionRecordVersion"),TAMPERED);eq(proposalDigest,hashField(record,"decisionProposalDigest"),TAMPERED);
            Instant observed=instant(record,"observedAt"),expiry=instant(record,"validUntil");
            PreparationContracts.require(!observed.isAfter(fetched)&&expiry.isAfter(observed)&&expiry.isAfter(now)&&!context.validUntil().isAfter(expiry),EXPIRED);
            String sourceRef=text(candidate,"sourceRef");PreparationContracts.require(array(proposal,"sourceRefs",256,false).stream().anyMatch(v->sourceRef.equals(v.asText())),UNSELECTED);
            var contents=context.identities().values().stream().filter(v->sourceRef.equals(v.sourceRef())).toList();
            PreparationContracts.require(contents.size()==1,PK_SOURCE_CONTENT_UNBOUND);var content=contents.get(0);usedContent.add(content.qualifiedRef());
            eq(hashField(candidate,"contentSha256"),content.contentSha256(),CONTENT_DIGEST_MISMATCH);
            String qualified=content.qualifiedRef();int at=qualified.indexOf('@'),colon=qualified.indexOf(':',at);
            eq(request.repositoryRevisions().get(qualified.substring(0,at)),qualified.substring(at+1,colon),SOURCE_VERSION_MISMATCH);
            projected.add(new SelectionProof.Source(allowed(content.qualifiedRef()),allowed(key),version,allowed(provider),allowed(sourceRef),content.contentSha256(),allowed(content.ownerRef()),allowed(knowledge),proposalDigest,
                    allowed(text(governance,"decisionRef")),allowed(key),version,proposalDigest,observed,expiry,allowed(text(candidate,"documentId")),number(candidate,"chunkCount"),allowed(dataset)));
        }
        PreparationContracts.require(usedContent.equals(context.identities().keySet()),UNSELECTED);
        for(String ref:List.of(request.missionRef(),request.workspaceRef(),request.knowledgeProjectRef(),request.consumerProjectRef())) allowed(ref);
        request.repositoryRevisions().keySet().forEach(this::allowed);request.requirementRefs().forEach(this::allowed);
        var proof=new SelectionProof("knowledge-selection-proof.v1",PreparationContracts.hash(original),hashField(receipt,"selectionReceiptSha256"),hashField(receipt,"sourceSnapshotSha256"),hashField(trace,"candidateSearchSha256"),hashField(trace,"querySha256"),context.digest(),"UNVERIFIED_BY_HTTP_CLIENT",request,fetched,evaluated,context.validUntil(),projected,projectedExclusions);
        proof.jsonBytes();return proof;
    }
    private String allowed(String value) { PreparationContracts.require(publicReferences.contains(value),METADATA_NOT_APPROVED); return value; }
    private static String text(JsonNode node,String field) { JsonNode v=node.get(field);PreparationContracts.require(v!=null&&v.isTextual(),INVALID_INPUT);return PreparationContracts.ref(v.asText()); }
    private static int number(JsonNode node,String field) { JsonNode v=node.get(field);PreparationContracts.require(v!=null&&v.isIntegralNumber()&&v.canConvertToInt()&&v.asInt()>0,INVALID_INPUT);return v.asInt(); }
    private static Instant instant(JsonNode node,String field) { try{return Instant.parse(text(node,field));}catch(Exception e){throw PreparationContracts.fail(INVALID_INPUT);} }
    private static String hashField(JsonNode node,String field) { return PreparationContracts.sha(text(node,field)); }
    private static List<JsonNode> array(JsonNode node,String field,int max,boolean empty) { JsonNode v=node.get(field);PreparationContracts.require(v!=null&&v.isArray()&&v.size()<=max&&(empty||!v.isEmpty()),INVALID_INPUT);var out=new ArrayList<JsonNode>();v.forEach(out::add);return out; }
    private static void verifyDigest(JsonNode value,String field) { var body=(ObjectNode)value.deepCopy();String expected=hashField(body,field);body.remove(field);eq(expected,PreparationContracts.canonicalHash(body),TAMPERED); }
    private static void eq(Object expected,Object actual,ContextAcquisition.Reason reason) { PreparationContracts.require(Objects.equals(expected,actual),reason); }
}

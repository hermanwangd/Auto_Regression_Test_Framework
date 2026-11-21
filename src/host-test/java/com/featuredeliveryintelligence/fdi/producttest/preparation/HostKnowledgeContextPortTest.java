package com.featuredeliveryintelligence.fdi.producttest.preparation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.featuredeliveryintelligence.fdi.application.ProductTestKnowledgePreparationBridge;
import com.featuredeliveryintelligence.fdi.orchestration.CogneeSearchClient;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual unchanged host subject + synthetic owned loopback transport. Never native Cognee/Swarm PASS. */
public final class HostKnowledgeContextPortTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static void main(String[] args) throws Exception {
        Path artifacts=Path.of(args[0]);Files.createDirectories(artifacts);
        List<Map<String,Object>> results=new ArrayList<>();
        for(String scenario:List.of("valid","empty","unavailable","denied","partial","expired","conflict","unselected","tampered","source-unbound","source-owner-unbound","content-mismatch","query-limit","metadata-denied","wrong-request")) {
            try(var fixture=new Fixture(scenario)) {
                ContextRequest request=PreparationFixture.request();
                if(scenario.equals("wrong-request"))request=new ContextRequest(request.missionRef(),"fixture:wrong-workspace",request.knowledgeProjectRef(),request.consumerProjectRef(),request.repositoryRevisions(),request.requirementRefs());
                var refs=new HashSet<>(PreparationFixture.publicRefs());if(scenario.equals("metadata-denied"))refs.remove(PreparationFixture.OWNER);
                var port=new HostKnowledgeContextPort(fixture,ref->scenario.equals("source-unbound")?null:new PinnedSourceContentPort.Content(PreparationFixture.qualified(),PreparationFixture.SOURCE,scenario.equals("source-owner-unbound")?"UNBOUND":PreparationFixture.OWNER,(scenario.equals("content-mismatch")?"different source bytes":PreparationFixture.SECRET).getBytes(StandardCharsets.UTF_8)),refs,Clock.systemUTC());
                try(var result=port.acquire(request)) {
                    ContextAcquisition.Reason expected=switch(scenario) {
                        case "valid"->ContextAcquisition.Reason.READY;case "empty"->ContextAcquisition.Reason.EMPTY;
                        case "unavailable"->ContextAcquisition.Reason.UNAVAILABLE;case "denied"->ContextAcquisition.Reason.DENIED;
                        case "partial"->ContextAcquisition.Reason.PARTIAL;case "expired"->ContextAcquisition.Reason.EXPIRED;
                        case "conflict"->ContextAcquisition.Reason.CONFLICT;case "unselected"->ContextAcquisition.Reason.UNSELECTED;
                        case "tampered"->ContextAcquisition.Reason.TAMPERED;case "source-unbound"->ContextAcquisition.Reason.PK_SOURCE_CONTENT_UNBOUND;
                        case "source-owner-unbound"->ContextAcquisition.Reason.SOURCE_OWNER_UNBOUND;case "content-mismatch"->ContextAcquisition.Reason.CONTENT_DIGEST_MISMATCH;case "query-limit"->ContextAcquisition.Reason.OUTPUT_LIMIT;
                        case "metadata-denied"->ContextAcquisition.Reason.METADATA_NOT_APPROVED;case "wrong-request"->ContextAcquisition.Reason.SCOPE_MISMATCH;
                        default->throw new AssertionError("UNKNOWN_SCENARIO");
                    };
                    check(!fixture.lastFailure.contains("BRIDGE_TIMEOUT"),"HARNESS_TRANSPORT_INCOMPLETE_"+scenario+"_"+fixture.lastChild);
                    check(result.reason()==expected,"SCENARIO_"+scenario+"_EXPECTED_"+expected+"_GOT_"+result.reason()+"_"+fixture.lastFailure+"_"+fixture.lastChild);
                    int expectedSearch=Set.of("denied","expired","conflict","wrong-request","query-limit").contains(scenario)?0:1;
                    check(fixture.searches.get()==expectedSearch,"RETRIEVAL_COUNT_"+scenario);
                    if(scenario.equals("valid")) {
                        check(fixture.replays==1,"EXACT_ORIGINAL_NOT_REPLAYED");
                        byte[] caseBytes=("dsl_version: v0.3\ntest_case_id: TC-1\ntitle: Public proposed total read\n"
                                +"execute:\n  - id: read_total\n    target: public_api\n    op: http_request\n    with:\n      request.method: GET\n      request.path: /total\n"
                                +"verify:\n  - id: status\n    type: assertion\n    assert:\n      actual: step://read_total/response.status\n      operator: equals\n      expected: 200\n"
                                +"  - id: total\n    type: assertion\n    assert:\n      actual: step://read_total/response.body\n      operator: json_match\n      expected_ref: artifact://expected_results/public-independent-oracle.json\n").getBytes(StandardCharsets.UTF_8);
                        byte[] oracleBytes="{\"total\":12}\n".getBytes(StandardCharsets.UTF_8);
                        Files.write(artifacts.resolve("public-proposed-case.yaml"),caseBytes);Files.write(artifacts.resolve("public-independent-oracle.json"),oracleBytes);
                        var t=PreparationServiceTest.trace();var concrete=new ProposalTrace(t.testCaseId(),t.qualifiedCaseRef(),PreparationFixture.hash(caseBytes),t.requirementRefs(),t.acceptanceRefs(),t.riskRefs(),t.apiRefs(),t.codeRefs(),t.citations(),new ProposalTrace.OracleRef(t.oracle().qualifiedRef(),PreparationFixture.hash(oracleBytes),t.oracle().reviewRef(),t.oracle().reviewedBy()));
                        var proposal=new PreparationService(r->result,Clock.systemUTC(),PreparationServiceTest.proposalRefs()).prepare(request,"fixture:author",List.of(concrete));
                        check(proposal.status()==PreparationService.Status.PREPARED_NOT_APPROVED,"PROPOSAL_NOT_PREPARED");
                        byte[] proof=result.proof().orElseThrow().jsonBytes(),prepared=proposal.proposal().jsonBytes();
                        check(!new String(proof,StandardCharsets.UTF_8).contains("PRIVATE-CANARY")&&!new String(prepared,StandardCharsets.UTF_8).contains("PRIVATE-CANARY"),"PRIVATE_BODY_EXPORTED");
                        Files.write(artifacts.resolve("prepared-selection-proof.json"),proof);Files.write(artifacts.resolve("prepared-test-proposal.json"),prepared);
                    }
                    results.add(Map.of("scenario",scenario,"status",result.status().name(),"reason",result.reason().name(),"retrieval_operations",fixture.searches.get(),"receipt_replay_operations",fixture.replays,"test_provider_dispatch","NOT_INVOKED_PREPARATION_PHASE","bridge_operations",List.copyOf(fixture.bridgeOperations),"classification","synthetic_public_local_host_contract"));
                }
            }
        }
        Files.writeString(artifacts.resolve("host-contract-results.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("cases",results,"live_cognee","NOT_RUN","native_swarm_actor","NOT_RUN","d2_integration","D2_BINDING_API_UNBOUND")));
        System.out.println("Actual fe803 host preparation contracts: "+results.size()+" passed; live Cognee/native Swarm actor NOT_RUN");
    }
    private static void check(boolean condition,String code) { if(!condition)throw new AssertionError(code); }
    private static final class Fixture implements HostKnowledgeContextPort.HostSession,AutoCloseable {
        final String scenario;final Path privateRoot;final HttpServer server;final AtomicInteger searches=new AtomicInteger();final List<String> bridgeOperations=new ArrayList<>();
        final ObjectNode evidence;int replays;String lastFailure="NONE",lastChild="NONE";
        Fixture(String scenario) throws Exception {
            this.scenario=scenario;privateRoot=Files.createTempDirectory("private-pk-host-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Instant now=Instant.now().minusSeconds(1);var source=PreparationFixture.receipt();var record=(ObjectNode)source.path("context").path("selected").get(0);
            record.put("observedAt",now.minusSeconds(2).toString()).put("validUntil",scenario.equals("expired")?now.minusSeconds(1).toString():now.plusSeconds(120).toString());
            ((ObjectNode)record.path("governance")).put("decidedAt",now.minusSeconds(2).toString());
            if(scenario.equals("conflict"))record.withArray("sourceConflictRefs").add("private:unresolved-conflict");
            evidence=JSON.createObjectNode();evidence.set("consumerRequest",source.path("context").path("request").deepCopy());evidence.put("cogneeQuery",scenario.equals("query-limit")?"q".repeat(4097):PreparationFixture.SECRET);
            var read=evidence.putObject("providerRead");read.put("workspaceRef",PreparationFixture.request().workspaceRef()).put("projectRef",PreparationFixture.request().knowledgeProjectRef()).put("fetchedAt",now.minusSeconds(1).toString());read.putArray("entries").add(record);
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v1/search",exchange->{
                searches.incrementAndGet();JsonNode query=JSON.readTree(exchange.getRequestBody());
                if(!"Bearer SYNTHETIC-TOKEN-CANARY".equals(exchange.getRequestHeaders().getFirst("Authorization"))||!"CHUNKS".equals(query.path("search_type").asText())||query.path("top_k").asInt()!=5||!PreparationFixture.DATASET.equals(query.path("dataset_ids").get(0).asText())){exchange.sendResponseHeaders(400,-1);exchange.close();return;}
                if(scenario.equals("unavailable")){byte[] unsafe="PRIVATE-CANARY SYNTHETIC-TOKEN-CANARY".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(503,unsafe.length);exchange.getResponseBody().write(unsafe);exchange.close();return;}
                var response=JSON.createArrayNode();var hit=response.addObject().put("dataset_id",PreparationFixture.DATASET);var chunks=hit.putArray("search_result");
                if(!scenario.equals("empty")) {
                    var metadata=JSON.createObjectNode().put("sourceId",scenario.equals("unselected")?"fixture:other-record":PreparationFixture.RECORD).put("sourceRevision",1).put("providerRevision",PreparationFixture.PROVIDER).put("sourceRef",PreparationFixture.SOURCE).put("workspaceRef",PreparationFixture.request().workspaceRef()).put("projectRef",PreparationFixture.request().knowledgeProjectRef()).put("contentSha256",PreparationFixture.hash(PreparationFixture.SECRET.getBytes(StandardCharsets.UTF_8)));
                    chunks.addObject().put("document_id",PreparationFixture.DOCUMENT).put("chunk_index",scenario.equals("partial")?1:0).put("type","IndexSchema").put("text",PreparationFixture.SECRET).put("external_metadata",JSON.writeValueAsString(metadata));
                }
                byte[] bytes=JSON.writeValueAsBytes(response);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
        }
        public byte[] sourceEvidence(ContextRequest request) { return PreparationFixture.bytes(evidence); }
        public byte[] select(byte[] sourceEvidence) {
            try {
                Path input=privateRoot.resolve("source.json");Files.write(input,sourceEvidence);
                byte[] raw=invoke(scenario.equals("denied")?"default":"selection",input);
                if(scenario.equals("tampered")) { var changed=(ObjectNode)JSON.readTree(raw);((ObjectNode)changed.path("context").path("selected").get(0)).put("providerRevision","fixture:tampered");PreparationFixture.resign(changed);return PreparationFixture.bytes(changed); }
                return raw;
            } catch(PreparationContracts.Blocked e){throw e;}catch(Exception e){lastFailure=e.getClass().getSimpleName()+("BRIDGE_TIMEOUT".equals(e.getMessage())?"_BRIDGE_TIMEOUT":"");throw PreparationContracts.fail(ContextAcquisition.Reason.UNAVAILABLE);}
        }
        public void replay(byte[] sourceEvidence,byte[] original) {
            try {
                var replay=(ObjectNode)JSON.readTree(sourceEvidence);var receipt=JSON.readTree(original);replay.set("selectionReceipt",receipt);
                var selected=receipt.path("context").path("selected").get(0);
                var feedback=replay.putObject("consumerFeedback");feedback.put("recordKey",selected.path("recordKey").asText()).put("recordVersion",selected.path("recordVersion").asInt()).put("disposition","REJECTED").put("reason","Preparation replay only; no adoption claim").put("outcome","UNASSESSED");feedback.putArray("outcomeEvidenceRefs");
                Path input=privateRoot.resolve("replay.json");Files.write(input,PreparationFixture.bytes(replay));JsonNode checked=JSON.readTree(invoke("replay",input));
                check("FEEDBACK_BUILT".equals(checked.path("status").asText()),"HOST_REPLAY_FAILED");
                check(receipt.path("selectionReceiptSha256").equals(checked.path("selectionReceiptSha256")),"ORIGINAL_RECEIPT_DIGEST_CHANGED");replays++;
            }catch(Exception e){throw PreparationContracts.fail(ContextAcquisition.Reason.TAMPERED);}
        }
        private byte[] invoke(String mode,Path input) throws Exception {
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
            var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx256m","-XX:MaxMetaspaceSize=128m","-cp",System.getProperty("java.class.path"),Bootstrap.class.getName(),mode,input.toString(),endpoint).start();
            bridgeOperations.add("START_HOST_"+mode.toUpperCase(java.util.Locale.ROOT));
            var readers=Executors.newFixedThreadPool(2);Future<byte[]> stdout=readers.submit(()->bounded(process.getInputStream(),4*1024*1024));Future<byte[]> stderr=readers.submit(()->bounded(process.getErrorStream(),4096));
            try {
                if(!process.waitFor(15,TimeUnit.SECONDS)) {
                    bridgeOperations.add("TIMEOUT_HOST_"+mode.toUpperCase(java.util.Locale.ROOT));process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);
                    byte[] partial=stdout.get(2,TimeUnit.SECONDS),errors=stderr.get(2,TimeUnit.SECONDS);
                    String observed="NONE";try{String value=JSON.readTree(partial).path("status").asText();if(value.matches("[A-Z_]{1,80}"))observed=value;}catch(Exception ignored){}
                    lastChild="TIMEOUT_OUT_"+partial.length+"_ERR_"+errors.length+"_STATUS_"+observed+"_THREAD_LIMIT_"+new String(errors,StandardCharsets.UTF_8).contains("unable to create native thread");
                    throw new IllegalStateException("BRIDGE_TIMEOUT");
                }
                byte[] out=stdout.get(2,TimeUnit.SECONDS);byte[] err=stderr.get(2,TimeUnit.SECONDS);bridgeOperations.add("EXIT_HOST_"+mode.toUpperCase(java.util.Locale.ROOT)+"_"+process.exitValue());lastChild="EXIT_"+process.exitValue()+"_OUT_"+out.length+"_ERR_"+err.length+"_THREAD_LIMIT_"+new String(err,StandardCharsets.UTF_8).contains("unable to create native thread");
                if(mode.equals("default")&&process.exitValue()==2)throw PreparationContracts.fail(ContextAcquisition.Reason.DENIED);
                if(out.length==0)throw PreparationContracts.fail(ContextAcquisition.Reason.TAMPERED);
                return out;
            } finally { process.destroyForcibly();readers.shutdownNow();check(readers.awaitTermination(2,TimeUnit.SECONDS),"READER_CLEANUP"); }
        }
        public void close() throws Exception { server.stop(0);try(var walk=Files.walk(privateRoot)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);} }
    }
    private static byte[] bounded(java.io.InputStream stream,int limit) throws Exception { try(stream){byte[] bytes=stream.readNBytes(limit+1);if(bytes.length>limit)throw new IllegalStateException("BRIDGE_OUTPUT_LIMIT");return bytes;} }
    /** Test-only synthetic authorizer. Not included in any production source set or launch default. */
    public static final class Bootstrap {
        public static void main(String[] args) {
            String mode=args[0];List<String> argv=new ArrayList<>(List.of("dev204-knowledge-consume","--evidence-file",args[1],"--phase",mode.equals("replay")?"feedback":"selection"));
            if(!mode.equals("replay"))argv.addAll(List.of("--dataset-id",PreparationFixture.DATASET,"--base-url",args[2]));
            if(mode.equals("default")){ProductTestKnowledgePreparationBridge.main(argv.toArray(String[]::new));return;}
            try {
                if(mode.equals("replay"))ProductTestKnowledgePreparationBridge.replay(argv.toArray(String[]::new));
                else ProductTestKnowledgePreparationBridge.select(argv.toArray(String[]::new),config->{
                    if(!args[2].equals(config.get("--base-url"))||!PreparationFixture.DATASET.equals(config.get("--dataset-id")))throw new IllegalArgumentException("SYNTHETIC_POLICY_DENIED");
                    return new CogneeSearchClient(URI.create(args[2]),"SYNTHETIC-TOKEN-CANARY");
                });
            } catch(Exception rejected) { System.err.println("HOST_SELECTION_OR_REPLAY_REJECTED");System.exit(1); }
        }
    }
}

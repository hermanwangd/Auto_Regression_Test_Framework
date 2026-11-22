package com.specdriven.regression.provider.kafka;

import static org.assertj.core.api.Assertions.*;
import com.specdriven.regression.provider.runtime.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Injectable contract evidence only; default fence remains unbound, no sockets or producer construction. */
public class ReviewedKafkaRuntimeTest {
    @TempDir Path root;
    static final Clock CLOCK=Clock.fixed(Instant.parse("2000-01-01T00:01:00Z"),ZoneOffset.UTC);
    final AtomicInteger calls=new AtomicInteger();
    KafkaProviderRuntime.KafkaClientTransport transport(ReviewedKafkaProducer.Outcome outcome){return new KafkaProviderRuntime.KafkaClientTransport(){
        public KafkaProviderRuntime.KafkaPublishResult publish(KafkaProviderRuntime.KafkaConnection c,String k,Object p,Duration t){throw new AssertionError("ordinary client must not be reached");}
        public KafkaProviderRuntime.KafkaObserveResult observe(KafkaProviderRuntime.KafkaConnection c,String k,Instant i,Duration t,Duration p){throw new AssertionError("consumer must not be reached");}
        public ReviewedKafkaProducer.Outcome reviewedPublish(KafkaProviderRuntime.KafkaConnection c,String k,String payload,Duration t){calls.incrementAndGet();assertThat(c.bootstrapServers()).isEqualTo("127.0.0.1:19092");assertThat(payload).isEqualTo("{\"id\":\"public\"}");assertThat(k).isEqualTo("run."+ReviewedLoopbackKafkaPolicyTest.OWNER);return outcome;}
    };}
    ProviderExecutionContext context(){return new ProviderExecutionContext("todo_api","kafka","local_native","native",root,root.resolve("BATCH-1/RUN-1"),Map.of(),Map.of(),ReviewedLoopbackKafkaPolicyTest.bindings());}
    ProviderOperationRequest request(){return new ProviderOperationRequest("kafka_publish",List.of(Map.of("bind_as","payload_ref","ref","fixtures/payload.json"),Map.of("bind_as","key","ref","run."+ReviewedLoopbackKafkaPolicyTest.OWNER),Map.of("bind_as","timeout","ref","PT0.2S")),Map.of("_operation_id","publish"));}
    @Test void defaultFenceRejectsBeforeAnyClientAndRetainsBoundedBlockedProof()throws Exception{String pin=ReviewedLoopbackKafkaPolicyTest.make(root);var rt=new KafkaProviderRuntime(transport(new ReviewedKafkaProducer.Outcome(true,true,true,"ACK",0,0)),()->pin,CLOCK);var out=rt.executeReviewedPublish(context(),request());assertThat(out.status()).isEqualTo("blocked");assertThat(calls).hasValue(0);var proof=ReviewedLoopbackKafkaPolicy.parse(Files.readAllBytes(context().runDir().resolve(ReviewedKafkaPublishProof.PATH)));assertThat(proof.path("status").asText()).isEqualTo("PREFLIGHT_BLOCKED");assertThat(proof.path("attempted").asBoolean()).isFalse();}
    @Test void explicitUnitFenceAndTransportCanRecordAckWithoutClaimingNativeAcceptance()throws Exception{String pin=ReviewedLoopbackKafkaPolicyTest.make(root);var rt=new KafkaProviderRuntime(transport(new ReviewedKafkaProducer.Outcome(true,true,true,"ACK",0,0)),()->pin,f->{},CLOCK);var out=rt.executeReviewedPublish(context(),request());assertThat(out.passed()).isTrue();assertThat(calls).hasValue(1);assertThat(out.outputs()).containsEntry("offset",0L).containsEntry("partition",0);var proof=ReviewedLoopbackKafkaPolicy.parse(Files.readAllBytes(context().runDir().resolve(ReviewedKafkaPublishProof.PATH)));assertThat(proof.path("run_id").asText()).isEqualTo("RUN-1");assertThat(proof.path("batch_id").asText()).isEqualTo("BATCH-1");assertThat(proof.path("client_version").asText()).isEqualTo("3.9.2");}
    @ParameterizedTest @ValueSource(strings={"pin","payload","key","duplicate","resolved","operation","step"})
    void invalidAdmissionPerformsZeroClientOperations(String v)throws Exception{String pin=ReviewedLoopbackKafkaPolicyTest.make(root);String supplied=v.equals("pin")?null:pin;var req=request();if(v.equals("payload"))Files.writeString(root.resolve("fixtures/payload.json"),"{}");if(v.equals("key"))req=new ProviderOperationRequest(req.operation(),List.of(Map.of("bind_as","payload_ref","ref","fixtures/payload.json"),Map.of("bind_as","key","ref","run."+"a".repeat(32))),req.outputs());if(v.equals("duplicate")){var ps=new ArrayList<>(req.parameters());ps.add(ps.get(0));req=new ProviderOperationRequest(req.operation(),ps,req.outputs());}if(v.equals("resolved"))req=new ProviderOperationRequest(req.operation(),List.of(Map.of("bind_as","payload_ref","ref","other.json")),req.outputs());if(v.equals("operation"))req=new ProviderOperationRequest("kafka_observe",req.parameters(),req.outputs());if(v.equals("step"))req=new ProviderOperationRequest(req.operation(),req.parameters(),Map.of());var rt=new KafkaProviderRuntime(transport(new ReviewedKafkaProducer.Outcome(true,true,true,"ACK",0,0)),()->supplied,f->{},CLOCK);var finalReq=req;assertThatThrownBy(()->rt.executeReviewedPublish(context(),finalReq)).isInstanceOf(IllegalArgumentException.class);assertThat(calls).hasValue(0);assertThat(context().runDir().resolve(ReviewedKafkaPublishProof.PATH)).doesNotExist();}
    @Test void failedClientOutcomeRetainsOnlyStaticBoundedDiagnostics()throws Exception{String pin=ReviewedLoopbackKafkaPolicyTest.make(root);var rt=new KafkaProviderRuntime(transport(new ReviewedKafkaProducer.Outcome(true,false,true,"CLIENT_FAILED",-1,-1)),()->pin,f->{},CLOCK);var out=rt.executeReviewedPublish(context(),request());assertThat(out.status()).isEqualTo("failed");assertThat(calls).hasValue(1);assertThat(out.failure().code()).isEqualTo("KAFKA_PUBLISH_FAILED");assertThat(out.outputs()).containsEntry("offset",-1L);}
    @ParameterizedTest @ValueSource(strings={"partition","offset","attempted","acknowledged","cleanup"})
    void contradictoryOrNonfreshAckNeverReturnsProviderPassAndRetainsObservedFacts(String variant)throws Exception {
        String pin=ReviewedLoopbackKafkaPolicyTest.make(root);
        boolean attempted=!variant.equals("attempted"),ack=!variant.equals("acknowledged"),clean=!variant.equals("cleanup");int partition=variant.equals("partition")?1:0;long offset=variant.equals("offset")?1:0;
        var outcome=new ReviewedKafkaProducer.Outcome(attempted,ack,clean,"ACK",partition,offset);
        var rt=new KafkaProviderRuntime(transport(outcome),()->pin,f->{},CLOCK);var out=rt.executeReviewedPublish(context(),request());
        assertThat(out.passed()).as("finite ACK qualification: "+variant).isFalse();assertThat(calls).hasValue(1);
        assertThat(out.outputs()).containsEntry("partition",partition).containsEntry("offset",offset);
        var proof=ReviewedLoopbackKafkaPolicy.parse(Files.readAllBytes(context().runDir().resolve(ReviewedKafkaPublishProof.PATH)));
        assertThat(proof.path("attempted").booleanValue()).isEqualTo(attempted);assertThat(proof.path("acknowledged").booleanValue()).isEqualTo(ack);assertThat(proof.path("cleanup_confirmed").booleanValue()).isEqualTo(clean);assertThat(proof.path("partition").intValue()).isEqualTo(partition);assertThat(proof.path("offset").longValue()).isEqualTo(offset);assertThat(proof.path("status").asText()).isEqualTo("ACK");
    }

    @Test void actualBoundedProducerDeadlineUsesDeclaredKafkaTimeoutCode()throws Exception {
        String pin=ReviewedLoopbackKafkaPolicyTest.make(root);var n=new java.util.concurrent.atomic.AtomicLong();var producer=new ReviewedKafkaProducerTest.ProducerSpy();
        var deadline=ReviewedKafkaProducer.send(p->{n.set(20_000_000);return producer;},new KafkaProviderRuntime.KafkaConnection("127.0.0.1:19092",ReviewedLoopbackKafkaPolicyTest.TOPIC,"sit."+ReviewedLoopbackKafkaPolicyTest.OWNER+".group"),"run."+ReviewedLoopbackKafkaPolicyTest.OWNER,"{}",Duration.ofMillis(20),n::get);
        assertThat(deadline.status()).isEqualTo("DEADLINE_EXCEEDED");assertThat(deadline.attempted()).isFalse();var rt=new KafkaProviderRuntime(transport(deadline),()->pin,f->{},CLOCK);var result=rt.executeReviewedPublish(context(),request());assertThat(result.failure().code()).isEqualTo("KAFKA_TIMEOUT");assertThat(result.passed()).isFalse();
    }
    /** Test bridge uses the actual reviewed runtime/default UNBOUND fence, never a Kafka producer. */
    public static com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter unboundContractBridge(String pin,AtomicInteger transportCalls){
        KafkaProviderRuntime.KafkaClientTransport denied=new KafkaProviderRuntime.KafkaClientTransport(){
            public KafkaProviderRuntime.KafkaPublishResult publish(KafkaProviderRuntime.KafkaConnection c,String k,Object p,Duration t){throw new AssertionError("ordinary transport forbidden");}
            public KafkaProviderRuntime.KafkaObserveResult observe(KafkaProviderRuntime.KafkaConnection c,String k,Instant from,Duration t,Duration poll){throw new AssertionError("consumer forbidden");}
            public ReviewedKafkaProducer.Outcome reviewedPublish(KafkaProviderRuntime.KafkaConnection c,String k,String p,Duration t){transportCalls.incrementAndGet();throw new AssertionError("default fence must reject before transport");}
        };
        var runtime=new KafkaProviderRuntime(denied,()->pin,Clock.systemUTC());
        return new com.specdriven.regression.sit.SitPolicyAwareProvider(){
            public String providerType(){return "kafka";}public boolean supports(String c,String o){return "kafka.v0.3".equals(c)&&"kafka_publish".equals(o);}public boolean simulated(){return false;}
            public com.specdriven.regression.sit.SitOperationEffect sitEffect(String c,String o){return supports(c,o)?com.specdriven.regression.sit.SitOperationEffect.OWNED_WRITE:com.specdriven.regression.sit.SitOperationEffect.UNKNOWN;}
            public com.specdriven.regression.contract.v03.V03StepResult executeWithSitPolicy(com.specdriven.regression.contract.v03.V03ExecutionStep s,com.specdriven.regression.contract.v03.V03ExecutionContext c,com.specdriven.regression.sit.SitExecutionScope scope){throw new AssertionError("shared scope forbidden");}
            public com.specdriven.regression.contract.v03.V03StepResult execute(com.specdriven.regression.contract.v03.V03ExecutionStep step,com.specdriven.regression.contract.v03.V03ExecutionContext context){
                var provider=new ProviderExecutionContext(step.target(),"kafka",step.profile(),step.runtimeMode(),context.suiteRoot(),context.runDir(),Map.of(),Map.of(),context.targets().get(step.target()).bindings());var parameters=new ArrayList<Map<String,Object>>();
                for(var e:step.inputs().entrySet())parameters.add(Map.of("bind_as",e.getKey(),"ref",e.getKey().equals("payload_ref")?"fixtures/payload.json":e.getValue()));
                var out=runtime.executeReviewedPublish(provider,new ProviderOperationRequest(step.operation(),parameters,Map.of("_operation_id",step.id())));
                return new com.specdriven.regression.contract.v03.V03StepResult(step.id(),out.status(),out.outputs(),out.evidence().stream().map(ProviderEvidence::ref).toList(),out.failure()==null?"":out.failure().code(),out.failure()==null?"":out.failure().reason());
            }
        };
    }

}

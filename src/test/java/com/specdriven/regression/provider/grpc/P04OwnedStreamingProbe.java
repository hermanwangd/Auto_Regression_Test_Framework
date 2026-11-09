package com.specdriven.regression.provider.grpc;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specdriven.regression.contract.v03.V03RuntimeExecutionService;
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.producttest.ProductTestHandoffService;
import com.specdriven.regression.summary.SuiteExecutionContext;
import com.google.protobuf.DynamicMessage;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Finite executable contract probe; internal assertions are not JUnit testcase counts. */
public final class P04OwnedStreamingProbe {
    static final ObjectMapper JSON=new ObjectMapper();
    static int checks;
    static final String EXPECTED="gRPC unary_call failed: Unsupported streaming descriptor for gRPC unary_call; select a unary method.";
    static final Map<String,Object> INPUT=Map.of("grpc.service","service.Test","grpc.method","GetThingVersions","grpc.descriptor_ref","artifact://fixtures/service.desc.b64","grpc.message","artifact://fixtures/request.json");
    public static void main(String[] args)throws Exception {
        checks=0;Path temporary=Path.of(args[0]).toAbsolutePath();Files.createDirectory(temporary);
        Path source=Path.of("samples/41-product-testing/grpc_descriptor_admission/fixtures").toAbsolutePath();
        Path contract=args.length>1?Path.of(args[1]).toAbsolutePath():Path.of("docs/02-architecture/contracts/provider-contracts/grpc_client_v0_3.yaml").toAbsolutePath();
        // A distinct owner proves the actual streaming fixture. Never reset or borrow its call counter.
        try(var health=new Owner(temporary.resolve("health-owner"),source)) {
            var fixture=ReviewedLoopbackGrpcPolicy.fixture(health.root,health.bindings(),true);
            var method=fixture.method();
            var wire=MethodDescriptor.<DynamicMessage,DynamicMessage>newBuilder().setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                .setFullMethodName("service.Test/GetThingVersions")
                .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getInputType())))
                .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getOutputType()))).build();
            var channel=ManagedChannelBuilder.forTarget(health.endpoint.substring("grpc://".length())).usePlaintext().maxInboundMessageSize(65536).build();
            try {
                var request=DynamicMessage.newBuilder(method.getInputType()).setField(method.getInputType().findFieldByName("name"),"my milkshakes").build();
                var responses=ClientCalls.blockingServerStreamingCall(channel,wire,CallOptions.DEFAULT.withDeadlineAfter(5,TimeUnit.SECONDS),request);
                int count=0;while(responses.hasNext()) {var item=responses.next();count++;check(item.getField(method.getOutputType().findFieldByName("name")).equals("my milkshakes") && item.getField(method.getOutputType().findFieldByName("version")).equals(count),"streaming response differs");}
                check(count==2 && health.calls()==1,"actual streaming fixture must receive one call and emit two responses");
            } finally {channel.shutdownNow();check(channel.awaitTermination(2,TimeUnit.SECONDS),"health client cleanup");}
        }
        var spy=new ForbiddenChannelProvider();ManagedChannelRegistry.getDefaultRegistry().register(spy);
        try {
            try {ManagedChannelBuilder.forTarget("127.0.0.1:1");throw new AssertionError("spy not selected");}
            catch(AssertionError expected){check(spy.attempts==1,"channel construction sensitivity control");}spy.attempts=0;
            Path suite;
            try(var owner=new Owner(temporary.resolve("rejection-owner"),source)) {
                var fixture=ReviewedLoopbackGrpcPolicy.fixture(owner.root,owner.bindings(),true);checks++;
                helperControls(fixture.method());policyControls(owner);
                suite=fixture(temporary.resolve("suite"),owner,contract);
                String handoffPin=Files.readString(suite.resolve("handoff/reviewed.json.sha256")).trim();
                var requirement=new ProductTestHandoffService.InvocationRequirement(handoffPin,hash(suite.resolve("fixtures/lease.json")));
                var run=new V03RuntimeExecutionService().runReviewed(suite.resolve("suite_manifest.yaml"),"p04_owned_negative",SuiteExecutionContext.standalone("p04_owned_negative",temporary.resolve("runs"),"P04"),requirement);
                Files.writeString(temporary.resolve("result-path.txt"),run.resultJson().toString());
                check("failed".equals(run.status())&&run.providerRuntimeExecuted(),"original provider must actually execute: "+run.findings());
                var result=JSON.readTree(run.resultJson().toFile());
                var proof=JSON.readTree(run.evidenceDir().resolve(ReviewedGrpcStreamingRejectionProof.PATH).toFile());
                var handoff=JSON.readTree(run.evidenceDir().resolve("product-test/reviewed-handoff.json").toFile());
                check(EXPECTED.equals(result.path("failure").path("reason").asText()),"wrong canonical diagnostic: "+result.path("failure"));
                check(correctOracle(result,proof),"correct frozen original oracle must accept");
                check(!wrongOracle(result,proof),"wrong PASS/OK oracle must reject the same result");
                var validation=new EvidenceHardeningService().validateResult(run.resultJson());
                check(validation.valid(),"canonical validation failed: "+validation.findings()+validation.failedEvidenceSummary());
                proofControls(proof,result,handoff);
                check(owner.calls()==0 && Files.size(owner.root.resolve("owner-receipts.jsonl"))==0,"fresh rejection owner received an RPC");
                check(spy.attempts==0,"rejection or controls constructed a client channel");
                // The one additional invocation is blocked pre-dispatch, not another provider execution.
                var blocked=new V03RuntimeExecutionService().runReviewed(suite.resolve("suite_manifest.yaml"),"p04_owned_negative",SuiteExecutionContext.standalone("p04_owned_negative",temporary.resolve("wrong-pin"),"P04"),new ProductTestHandoffService.InvocationRequirement(handoffPin,"0".repeat(64)));
                check("blocked".equals(blocked.status())&&!blocked.providerRuntimeExecuted(),"wrong independent pin reached provider");
                check(owner.calls()==0 && spy.attempts==0,"pin guard caused a network action");
                write(temporary.resolve("oracle-results.json"),Map.of("run_id",result.path("run_id").asText(),"result",run.resultJson().toString(),"correct_oracle","ACCEPT","wrong_PASS_OK_oracle","REJECT","provider_contract","grpc_client.v0.3","actual_provider_executions",1,"owner_rpc_count",0,"channel_builder_requests",spy.attempts));
            }
            rejects(()->ReviewedLoopbackGrpcPolicy.fixture(suite,Map.of("target",JSON.readTree(suite.resolve("fixtures/lease.json").toFile()).path("endpoint").asText(),"owner_run_id",JSON.readTree(suite.resolve("fixtures/lease.json").toFile()).path("owner_run_id").asText()),true));
            check(spy.attempts==0,"stopped-owner guard constructed a channel");
        } finally {ManagedChannelRegistry.getDefaultRegistry().deregister(spy);}
        write(temporary.resolve("probe-summary.json"),Map.of("checks",checks,"status","PASS","runtime",System.getProperty("java.version"),"formal_coverage_credit",false));
        System.out.println("P04 owned streaming probe PASS; internal checks="+checks+"; evidence="+temporary);
    }
    static void helperControls(com.google.protobuf.Descriptors.MethodDescriptor method)throws Exception {
        try {GrpcClientProviderRuntime.requireRejectionOnly(method,GrpcDescriptorSupport::requireUnary);throw new AssertionError("guard did not reject");}
        catch(GrpcDescriptorSupport.StreamingDescriptorException real){check(EXPECTED.equals(GrpcClientProviderRuntime.reviewedFailureReason(true,"UNKNOWN",real.getMessage())),"typed guard must retain exact diagnostic");}
        for(boolean unrelated:List.of(false,true)) {
            try {GrpcClientProviderRuntime.requireRejectionOnly(method,m->{if(unrelated)throw new IllegalArgumentException("unrelated guard error");});throw new AssertionError("guard failure fell through");}
            catch(RuntimeException failure){check(!(failure instanceof GrpcDescriptorSupport.StreamingDescriptorException),"fake typed cause");check(!EXPECTED.equals(GrpcClientProviderRuntime.reviewedFailureReason(false,"UNKNOWN",failure.getMessage())),"fallback must not pass exact oracle");}
        }
        byte[] raw=Base64.getDecoder().decode(Files.readAllBytes(Path.of("samples/41-product-testing/grpc_descriptor_admission/fixtures/service.desc.b64")));
        GrpcDescriptorSupport.requireUnary(GrpcDescriptorSupport.method(raw,"service.Test","DoThing"));checks++;
        rejects(()->GrpcDescriptorSupport.method(raw,"service.Test","Missing"));
        rejects(()->GrpcDescriptorSupport.method(new byte[]{0},"service.Test","GetThingVersions"));
        for(String methodName:List.of("Missing")){var input=new LinkedHashMap<>(INPUT);input.put("grpc.method",methodName);rejects(()->ReviewedLoopbackGrpcPolicy.validateInputs(input));}
    }
    static void policyControls(Owner owner)throws Exception {
        ReviewedLoopbackGrpcPolicy.validateInputs(INPUT);checks++;
        for(String key:List.of("tls","authority","auth","endpoint","lease")){var bindings=new LinkedHashMap<>(owner.bindings());bindings.put(key,"forbidden");rejects(()->ReviewedLoopbackGrpcPolicy.fixture(owner.root,bindings,true));}
        for(String target:List.of("grpc://localhost:12345","grpc://192.0.2.1:12345","grpc://127.0.0.1:65536","grpc://127.0.0.1:1")){var bindings=new LinkedHashMap<>(owner.bindings());bindings.put("target",target);rejects(()->ReviewedLoopbackGrpcPolicy.fixture(owner.root,bindings,true));}
        var metadata=new LinkedHashMap<>(INPUT);metadata.put("grpc.metadata.authorization","forbidden");rejects(()->ReviewedLoopbackGrpcPolicy.validateInputs(metadata));
        Path lease=owner.root.resolve("fixtures/lease.json");byte[] original=Files.readAllBytes(lease);
        for(String variant:List.of("expired","future","ttl","pid","start","nonce","owner","descriptor_digest","request_digest","profile")) {
            ObjectNode node=(ObjectNode)JSON.readTree(original);
            switch(variant) {
                case "expired"->{node.put("issued_at","2020-01-01T00:00:00Z");node.put("expires_at","2020-01-01T00:01:00Z");}
                case "future"->{node.put("issued_at",Instant.now().plusSeconds(1000).toString());node.put("expires_at",Instant.now().plusSeconds(1100).toString());}
                case "ttl"->node.put("expires_at",Instant.now().plusSeconds(1000).toString());
                case "pid"->node.put("pid",Long.MAX_VALUE);
                case "start"->node.put("process_start_instant","2020-01-01T00:00:00Z");
                case "nonce"->{node.put("pid",ProcessHandle.current().pid());node.put("process_start_instant",ProcessHandle.current().info().startInstant().orElseThrow().toString());}
                case "owner"->node.put("owner_run_id","0".repeat(32));
                case "descriptor_digest"->node.put("descriptor_sha256","0".repeat(64));
                case "request_digest"->node.put("request_sha256","0".repeat(64));
                case "profile"->node.put("profile","unknown");
            }
            try {Files.write(lease,JSON.writeValueAsBytes(node));rejects(()->ReviewedLoopbackGrpcPolicy.fixture(owner.root,owner.bindings(),true));}finally{Files.write(lease,original);}
        }
        for(String file:List.of("service.proto","service.desc.b64","request.json")) {
            Path target=owner.root.resolve("fixtures/"+file);byte[] bytes=Files.readAllBytes(target);
            try {Files.writeString(target,"changed");rejects(()->ReviewedLoopbackGrpcPolicy.fixture(owner.root,owner.bindings(),true));}finally{Files.write(target,bytes);}
        }
    }
    static void proofControls(JsonNode proof,JsonNode result,JsonNode handoff)throws Exception {
        ReviewedGrpcStreamingRejectionProof.validate(proof,result,handoff);checks++;
        for(String field:List.of("source_revision","source_sha256","descriptor_sha256","request_sha256","fixture_lease_sha256","owner_run_id","endpoint","method","run_id","test_case_id","step_id","provider_id","provider_type","operation","diagnostic","lease_checked_at")) {
            ObjectNode bad=proof.deepCopy();bad.put(field,"wrong");rejects(()->ReviewedGrpcStreamingRejectionProof.validate(bad,result,handoff));
        }
        for(String flag:List.of("rpc_attempted","channel_created","client_streaming","server_streaming","guard_rejection_observed","cleanup_confirmed")) {
            ObjectNode bad=proof.deepCopy();bad.put(flag,!proof.path(flag).booleanValue());rejects(()->ReviewedGrpcStreamingRejectionProof.validate(bad,result,handoff));
            bad.remove(flag);rejects(()->ReviewedGrpcStreamingRejectionProof.validate(bad,result,handoff));
        }
        ObjectNode passed=result.deepCopy();passed.put("status","passed");rejects(()->ReviewedGrpcStreamingRejectionProof.validate(proof,passed,handoff));
        for(String field:List.of("profile","pid","process_start_instant","issued_at","expires_at","owner_run_id","endpoint","descriptor_sha256")) {
            ObjectNode bad=handoff.deepCopy();((ObjectNode)bad.path("fixture_lease")).put(field,"wrong");rejects(()->ReviewedGrpcStreamingRejectionProof.validate(proof,result,bad));
        }
        ObjectNode extra=handoff.deepCopy();extra.put("fixture_lease_alternate","forbidden");rejects(()->ReviewedGrpcStreamingRejectionProof.validate(proof,result,extra));
    }
    static boolean correctOracle(JsonNode result,JsonNode proof){return "failed".equals(result.path("status").asText())&&EXPECTED.equals(result.path("failure").path("reason").asText())&&"UNKNOWN".equals(proof.path("status").asText())&&"GetThingVersions".equals(proof.path("method").asText())&&proof.path("server_streaming").asBoolean()&&!proof.path("client_streaming").asBoolean();}
    static boolean wrongOracle(JsonNode result,JsonNode proof){return "passed".equals(result.path("status").asText())&&"OK".equals(proof.path("status").asText());}
    static final class ForbiddenChannelProvider extends ManagedChannelProvider {
        int attempts;
        protected boolean isAvailable(){return true;}protected int priority(){return Integer.MAX_VALUE;}
        protected Collection<Class<? extends java.net.SocketAddress>> getSupportedSocketAddressTypes(){return Set.of(java.net.InetSocketAddress.class);}
        protected ManagedChannelBuilder<?> builderForAddress(String h,int p){attempts++;throw new AssertionError("channel construction forbidden");}
        protected ManagedChannelBuilder<?> builderForTarget(String t){attempts++;throw new AssertionError("channel construction forbidden");}
    }
    static final class Owner implements AutoCloseable {
        final Path root;final String id=UUID.randomUUID().toString().replace("-","");final Process process;final String endpoint;
        Owner(Path root,Path source)throws Exception {
            this.root=root;
            Path log=root.resolveSibling(root.getFileName()+".log");
            Path argfile=root.resolveSibling(root.getFileName()+".args");
            Files.writeString(argfile,"-cp \""+System.getProperty("java.class.path")+"\"\n"+P04StreamingOwner.class.getName()+"\n");
            process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx128m","-XX:MaxMetaspaceSize=128m","-Dswarm.grpc.owner_nonce="+id,"@"+argfile,root.toString(),source.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!Files.exists(root.resolve("owner-ready.json"))&&process.isAlive()&&System.nanoTime()<end)Thread.sleep(10);
            if(!Files.exists(root.resolve("owner-ready.json"))) {process.destroyForcibly();if(!process.waitFor(2,TimeUnit.SECONDS))throw new IllegalStateException("owner reaping uncertain");throw new IllegalStateException("owner readiness failed: "+Files.readString(log));}
            endpoint=JSON.readTree(root.resolve("owner-ready.json").toFile()).path("endpoint").asText();
            if(!Arrays.asList(process.info().arguments().orElse(new String[0])).contains("-Dswarm.grpc.owner_nonce="+id)) {
                process.destroyForcibly();boolean reaped=process.waitFor(2,TimeUnit.SECONDS);
                write(root.resolve("failed-launch-cleanup.json"),Map.of("pid",process.pid(),"reaped",reaped,"reason","owner nonce not observable through ProcessHandle"));throw new AssertionError("real owner nonce arg absent; reaped="+reaped);
            }checks++;
        }
        Map<String,Object> bindings(){return Map.of("target",endpoint,"owner_run_id",id);}
        int calls()throws Exception{return JSON.readTree(root.resolve("owner-calls.json").toFile()).path("calls").asInt();}
        public void close()throws Exception {
            if(process.isAlive()){process.getOutputStream().write("stop\n".getBytes());process.getOutputStream().flush();}
            if(!process.waitFor(3,TimeUnit.SECONDS)){process.destroyForcibly();check(process.waitFor(2,TimeUnit.SECONDS),"owner process did not terminate");throw new AssertionError("owner required forced termination");}
            check(process.exitValue()==0,"owner exit="+process.exitValue());
            check(JSON.readTree(root.resolve("owner-cleanup.json").toFile()).path("server_terminated").asBoolean(),"server cleanup missing");
            try(var socket=new java.net.Socket()) {try{socket.connect(new java.net.InetSocketAddress("127.0.0.1",java.net.URI.create(endpoint).getPort()),100);throw new AssertionError("listener remains open");}catch(java.io.IOException expected){checks++;}}
            write(root.resolve("launcher-cleanup.json"),Map.of("pid",process.pid(),"reaped",true,"port_closed",true));
        }
    }
    static Path fixture(Path suite,Owner owner,Path contract)throws Exception {
        for(String dir:List.of("fixtures","env_profiles","test_cases","contracts","handoff"))Files.createDirectories(suite.resolve(dir));
        for(String file:ReviewedGrpcDescriptorPolicy.FILES.keySet())Files.copy(Path.of("samples/41-product-testing/grpc_descriptor_admission").resolve(file),suite.resolve(file));
        Files.copy(contract,suite.resolve("contracts/grpc.yaml"));
        Files.copy(owner.root.resolve("fixtures/lease.json"),suite.resolve("fixtures/lease.json"));
        write(suite.resolve("suite_manifest.yaml"),Map.of("manifest_version","v0.3","suite_id","P04-OWNED-STREAMING-REJECTION","default_profile","p04_owned_negative",
                "artifact_roots",Map.of("fixtures","fixtures/"),"targets",Map.of("betterproto",Map.of("provider_contract","grpc_client.v0.3")),
                "env_profiles",Map.of("p04_owned_negative",Map.of("ref","env_profiles/p04.yaml")),"tests",List.of(Map.of("ref","test_cases/streaming.yaml"))));
        write(suite.resolve("env_profiles/p04.yaml"),Map.of("profile_id","p04_owned_negative","execution_mode","local","isolation","per_run",
                "evidence_classification","framework_verification_only","downstream_release_evidence",false,
                "targets",Map.of("betterproto",Map.of("runtime_mode","native","bindings",owner.bindings())),
                "product_test",Map.of("contract_version","product-test.v1","test_level","integration","test_type","regression","environment_stage","local","knowledge_mode","not_used","provider_modes",Map.of("betterproto","real"),"dependency_modes",Map.of(),"reviewed_handoff_ref","handoff/reviewed.json")));
        write(suite.resolve("test_cases/streaming.yaml"),Map.of("dsl_version","v0.3","test_case_id","TC-P04-SG02-SERVERSTREAM-REJECT-001","title","Owned reviewed original gRPC streaming rejection",
                "execute",List.of(Map.of("id","reject_streaming","target","betterproto","op","unary_call","with",Map.of("grpc.service","service.Test","grpc.method","GetThingVersions","grpc.descriptor_ref","artifact://fixtures/service.desc.b64","grpc.message","artifact://fixtures/request.json"))),
                "verify",List.of(Map.of("id","status_unknown","type","assertion","assert",Map.of("actual","step://reject_streaming/response.status","operator","equals","expected","UNKNOWN")))));
        var review=new LinkedHashMap<String,Object>();review.put("review_ref","test://p04/owned-negative");review.put("reviewed_by","automated-test-fixture-not-production-approval");
        review.put("reviewed_at",Instant.now().minusSeconds(5).toString());review.put("valid_until",Instant.now().plusSeconds(600).toString());review.put("framework_baseline","0.3.2");
        review.put("workspace_ref","p04-test-workspace");review.put("project_ref","p04-owned-negative");
        review.put("repository_revisions",Map.of("python-betterproto",ReviewedGrpcDescriptorPolicy.SOURCE_REVISION));
        review.put("swarm_source",Map.of("base_revision","b13c503cc9fadd930e11fb8adc6496308697a966","files",Map.of("fixtures/service.proto",ReviewedGrpcDescriptorPolicy.SOURCE_SHA)));
        var files=new ArrayList<Map<String,String>>();files.add(Map.of("path","suite_manifest.yaml","role","manifest"));files.add(Map.of("path","env_profiles/p04.yaml","role","profile"));files.add(Map.of("path","test_cases/streaming.yaml","role","test"));
        for(String file:ReviewedGrpcDescriptorPolicy.FILES.keySet())files.add(Map.of("path",file,"role","fixture"));files.add(Map.of("path","fixtures/lease.json","role","fixture"));review.put("reviewed_files",files);
        review.put("reviewed_contracts",List.of(Map.of("path","contracts/grpc.yaml","provider_contract","grpc_client.v0.3")));
        review.put("requirement_mapping",List.of(Map.of("requirement_ref","P04-SG02-CANDIDATE-ORIGINAL-GRPC-ROW-AUTHORITY-PENDING","test_case_ids",List.of("TC-P04-SG02-SERVERSTREAM-REJECT-001"),"oracle_refs",List.of("test_cases/streaming.yaml#status_unknown"),"review_ref","test://p04/owned-negative")));
        write(suite.resolve("review-input.json"),review);
        new ProductTestHandoffService().prepare(suite.resolve("suite_manifest.yaml"),"p04_owned_negative",suite.resolve("review-input.json"),null,suite.resolve("handoff/reviewed.json"));
        return suite;
    }
    static void write(Path path,Object value)throws Exception{Files.write(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));}
    static String hash(Path path)throws Exception{return ReviewedLoopbackGrpcPolicy.digest(Files.readAllBytes(path));}
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;}
    @FunctionalInterface interface Checked {void run()throws Exception;}
    static void rejects(Checked action)throws Exception{try{action.run();}catch(IllegalArgumentException|java.io.IOException expected){check(!EXPECTED.equals(expected.getMessage()),"invalid control mimicked streaming result");return;}throw new AssertionError("invalid input was accepted");}
}

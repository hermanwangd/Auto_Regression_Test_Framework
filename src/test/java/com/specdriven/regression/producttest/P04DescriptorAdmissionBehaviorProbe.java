package com.specdriven.regression.producttest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specdriven.regression.contract.v03.V03RuntimeExecutionService;
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.provider.grpc.*;
import com.specdriven.regression.provider.runtime.*;
import com.specdriven.regression.summary.SuiteExecutionContext;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Executable integration/negative suite without external services or testing-library dependencies. */
public final class P04DescriptorAdmissionBehaviorProbe {
    static final ObjectMapper JSON=new ObjectMapper();
    static int checks;
    public static void main(String[] args)throws Exception {
        checks=0;
        var channelSpy=new ForbiddenChannelProvider();
        io.grpc.ManagedChannelRegistry.getDefaultRegistry().register(channelSpy);
        try {
        try {io.grpc.ManagedChannelBuilder.forTarget("127.0.0.1:1");throw new AssertionError("channel spy not selected");}
        catch(AssertionError expected){check(channelSpy.attempts==1,"positive control must detect a channel-builder request before construction");}
        channelSpy.attempts=0;
        Path temporary=Path.of(args[0]).toAbsolutePath();Files.createDirectories(temporary);
        Path suite=fixture(temporary.resolve("suite"));
        String pin=Files.readString(suite.resolve("handoff/reviewed.json.sha256")).trim();
        var run=new V03RuntimeExecutionService().runReviewed(suite.resolve("suite_manifest.yaml"),"p04_descriptor_only",
                SuiteExecutionContext.standalone("p04_descriptor_only",temporary.resolve("runs"),"P04"),
                new ProductTestHandoffService.InvocationRequirement(pin));
        check("failed".equals(run.status())&&run.providerRuntimeExecuted(),"actual descriptor provider must run and reject: "+run.findings());
        var validation=new EvidenceHardeningService().validateResult(run.resultJson());
        check(validation.valid(),"canonical failure evidence must validate: "+validation.findings()+validation.failedEvidenceSummary());
        var result=JSON.readTree(run.resultJson().toFile());var proof=JSON.readTree(run.evidenceDir().resolve(ReviewedGrpcDescriptorProof.PATH).toFile());
        var provenance=JSON.readTree(run.evidenceDir().resolve("product-test/reviewed-handoff.json").toFile());
        ReviewedGrpcDescriptorProof.validate(proof,result,provenance);checks++;
        check(!"passed".equals(result.path("status").asText())&&!"OK".equals(proof.path("status").asText()),"wrong PASS/OK oracle must reject same result");
        for(String flag:List.of("rpc_attempted","channel_created","client_streaming")) {
            ObjectNode mutated=proof.deepCopy();mutated.put(flag,true);rejects(()->ReviewedGrpcDescriptorProof.validate(mutated,result,provenance));
        }
        for(String field:List.of("diagnostic","source_sha256","descriptor_sha256","request_sha256","run_id","method","step_id")) {
            ObjectNode mutated=proof.deepCopy();mutated.put(field,"wrong");rejects(()->ReviewedGrpcDescriptorProof.validate(mutated,result,provenance));
        }
        ObjectNode passed=result.deepCopy();passed.put("status","passed");rejects(()->ReviewedGrpcDescriptorProof.validate(proof,passed,provenance));
        ObjectNode missing=proof.deepCopy();missing.remove("channel_created");rejects(()->ReviewedGrpcDescriptorProof.validate(missing,result,provenance));
        var bindings=new LinkedHashMap<String,Object>(Map.of("admission_mode","descriptor_only","source_revision",ReviewedGrpcDescriptorPolicy.SOURCE_REVISION));
        for(String field:List.of("target","endpoint","owner_run_id","lease","tls","authority")) {
            var mutated=new LinkedHashMap<String,Object>(bindings);mutated.put(field,"forbidden");rejects(()->ReviewedGrpcDescriptorPolicy.validateBindings(mutated));
        }
        for(String file:ReviewedGrpcDescriptorPolicy.FILES.keySet()) {
            byte[] original=Files.readAllBytes(suite.resolve(file));Files.writeString(suite.resolve(file),"changed");
            rejects(()->ReviewedGrpcDescriptorPolicy.fixture(suite,bindings));Files.write(suite.resolve(file),original);
        }
        Files.writeString(suite.resolve("fixtures/lease.json"),"{}");rejects(()->ReviewedGrpcDescriptorPolicy.fixture(suite,bindings));Files.delete(suite.resolve("fixtures/lease.json"));
        var leaseResult=new V03RuntimeExecutionService().runReviewed(suite.resolve("suite_manifest.yaml"),"p04_descriptor_only",
                SuiteExecutionContext.standalone("p04_descriptor_only",temporary.resolve("lease-contamination"),"P04"),
                new ProductTestHandoffService.InvocationRequirement(pin,"a".repeat(64)));
        check("blocked".equals(leaseResult.status())&&!leaseResult.providerRuntimeExecuted(),"independent lease contamination must stop before provider");
        byte[] descriptor=Base64.getDecoder().decode(Files.readAllBytes(suite.resolve("fixtures/service.desc.b64")));
        GrpcDescriptorSupport.requireUnary(GrpcDescriptorSupport.method(descriptor,"service.Test","DoThing"));checks++;
        for(String method:List.of("DoManyThings","GetThingVersions","GetDifferentThings")) {
            var parsed=GrpcDescriptorSupport.method(descriptor,"service.Test",method);rejects(()->GrpcDescriptorSupport.requireUnary(parsed));
        }
        Path generic=temporary.resolve("generic");Files.createDirectories(generic);
        var params=new ArrayList<Map<String,Object>>();
        Map.of("grpc.service","service.Test","grpc.method","GetThingVersions","grpc.descriptor_ref","fixtures/service.desc.b64","grpc.message","fixtures/request.json").forEach((k,v)->params.add(Map.of("bind_as",k,"ref",v)));
        var observed=new GrpcClientProviderRuntime().execute(new ProviderExecutionContext("p04","grpc_client","probe","native",suite,generic,Map.of(),Map.of(),Map.of("target","grpc://127.0.0.1:1")),new ProviderOperationRequest("unary_call",params,Map.of()));
        check("failed".equals(observed.status())&&"OPERATION_FAILED".equals(observed.failure().code())&&ReviewedGrpcDescriptorProof.DIAGNOSTIC.equals(observed.failure().reason()),"generic runtime must share exact real rejection");
        for(Class<?> type:List.of(GrpcDescriptorSupport.class,GrpcDescriptorAdmissionRuntime.class,ReviewedGrpcDescriptorPolicy.class)) {
            String resource="/"+type.getName().replace('.','/')+".class";
            byte[] bytes;try(var input=type.getResourceAsStream(resource)){bytes=input.readAllBytes();}
            String pool=new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1);
            check(!pool.contains("io/grpc/")&&!pool.contains("java/net/")&&!pool.contains("GrpcClientProviderRuntime"),"offline call path must have no network-capable class dependency: "+type.getName());
        }
        check(channelSpy.attempts==0,"no descriptor or generic streaming check may construct a channel");
        try { GrpcDescriptorSupport.method(new byte[]{0},"service.Test","GetThingVersions");throw new AssertionError("malformed descriptor accepted"); }
        catch(java.io.IOException expected){checks++;}
        try { GrpcDescriptorSupport.method(descriptor,"service.Test","Missing");throw new AssertionError("unknown method accepted"); }
        catch(java.io.IOException expected){check(!expected.getMessage().contains("Unsupported streaming"),"unknown method must not masquerade as streaming");}
        System.out.println("P04 behavior checks passed: "+checks+"; run_id="+result.path("run_id").asText()+"; result="+run.resultJson());
        Files.writeString(temporary.resolve("result-path.txt"),run.resultJson().toString());
        } finally {io.grpc.ManagedChannelRegistry.getDefaultRegistry().deregister(channelSpy);}
    }
    static final class ForbiddenChannelProvider extends io.grpc.ManagedChannelProvider {
        int attempts;
        @Override protected boolean isAvailable(){return true;}
        @Override protected int priority(){return Integer.MAX_VALUE;}
        @Override protected java.util.Collection<Class<? extends java.net.SocketAddress>> getSupportedSocketAddressTypes(){return Set.of(java.net.InetSocketAddress.class);}
        @Override protected io.grpc.ManagedChannelBuilder<?> builderForAddress(String host,int port){attempts++;throw new AssertionError("channel construction forbidden");}
        @Override protected io.grpc.ManagedChannelBuilder<?> builderForTarget(String target){attempts++;throw new AssertionError("channel construction forbidden");}
    }
    static Path fixture(Path suite)throws Exception {
        for(String dir:List.of("fixtures","env_profiles","test_cases","contracts","handoff"))Files.createDirectories(suite.resolve(dir));
        for(String file:ReviewedGrpcDescriptorPolicy.FILES.keySet())Files.copy(Path.of("samples/41-product-testing/grpc_descriptor_admission").resolve(file),suite.resolve(file));
        Files.copy(Path.of("docs/02-architecture/contracts/provider-contracts/grpc_descriptor_admission_v0_3.yaml"),suite.resolve("contracts/grpc.yaml"));
        write(suite.resolve("suite_manifest.yaml"),Map.of("manifest_version","v0.3","suite_id","P04-DESCRIPTOR-ADMISSION","default_profile","p04_descriptor_only",
                "artifact_roots",Map.of("fixtures","fixtures/"),"targets",Map.of("betterproto",Map.of("provider_contract",ReviewedGrpcDescriptorPolicy.CONTRACT)),
                "env_profiles",Map.of("p04_descriptor_only",Map.of("ref","env_profiles/p04.yaml")),"tests",List.of(Map.of("ref","test_cases/streaming.yaml"))));
        write(suite.resolve("env_profiles/p04.yaml"),Map.of("profile_id","p04_descriptor_only","execution_mode","local","isolation","per_run",
                "evidence_classification","framework_verification_only","downstream_release_evidence",false,
                "targets",Map.of("betterproto",Map.of("runtime_mode","native","bindings",Map.of("admission_mode","descriptor_only","source_revision",ReviewedGrpcDescriptorPolicy.SOURCE_REVISION))),
                "product_test",Map.of("contract_version","product-test.v1","test_level","integration","test_type","regression","environment_stage","local","knowledge_mode","not_used","provider_modes",Map.of("betterproto","real"),"dependency_modes",Map.of(),"reviewed_handoff_ref","handoff/reviewed.json")));
        write(suite.resolve("test_cases/streaming.yaml"),Map.of("dsl_version","v0.3","test_case_id","TC-P04-SG02-SERVERSTREAM-REJECT-001","title","Offline reviewed streaming descriptor rejection",
                "execute",List.of(Map.of("id","reject_streaming","target","betterproto","op","unary_call","with",Map.of("grpc.service","service.Test","grpc.method","GetThingVersions","grpc.descriptor_ref","artifact://fixtures/service.desc.b64","grpc.message","artifact://fixtures/request.json"))),
                "verify",List.of(Map.of("id","status_unknown","type","assertion","assert",Map.of("actual","step://reject_streaming/response.status","operator","equals","expected","UNKNOWN")))));
        var review=new LinkedHashMap<String,Object>();review.put("review_ref","test://p04/descriptor-only");review.put("reviewed_by","automated-test-fixture-not-production-approval");
        review.put("reviewed_at",Instant.now().minusSeconds(5).toString());review.put("valid_until",Instant.now().plusSeconds(600).toString());review.put("framework_baseline","0.3.2");
        review.put("workspace_ref","p04-test-workspace");review.put("project_ref","p04-descriptor-only");
        review.put("repository_revisions",Map.of("python-betterproto",ReviewedGrpcDescriptorPolicy.SOURCE_REVISION));
        review.put("swarm_source",Map.of("base_revision","b13c503cc9fadd930e11fb8adc6496308697a966","files",Map.of("fixtures/service.proto",ReviewedGrpcDescriptorPolicy.SOURCE_SHA)));
        var files=new ArrayList<Map<String,String>>();files.add(Map.of("path","suite_manifest.yaml","role","manifest"));files.add(Map.of("path","env_profiles/p04.yaml","role","profile"));files.add(Map.of("path","test_cases/streaming.yaml","role","test"));
        for(String file:ReviewedGrpcDescriptorPolicy.FILES.keySet())files.add(Map.of("path",file,"role","fixture"));review.put("reviewed_files",files);
        review.put("reviewed_contracts",List.of(Map.of("path","contracts/grpc.yaml","provider_contract",ReviewedGrpcDescriptorPolicy.CONTRACT)));
        review.put("requirement_mapping",List.of(Map.of("requirement_ref","P04-SG02-CANDIDATE-NEW-CONTRACT","test_case_ids",List.of("TC-P04-SG02-SERVERSTREAM-REJECT-001"),"oracle_refs",List.of("test_cases/streaming.yaml#status_unknown"),"review_ref","test://p04/descriptor-only")));
        write(suite.resolve("review-input.json"),review);
        new ProductTestHandoffService().prepare(suite.resolve("suite_manifest.yaml"),"p04_descriptor_only",suite.resolve("review-input.json"),null,suite.resolve("handoff/reviewed.json"));
        return suite;
    }
    static void write(Path path,Object value)throws Exception{Files.write(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));}
    static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);checks++;}
    static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("Invalid input/proof was accepted");}
}

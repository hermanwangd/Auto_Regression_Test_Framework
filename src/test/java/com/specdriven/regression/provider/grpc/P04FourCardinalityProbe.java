package com.specdriven.regression.provider.grpc;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.DynamicMessage;
import com.specdriven.regression.contract.v03.V03RuntimeExecutionService;
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.producttest.ProductTestHandoffService;
import com.specdriven.regression.summary.SuiteExecutionContext;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Four real original-provider cases, one absolute deadline, no packaged Module or Python-runtime claim. */
public final class P04FourCardinalityProbe {
 static final ObjectMapper JSON=new ObjectMapper();static long end;static int checks;static Path allocationRoot;
 record Case(String method,String profile,String id,boolean client,boolean server){boolean unary(){return !client&&!server;}}
 static final List<Case> CASES=List.of(
  new Case("DoThing","betterproto.dothing-unary.v1","TC-P04-SG02-UNARY-ADMIT-001",false,false),
  new Case("DoManyThings","betterproto.domanythings-reject.v1","TC-P04-SG02-CLIENTSTREAM-REJECT-001",true,false),
  new Case("GetThingVersions","betterproto.getthingversions-reject.v1","TC-P04-SG02-SERVERSTREAM-REJECT-001",false,true),
  new Case("GetDifferentThings","betterproto.getdifferentthings-reject.v1","TC-P04-SG02-BIDISTREAM-REJECT-001",true,true));
 public static void main(String[] args)throws Exception {
  end=System.nanoTime()+TimeUnit.SECONDS.toNanos(63);checks=0; // reserve two outer-watchdog seconds for terminal finalization
  Path root=Path.of(args[0]).toAbsolutePath();allocationRoot=args.length>2?Path.of(args[2]).toAbsolutePath():root;Files.createDirectory(root);
  Path source=Path.of("samples/41-product-testing/grpc_descriptor_admission/fixtures").toAbsolutePath();
  Path contract=Path.of(args.length>1?args[1]:"docs/02-architecture/contracts/provider-contracts/grpc_client_v0_3.yaml").toAbsolutePath();
  Path launch=root.resolve("owner.args");Files.writeString(launch,"-cp \""+ownerClasspath()+"\"\n"+P04StreamingOwner.class.getName()+"\n");
  byte[] raw=Base64.getDecoder().decode(Files.readAllBytes(source.resolve("service.desc.b64")));
  try(var owner=new Owner(root.resolve("health"),source,CASES.get(2),launch)) {
   var channel=ManagedChannelBuilder.forTarget(owner.endpoint.substring(7)).usePlaintext().maxInboundMessageSize(65536).build();
   try {for(Case c:CASES)health(channel,raw,c);check(owner.calls()==4,"all four handlers must receive a real call");}
   finally{channel.shutdownNow();check(channel.awaitTermination(left(2),TimeUnit.NANOSECONDS),"health channel cleanup");}
  }
  var spy=new ChannelProbe();ManagedChannelRegistry.getDefaultRegistry().register(spy);
  var results=new ArrayList<Map<String,Object>>();
  try {
   try{ManagedChannelBuilder.forTarget("127.0.0.1:1");throw new AssertionError("channel probe not selected");}catch(AssertionError expected){check(spy.attempts==1,"channel sensitivity control");}
   for(Case c:CASES) {
    if(end-System.nanoTime()<TimeUnit.SECONDS.toNanos(10))throw new IllegalStateException("insufficient absolute deadline for another owned case and cleanup");
    spy.attempts=0;spy.allowed=c.unary();
    try(var owner=new Owner(root.resolve(c.method+"-owner"),source,c,launch)) {
     var parsed=ReviewedLoopbackGrpcPolicy.fixture(owner.root,owner.bindings(),true).method();
     check(parsed.getName().equals(c.method)&&parsed.isClientStreaming()==c.client&&parsed.isServerStreaming()==c.server,"actual pinned cardinality");
     guards(owner,c,parsed);
     Path suite=fixture(owner.root,owner,c,source,contract);
     var pin=new ProductTestHandoffService.InvocationRequirement(Files.readString(suite.resolve("handoff/reviewed.json.sha256")).trim(),hash(suite.resolve("fixtures/lease.json")));
     var run=new V03RuntimeExecutionService().runReviewed(suite.resolve("suite_manifest.yaml"),"p04_owned_negative",SuiteExecutionContext.standalone("p04_owned_negative",root.resolve(c.method+"-runs"),"P04"),pin);
     check(run.providerRuntimeExecuted(),"provider did not execute: "+run.findings());
     var result=JSON.readTree(run.resultJson().toFile());var proof=JSON.readTree(run.evidenceDir().resolve(ReviewedGrpcStreamingRejectionProof.PATH).toFile());
     var handoff=JSON.readTree(run.evidenceDir().resolve("product-test/reviewed-handoff.json").toFile());
     boolean correct=c.unary()?"passed".equals(result.path("status").asText())&&"OK".equals(proof.path("status").asText())&&"{\"names\":[\"my milkshakes\"]}".equals(proof.path("body").asText())
       :"failed".equals(result.path("status").asText())&&"UNKNOWN".equals(proof.path("status").asText())&&ReviewedGrpcStreamingRejectionProof.DIAGNOSTIC.equals(result.path("failure").path("reason").asText());
     boolean wrong=c.unary()?"{\"names\":[\"wrong milkshakes\"]}".equals(proof.path("body").asText()):"passed".equals(result.path("status").asText())&&"OK".equals(proof.path("status").asText());
     check(correct&&!wrong,"same-result independent correct/wrong oracle differs: "+result.path("failure"));
     var validation=new EvidenceHardeningService().validateResult(run.resultJson());check(validation.valid(),"canonical evidence invalid: "+validation.findings()+validation.failedEvidenceSummary());
     validate(c,proof,result,handoff);
     for(String field:List.of("method","input_type","descriptor_sha256","fixture_lease_sha256","run_id","provider_type")){ObjectNode bad=proof.deepCopy();bad.put(field,"wrong");rejects(()->validate(c,bad,result,handoff));}
     for(String flag:List.of("client_streaming","server_streaming","channel_created","rpc_attempted","guard_rejection_observed")){ObjectNode bad=proof.deepCopy();bad.put(flag,!proof.path(flag).asBoolean());rejects(()->validate(c,bad,result,handoff));}
     for(Case other:CASES)if(other!=c){ObjectNode bad=handoff.deepCopy();((ObjectNode)bad.path("fixture_lease")).put("profile",other.profile);rejects(()->validate(c,proof,result,bad));}
     if(c.unary())rejects(()->ReviewedGrpcStreamingRejectionProof.validate(proof,result,handoff));else rejects(()->ReviewedGrpcStreamingRejectionProof.validateUnary(proof,result,handoff));
     check(spy.attempts==(c.unary()?1:0)&&owner.calls()==(c.unary()?1:0),"channel/RPC count mismatch");
     results.add(Map.of("case_id",c.id,"method",c.method,"run_id",result.path("run_id").asText(),"result",run.resultJson().toString(),"canonical_status",result.path("status").asText(),"correct_oracle","ACCEPT","wrong_oracle","REJECT","channel_builder_requests",spy.attempts,"owner_calls",owner.calls()));
    }
   }
  }finally{ManagedChannelRegistry.getDefaultRegistry().deregister(spy);}
  check(end-System.nanoTime()>0,"absolute deadline exceeded");
  Files.write(root.resolve("four-results.json"),JSON.writeValueAsBytes(Map.of("status","PASS","internal_checks",checks,"cases",results,"scope","source-overlay Framework; not packaged Module or Python runtime","formal_group_acceptance",false)));
  System.out.println("P04 four-cardinality PASS; internal checks="+checks+"; results="+root.resolve("four-results.json"));
 }
 static void validate(Case c,JsonNode p,JsonNode r,JsonNode h){if(c.unary())ReviewedGrpcStreamingRejectionProof.validateUnary(p,r,h);else ReviewedGrpcStreamingRejectionProof.validate(p,r,h);}
 static void guards(Owner owner,Case c,com.google.protobuf.Descriptors.MethodDescriptor method)throws Exception {
  for(String key:List.of("tls","authority","metadata")){var b=new LinkedHashMap<>(owner.bindings());b.put(key,"forbidden");rejects(()->ReviewedLoopbackGrpcPolicy.fixture(owner.root,b,true));}
  var wrong=new LinkedHashMap<>(owner.bindings());wrong.put("target","grpc://192.0.2.1:12345");rejects(()->ReviewedLoopbackGrpcPolicy.fixture(owner.root,wrong,true));
  Path lease=owner.root.resolve("fixtures/lease.json");byte[] original=Files.readAllBytes(lease);
  for(String variant:List.of("expired","pid","start","nonce","digest")) {ObjectNode bad=(ObjectNode)JSON.readTree(original);
   switch(variant){case "expired"->{bad.put("issued_at","2020-01-01T00:00:00Z");bad.put("expires_at","2020-01-01T00:01:00Z");}case "pid"->bad.put("pid",Long.MAX_VALUE);case "start"->bad.put("process_start_instant","2020-01-01T00:00:00Z");case "nonce"->{bad.put("pid",ProcessHandle.current().pid());bad.put("process_start_instant",ProcessHandle.current().info().startInstant().orElseThrow().toString());}case "digest"->bad.put("descriptor_sha256","0".repeat(64));}
   try{Files.write(lease,JSON.writeValueAsBytes(bad));rejects(()->ReviewedLoopbackGrpcPolicy.fixture(owner.root,owner.bindings(),true));}finally{Files.write(lease,original);}
  }
  if(c.unary()){GrpcDescriptorSupport.requireUnary(method);checks++;}
  else {
   try{GrpcClientProviderRuntime.requireRejectionOnly(method,GrpcDescriptorSupport::requireUnary);throw new AssertionError("shared guard did not reject");}catch(GrpcDescriptorSupport.StreamingDescriptorException expected){checks++;}
   for(boolean unrelated:List.of(false,true))try{GrpcClientProviderRuntime.requireRejectionOnly(method,m->{if(unrelated)throw new IllegalArgumentException("unrelated");});throw new AssertionError("missing guard fell through");}catch(RuntimeException failure){check(!(failure instanceof GrpcDescriptorSupport.StreamingDescriptorException)&&!ReviewedGrpcStreamingRejectionProof.DIAGNOSTIC.equals(GrpcClientProviderRuntime.reviewedFailureReason(false,"UNKNOWN",failure.getMessage())),"false guard accepted by oracle");}
  }
 }
 static void health(ManagedChannel channel,byte[] raw,Case c)throws Exception {
  var m=GrpcDescriptorSupport.method(raw,"service.Test",c.method);check(m.isClientStreaming()==c.client&&m.isServerStreaming()==c.server,"health descriptor flags");
  var type=c.client?(c.server?MethodDescriptor.MethodType.BIDI_STREAMING:MethodDescriptor.MethodType.CLIENT_STREAMING):(c.server?MethodDescriptor.MethodType.SERVER_STREAMING:MethodDescriptor.MethodType.UNARY);
  var wire=MethodDescriptor.<DynamicMessage,DynamicMessage>newBuilder().setType(type).setFullMethodName("service.Test/"+c.method).setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(m.getInputType()))).setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(m.getOutputType()))).build();
  var request=DynamicMessage.newBuilder(m.getInputType()).setField(m.getInputType().findFieldByName("name"),"my milkshakes").build();var options=CallOptions.DEFAULT.withDeadlineAfter(left(5),TimeUnit.NANOSECONDS);List<DynamicMessage> observed=new CopyOnWriteArrayList<>();
  if(c.unary())observed.add(ClientCalls.blockingUnaryCall(channel,wire,options,request));
  else if(!c.client)ClientCalls.blockingServerStreamingCall(channel,wire,options,request).forEachRemaining(observed::add);
  else {var done=new CountDownLatch(1);var errors=new CopyOnWriteArrayList<Throwable>();StreamObserver<DynamicMessage> observer=new StreamObserver<>(){public void onNext(DynamicMessage x){observed.add(x);}public void onError(Throwable e){errors.add(e);done.countDown();}public void onCompleted(){done.countDown();}};
   var input=c.server?ClientCalls.asyncBidiStreamingCall(channel.newCall(wire,options),observer):ClientCalls.asyncClientStreamingCall(channel.newCall(wire,options),observer);input.onNext(request);input.onCompleted();check(done.await(left(5),TimeUnit.NANOSECONDS)&&errors.isEmpty(),"health stream failed "+errors);
  }
  check(observed.size()==(c.server?2:1),"health response count");
  for(int i=0;i<observed.size();i++){var value=observed.get(i);if(c.server)check(value.getField(m.getOutputType().findFieldByName("name")).equals("my milkshakes")&&value.getField(m.getOutputType().findFieldByName("version")).equals(i+1),"health name/version order");else check(value.getField(m.getOutputType().findFieldByName("names")).equals(List.of("my milkshakes")),"health exact names");}
 }
 static Path fixture(Path suite,Owner owner,Case c,Path source,Path contract)throws Exception {
  for(String dir:List.of("fixtures","env_profiles","test_cases","contracts","handoff"))Files.createDirectories(suite.resolve(dir));
  if(!suite.equals(owner.root))for(String name:List.of("service.proto","service.desc.b64","request.json","lease.json"))Files.copy(owner.root.resolve("fixtures/"+name),suite.resolve("fixtures/"+name));
  Files.copy(contract,suite.resolve("contracts/grpc.yaml"));
  write(suite.resolve("suite_manifest.yaml"),Map.of("manifest_version","v0.3","suite_id","P04-OWNED-CARDINALITY","default_profile","p04_owned_negative","artifact_roots",Map.of("fixtures","fixtures/"),"targets",Map.of("betterproto",Map.of("provider_contract","grpc_client.v0.3")),"env_profiles",Map.of("p04_owned_negative",Map.of("ref","env_profiles/p04.yaml")),"tests",List.of(Map.of("ref","test_cases/case.yaml"))));
  write(suite.resolve("env_profiles/p04.yaml"),Map.of("profile_id","p04_owned_negative","execution_mode","local","isolation","per_run","evidence_classification","framework_verification_only","downstream_release_evidence",false,"targets",Map.of("betterproto",Map.of("runtime_mode","native","bindings",owner.bindings())),"product_test",Map.of("contract_version","product-test.v1","test_level","integration","test_type","regression","environment_stage","local","knowledge_mode","not_used","provider_modes",Map.of("betterproto","real"),"dependency_modes",Map.of(),"reviewed_handoff_ref","handoff/reviewed.json")));
  write(suite.resolve("test_cases/case.yaml"),Map.of("dsl_version","v0.3","test_case_id",c.id,"title","Owned Java protocol fixture "+c.method,"execute",List.of(Map.of("id","cardinality","target","betterproto","op","unary_call","with",Map.of("grpc.service","service.Test","grpc.method",c.method,"grpc.descriptor_ref","artifact://fixtures/service.desc.b64","grpc.message","artifact://fixtures/request.json"))),"verify",List.of(Map.of("id","status_oracle","type","assertion","assert",Map.of("actual","step://cardinality/response.status","operator","equals","expected",c.unary()?"OK":"UNKNOWN")))));
  var review=new LinkedHashMap<String,Object>();review.put("review_ref","test://p04/four-cardinality");review.put("reviewed_by","automated-test-fixture-not-production-approval");review.put("reviewed_at",Instant.now().minusSeconds(5).toString());review.put("valid_until",Instant.now().plusSeconds(600).toString());review.put("framework_baseline","0.3.2");review.put("workspace_ref","p04-test-workspace");review.put("project_ref","p04-four-cardinality");review.put("repository_revisions",Map.of("python-betterproto",ReviewedGrpcDescriptorPolicy.SOURCE_REVISION));review.put("swarm_source",Map.of("base_revision","c20829f9b574e753ecb6258aac8f4ba22a9e0b59","files",Map.of("fixtures/service.proto",ReviewedGrpcDescriptorPolicy.SOURCE_SHA)));
  var files=new ArrayList<Map<String,String>>();for(String[] f:new String[][]{{"suite_manifest.yaml","manifest"},{"env_profiles/p04.yaml","profile"},{"test_cases/case.yaml","test"},{"fixtures/service.proto","fixture"},{"fixtures/service.desc.b64","fixture"},{"fixtures/request.json","fixture"},{"fixtures/lease.json","fixture"}})files.add(Map.of("path",f[0],"role",f[1]));review.put("reviewed_files",files);review.put("reviewed_contracts",List.of(Map.of("path","contracts/grpc.yaml","provider_contract","grpc_client.v0.3")));review.put("requirement_mapping",List.of(Map.of("requirement_ref","P04-SG02-PROPOSED-FOUR-CARDINALITY","test_case_ids",List.of(c.id),"oracle_refs",List.of("test_cases/case.yaml#status_oracle"),"review_ref","test://p04/four-cardinality")));write(suite.resolve("review-input.json"),review);
  new ProductTestHandoffService().prepare(suite.resolve("suite_manifest.yaml"),"p04_owned_negative",suite.resolve("review-input.json"),null,suite.resolve("handoff/reviewed.json"));return suite;
 }
 static final class ChannelProbe extends ManagedChannelProvider {
  int attempts;boolean allowed;protected boolean isAvailable(){return true;}protected int priority(){return Integer.MAX_VALUE;}
  protected Collection<Class<? extends java.net.SocketAddress>> getSupportedSocketAddressTypes(){return Set.of(java.net.InetSocketAddress.class);}
  protected ManagedChannelBuilder<?> builderForAddress(String host,int port){return builderForTarget(host+":"+port);}
  protected ManagedChannelBuilder<?> builderForTarget(String target){attempts++;if(!allowed)throw new AssertionError("streaming path tried channel construction");return io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forTarget(target);}
 }
 static final class Owner implements AutoCloseable {
  final Path root;final String id=UUID.randomUUID().toString().replace("-","");final Process process;final String endpoint;
  Owner(Path root,Path source,Case c,Path launch)throws Exception {
   this.root=root;storage(root.getFileName().toString().equals("health")?5000:30000);if(end-System.nanoTime()<TimeUnit.SECONDS.toNanos(10))throw new IllegalStateException("not enough deadline for owner");
   process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx128m","-XX:MaxMetaspaceSize=128m","-Dswarm.grpc.owner_nonce="+id,"@"+launch,root.toString(),source.toString(),c.profile).redirectErrorStream(true).redirectOutput(root.resolveSibling(root.getFileName()+".log").toFile()).start();
   long ready=System.nanoTime()+left(5);while(!Files.exists(root.resolve("owner-ready.json"))&&process.isAlive()&&System.nanoTime()<ready)Thread.sleep(10);
   if(!Files.exists(root.resolve("owner-ready.json"))||!Arrays.asList(process.info().arguments().orElse(new String[0])).contains("-Dswarm.grpc.owner_nonce="+id)){process.destroyForcibly();boolean reaped=process.waitFor(left(2),TimeUnit.NANOSECONDS);throw new IllegalStateException("owner readiness/nonce failed; reaped="+reaped);}
   endpoint=JSON.readTree(root.resolve("owner-ready.json").toFile()).path("endpoint").asText();
  }
  Map<String,Object> bindings(){return Map.of("target",endpoint,"owner_run_id",id);}int calls()throws Exception{return JSON.readTree(root.resolve("owner-calls.json").toFile()).path("calls").asInt();}
  public void close()throws Exception {
   if(process.isAlive()){process.getOutputStream().write("stop\n".getBytes());process.getOutputStream().flush();}
   if(!process.waitFor(left(3),TimeUnit.NANOSECONDS)){process.destroyForcibly();boolean reaped=process.waitFor(left(2),TimeUnit.NANOSECONDS);write(root.resolve("launcher-cleanup.json"),Map.of("reaped",reaped,"forced",true));throw new AssertionError("forced owner termination");}
   check(process.exitValue()==0&&JSON.readTree(root.resolve("owner-cleanup.json").toFile()).path("server_terminated").asBoolean(),"owner cleanup");
   try(var socket=new java.net.Socket()){try{socket.connect(new java.net.InetSocketAddress("127.0.0.1",java.net.URI.create(endpoint).getPort()),100);throw new AssertionError("listener open");}catch(java.io.IOException expected){checks++;}}
   write(root.resolve("launcher-cleanup.json"),Map.of("pid",process.pid(),"reaped",true,"port_closed",true));
  }
 }
 static String ownerClasspath()throws Exception {
  // Collapse a jar directory only when its exact regular-jar set equals the bound classpath set. Launcher expands it; compiler never uses this shortcut.
  var entries=List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator));var jars=new LinkedHashMap<Path,Set<Path>>();
  for(String s:entries){Path p=Path.of(s).toAbsolutePath();if(s.endsWith(".jar"))jars.computeIfAbsent(p.getParent(),x->new HashSet<>()).add(p);}
  var compact=new HashSet<Path>();for(var e:jars.entrySet())try(var paths=Files.list(e.getKey())){var actual=paths.filter(p->p.toString().endsWith(".jar")&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).collect(java.util.stream.Collectors.toSet());if(actual.equals(e.getValue()))compact.add(e.getKey());}
  var output=new LinkedHashSet<String>();for(String s:entries){Path p=Path.of(s).toAbsolutePath();output.add(s.endsWith(".jar")&&compact.contains(p.getParent())?p.getParent()+"/*":p.toString());}return String.join(java.io.File.pathSeparator,output);
 }
 static void storage(long upcoming)throws Exception {long used;try(var paths=Files.walk(allocationRoot)){used=paths.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}).sum();}if(used+upcoming+8000>830000)throw new IllegalStateException("insufficient retained-output/finalization storage before spawn: used="+used+", upcoming="+upcoming);}
 static long left(int seconds){long left=end-System.nanoTime();if(left<=0)throw new IllegalStateException("absolute harness deadline exhausted");return Math.min(left,TimeUnit.SECONDS.toNanos(seconds));}
 static void write(Path p,Object o)throws Exception{Files.write(p,JSON.writeValueAsBytes(o));}static String hash(Path p)throws Exception{return ReviewedLoopbackGrpcPolicy.digest(Files.readAllBytes(p));}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
 @FunctionalInterface interface Check{void run()throws Exception;}static void rejects(Check c)throws Exception{try{c.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("invalid control accepted");}
}

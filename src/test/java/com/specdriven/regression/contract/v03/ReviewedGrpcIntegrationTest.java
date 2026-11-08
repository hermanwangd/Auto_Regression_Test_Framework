package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.*;
import com.specdriven.regression.producttest.ProductTestHandoffService;
import com.specdriven.regression.producttest.ProductTestHandoffService.InvocationRequirement;
import com.specdriven.regression.provider.grpc.GrpcEchoOwner;
import com.specdriven.regression.summary.SuiteExecutionContext;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewedGrpcIntegrationTest {
 @TempDir Path temporary;
 final ReviewedLoopbackRestTest helper=new ReviewedLoopbackRestTest();
 @org.junit.jupiter.api.AfterEach void retainEvidence() throws Exception {
  Path output=Path.of("target/grpc-acceptance").resolve(UUID.randomUUID().toString());
  try(var files=Files.walk(temporary)) {for(Path file:files.toList()) {
   Path target=output.resolve(temporary.relativize(file));
   if(Files.isDirectory(file))Files.createDirectories(target);else Files.copy(file,target);
  }}
 }
 @Test void separateJvmTcpEchoProducesCanonicalNativeEvidence() throws Exception {
  try(var owner=owner("echo")) {var result=run(fixture(owner,false),true);assertThat(result.status()).as(result.findings().toString()).isEqualTo("passed");helper.assertValid(result);assertThat(owner.receipts()).isEqualTo(1);assertAttempt(result,true,"OK");}
 }
 @ParameterizedTest @ValueSource(strings={"wrong_oracle","down","timeout","inbound_overflow","json_overflow"})
 void actualFailureAndFreshOwnerRecovery(String mode) throws Exception {
  try(var owner=owner(mode)) {
   Path suite=fixture(owner,mode.equals("wrong_oracle"));
   if(mode.equals("down")) owner.down();
   var result=run(suite,true);assertThat(result.status()).as(result.findings().toString()).isEqualTo("failed");helper.assertValid(result);
   assertThat(owner.receipts()).isEqualTo(mode.equals("down")?0:1);
   assertAttempt(result,true,switch(mode){case "wrong_oracle"->"OK";case "down"->"UNAVAILABLE";case "timeout"->"DEADLINE_EXCEEDED";default->"RESOURCE_EXHAUSTED";});
  }
  try(var fresh=owner("echo")) {var result=run(fixture(fresh,false),true);assertThat(result.status()).as(result.findings().toString()).isEqualTo("passed");assertThat(fresh.receipts()).isEqualTo(1);helper.assertValid(result);}
 }
 @Test void independentLeasePinRequiredBeforeDispatch() throws Exception {
  try(var owner=owner("echo")) {Path suite=fixture(owner,false);var result=run(suite,false);assertThat(result.status()).isEqualTo("blocked");assertThat(result.providerRuntimeExecuted()).isFalse();assertThat(owner.receipts()).isZero();}
 }
 @ParameterizedTest @ValueSource(strings={"expired","pid_reuse","nonce","lease_pin","request_tamper","descriptor_tamper"})
 void badLeaseAndChangedBytesRejectBeforeProvider(String variant) throws Exception {
  try(var owner=owner("echo")) {
   Path suite=fixture(owner,false);
   if(Set.of("expired","pid_reuse","nonce").contains(variant)) {
    var lease=helper.json.readValue(suite.resolve("fixtures/lease.json").toFile(),Map.class);
    if(variant.equals("expired")) {lease.put("issued_at","2020-01-01T00:00:00Z");lease.put("expires_at","2020-01-01T00:01:00Z");}
    if(variant.equals("pid_reuse")) lease.put("process_start_instant","2020-01-01T00:00:00Z");
    if(variant.equals("nonce")) {lease.put("pid",ProcessHandle.current().pid());lease.put("process_start_instant",ProcessHandle.current().info().startInstant().orElseThrow().toString());}
    helper.json.writeValue(suite.resolve("fixtures/lease.json").toFile(),lease);
   }
   helper.prepare(suite);var pin=pin(suite,variant.equals("lease_pin")?"0".repeat(64):hash(suite.resolve("fixtures/lease.json")));
   if(variant.equals("request_tamper"))Files.writeString(suite.resolve("fixtures/request.json"),"{\"message\":\"changed\"}");
   if(variant.equals("descriptor_tamper"))Files.writeString(suite.resolve("fixtures/echo.desc.b64"),"AAAA");
   var result=new V03RuntimeExecutionService().runReviewed(suite.resolve("suite_manifest.yaml"),"local_native",context(),pin);
   assertThat(result.status()).isEqualTo("blocked");assertThat(result.providerRuntimeExecuted()).isFalse();assertThat(owner.receipts()).isZero();
  }
 }
 @ParameterizedTest @ValueSource(strings={"metadata","tls","authority","auth","remote","run_mismatch","endpoint_mismatch","missing_descriptor","oversize_descriptor","imported_descriptor","streaming","wrong_field","extra_request_field","base64_whitespace"})
 void unsupportedProfileRejectsBeforeProvider(String variant)throws Exception {
  try(var owner=owner("echo")) {
   Path suite=fixture(owner,false);
   var profile=helper.read(suite.resolve("env_profiles/local_native.yaml"));
   var binding=helper.map(helper.map(helper.map(profile.get("targets")).get("todo_api")).get("bindings"));
   var test=helper.read(suite.resolve("test_cases/items.yaml"));
   var input=helper.map(helper.map(((List<?>)test.get("execute")).get(0)).get("with"));
   switch(variant) {
    case "metadata"->input.put("grpc.metadata.authorization","Bearer forbidden");
    case "tls"->binding.put("tls",true);
    case "authority"->binding.put("authority","localhost");
    case "auth"->binding.put("auth","secret");
    case "remote"->binding.put("target","grpc://192.0.2.1:12345");
    case "run_mismatch"->binding.put("owner_run_id","0".repeat(32));
    case "endpoint_mismatch"->binding.put("target","grpc://127.0.0.1:1");
    case "missing_descriptor"->Files.delete(suite.resolve("fixtures/echo.desc.b64"));
    case "oversize_descriptor"->Files.writeString(suite.resolve("fixtures/echo.desc.b64"),"A".repeat(350*1024+1));
    case "extra_request_field"->Files.writeString(suite.resolve("fixtures/request.json"),"{\"message\":\"swarm-probe\",\"extra\":true}");
    case "base64_whitespace"->Files.writeString(suite.resolve("fixtures/echo.desc.b64"),Files.readString(suite.resolve("fixtures/echo.desc.b64"))+"\n");
    default->{
     var set=com.google.protobuf.DescriptorProtos.FileDescriptorSet.parseFrom(Base64.getDecoder().decode(Files.readAllBytes(suite.resolve("fixtures/echo.desc.b64")))).toBuilder();
     if(variant.equals("imported_descriptor"))set.getFileBuilder(0).addDependency("remote.proto");
     if(variant.equals("streaming"))set.getFileBuilder(0).getServiceBuilder(0).getMethodBuilder(0).setServerStreaming(true);
     if(variant.equals("wrong_field"))set.getFileBuilder(0).getMessageTypeBuilder(0).getFieldBuilder(0).setType(com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_BYTES);
     Files.write(suite.resolve("fixtures/echo.desc.b64"),Base64.getEncoder().encode(set.build().toByteArray()));
    }
   }
   helper.write(suite.resolve("env_profiles/local_native.yaml"),profile);helper.write(suite.resolve("test_cases/items.yaml"),test);
   assertThatThrownBy(()->helper.prepare(suite)).isInstanceOf(IllegalArgumentException.class);
   assertThat(owner.receipts()).isZero();
  }
 }
 void assertAttempt(V03RuntimeExecutionService.V03RuntimeRunResult result,boolean attempted,String status)throws Exception {
  var proof=helper.json.readTree(result.evidenceDir().resolve("provider-evidence/grpc-client/reviewed-unary.json").toFile());
  assertThat(proof.path("rpc_attempted").asBoolean()).isEqualTo(attempted);assertThat(proof.path("cleanup_confirmed").asBoolean()).isTrue();assertThat(proof.path("status").asText()).isEqualTo(status);
 }
 V03RuntimeExecutionService.V03RuntimeRunResult run(Path suite,boolean lease)throws Exception {helper.prepare(suite);return new V03RuntimeExecutionService().runReviewed(suite.resolve("suite_manifest.yaml"),"local_native",context(),pin(suite,lease?hash(suite.resolve("fixtures/lease.json")):null));}
 SuiteExecutionContext context()throws Exception{return SuiteExecutionContext.standalone("local_native",temporary.toRealPath().resolve("runs"),"V03");}
 InvocationRequirement pin(Path suite,String lease)throws Exception{return new InvocationRequirement(Files.readString(suite.resolve("handoff/reviewed.json.sha256")).trim(),lease);}
 String hash(Path file)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
 Path fixture(Owner owner,boolean wrong)throws Exception {
  helper.temporary=temporary;Path suite=helper.fixture(owner.endpoint.replace("grpc://","http://"),"GET",false);
  var manifest=helper.read(suite.resolve("suite_manifest.yaml"));helper.map(helper.map(manifest.get("targets")).get("todo_api")).put("provider_contract","grpc_client.v0.3");helper.map(manifest.get("artifact_roots")).put("fixtures","fixtures/");helper.map(manifest.get("artifact_roots")).put("expected","expected/");helper.write(suite.resolve("suite_manifest.yaml"),manifest);
  var profile=helper.read(suite.resolve("env_profiles/local_native.yaml"));helper.map(helper.map(profile.get("targets")).get("todo_api")).put("bindings",Map.of("target",owner.endpoint,"owner_run_id",owner.id));helper.write(suite.resolve("env_profiles/local_native.yaml"),profile);
  Files.createDirectory(suite.resolve("fixtures"));for(String file:List.of("echo.desc.b64","request.json","lease.json"))Files.copy(owner.root.resolve("fixtures/"+file),suite.resolve("fixtures/"+file));
  Files.createDirectories(suite.resolve("expected"));Files.writeString(suite.resolve("expected/echo.json"),"{\"message\":\""+(wrong?"independent-wrong-oracle":"swarm-probe")+"\"}");
  helper.write(suite.resolve("test_cases/items.yaml"),Map.of("dsl_version","v0.3","test_case_id","TC-HTTP","title","Reviewed gRPC unary","execute",List.of(Map.of("id","echo","target","todo_api","op","unary_call","with",Map.of("grpc.service","swarm.producttest.EchoService","grpc.method","Echo","grpc.descriptor_ref","artifact://fixtures/echo.desc.b64","grpc.message","artifact://fixtures/request.json","grpc.timeout","PT5S"))),"verify",List.of(Map.of("id","oracle","type","assertion","assert",Map.of("actual","step://echo/response.message","operator","json_match","expected_ref","artifact://expected/echo.json")))));
  Files.copy(Path.of("docs/02-architecture/contracts/provider-contracts/grpc_client_v0_3.yaml"),suite.resolve("contracts/grpc.yaml"));
  var review=helper.json.readValue(suite.resolve("review-input.json").toFile(),Map.class);review.put("reviewed_contracts",List.of(Map.of("path","contracts/grpc.yaml","provider_contract","grpc_client.v0.3")));
  var files=(List<Object>)review.get("reviewed_files");for(String name:List.of("echo.desc.b64","request.json","lease.json"))files.add(Map.of("path","fixtures/"+name,"role","fixture"));files.add(Map.of("path","expected/echo.json","role","oracle"));helper.map(((List<?>)review.get("requirement_mapping")).get(0)).put("oracle_refs",List.of("expected/echo.json"));helper.json.writeValue(suite.resolve("review-input.json").toFile(),review);return suite;
 }
 Owner owner(String mode)throws Exception {return new Owner(temporary.resolve("owner-"+UUID.randomUUID()),mode);}
 final class Owner implements AutoCloseable {
  final Path root;final String id=UUID.randomUUID().toString().replace("-","");final Process process;final String endpoint;
  Owner(Path root,String mode)throws Exception {
   this.root=root;Files.createDirectories(root);
   Files.writeString(root.resolve("launcher.args"),"-cp \""+System.getProperty("java.class.path")+"\"\n"+GrpcEchoOwner.class.getName()+"\n");
   process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx128m","-XX:MaxMetaspaceSize=128m","-Dswarm.grpc.owner_nonce="+id,"@"+root.resolve("launcher.args"),root.toString(),mode).redirectErrorStream(true).redirectOutput(root.resolve("owner.log").toFile()).start();
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
   while(!Files.exists(root.resolve("owner-ready.json")) && process.isAlive() && System.nanoTime()<end)Thread.sleep(20);
   if(!Files.exists(root.resolve("owner-ready.json"))) {process.destroyForcibly();if(!process.waitFor(2,TimeUnit.SECONDS))throw new IllegalStateException("Owned fixture startup failed and PID termination was not confirmed");throw new IllegalStateException("Owned fixture did not publish atomic readiness within 30s: "+Files.readString(root.resolve("owner.log")));}
   endpoint=helper.json.readTree(root.resolve("owner-ready.json").toFile()).path("endpoint").asText();
   assertThat(Arrays.asList(process.info().arguments().orElse(new String[0]))).contains("-Dswarm.grpc.owner_nonce="+id);
  }
  int receipts()throws Exception {Path file=root.resolve("owner-receipts.jsonl");return Files.exists(file)?Files.readAllLines(file).size():0;}
  void down()throws Exception {process.getOutputStream().write("down\n".getBytes());process.getOutputStream().flush();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!Files.exists(root.resolve("owner-down"))&&System.nanoTime()<end)Thread.sleep(10);assertThat(Files.exists(root.resolve("owner-down"))).isTrue();assertThat(process.isAlive()).isTrue();}
  public void close()throws Exception {
   process.getOutputStream().write("stop\n".getBytes());process.getOutputStream().flush();
   if(!process.waitFor(3,TimeUnit.SECONDS)){process.destroyForcibly();assertThat(process.waitFor(2,TimeUnit.SECONDS)).isTrue();}
   assertThat(process.exitValue()).isZero();
   try(var socket=new java.net.Socket()) {assertThatThrownBy(()->socket.connect(new java.net.InetSocketAddress("127.0.0.1",java.net.URI.create(endpoint).getPort()),100)).isInstanceOf(java.io.IOException.class);}
   Files.writeString(root.resolve("cleanup.json"),"{\"pid\":"+process.pid()+",\"reaped\":true,\"port_closed\":true}");
  }
 }

 @ParameterizedTest @ValueSource(strings={"absent","empty"})
 void defaultMethodOptionsRemainCompatible(String mode)throws Exception {
  try(var owner=owner("echo")) {
   Path suite=fixture(owner,false);
   Path descriptor=suite.resolve("fixtures/echo.desc.b64");
   var set=com.google.protobuf.DescriptorProtos.FileDescriptorSet.parseFrom(Base64.getDecoder().decode(Files.readAllBytes(descriptor))).toBuilder();
   var rpc=set.getFileBuilder(0).getServiceBuilder(0).getMethodBuilder(0);
   if(mode.equals("empty"))rpc.setOptions(com.google.protobuf.DescriptorProtos.MethodOptions.getDefaultInstance());else rpc.clearOptions();
   Files.write(descriptor,Base64.getEncoder().encode(set.build().toByteArray()));
   var lease=helper.json.readValue(suite.resolve("fixtures/lease.json").toFile(),Map.class);
   lease.put("descriptor_sha256",hash(descriptor));helper.json.writeValue(suite.resolve("fixtures/lease.json").toFile(),lease);
   var result=run(suite,true);assertThat(result.status()).as(result.findings().toString()).isEqualTo("passed");
   helper.assertValid(result);assertThat(owner.receipts()).isEqualTo(1);
  }
 }
 @ParameterizedTest @ValueSource(strings={"nondefault","unknown"})
 void nondefaultMethodOptionsRejectBeforeProvider(String mode)throws Exception {
  try(var owner=owner("echo")) {
   Path suite=fixture(owner,false);
   Path descriptor=suite.resolve("fixtures/echo.desc.b64");
   var set=com.google.protobuf.DescriptorProtos.FileDescriptorSet.parseFrom(Base64.getDecoder().decode(Files.readAllBytes(descriptor))).toBuilder();
   var options=com.google.protobuf.DescriptorProtos.MethodOptions.newBuilder();
   if(mode.equals("nondefault"))options.setIdempotencyLevel(com.google.protobuf.DescriptorProtos.MethodOptions.IdempotencyLevel.IDEMPOTENT);
   else options.setUnknownFields(com.google.protobuf.UnknownFieldSet.newBuilder().addField(50000,com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build());
   set.getFileBuilder(0).getServiceBuilder(0).getMethodBuilder(0).setOptions(options);
   Files.write(descriptor,Base64.getEncoder().encode(set.build().toByteArray()));
   var lease=helper.json.readValue(suite.resolve("fixtures/lease.json").toFile(),Map.class);
   lease.put("descriptor_sha256",hash(descriptor));helper.json.writeValue(suite.resolve("fixtures/lease.json").toFile(),lease);
   assertThatThrownBy(()->helper.prepare(suite)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only unary Echo");
   assertThat(owner.receipts()).isZero();
  }
 }
}

package com.specdriven.regression.runner;
import java.io.PrintStream;
import java.util.*;
/** Additional same-framework CLI surface. Transport operations require independent bindings. */
public final class RunnerCommand {
 public int execute(String[] args,PrintStream out,PrintStream err){
  try{
   Map<String,String> options=new LinkedHashMap<>();
   for(int i=1;i<args.length;i++){RunnerFiles.require(args[i].startsWith("--")&&i+1<args.length,"explicit_option_value_required");String name=args[i++];RunnerFiles.require(options.putIfAbsent(name,args[i])==null,"duplicate_option");}
   if(args[0].equals("runner-observe-producer")){RunnerFiles.require(options.isEmpty(),"producer_observation_has_no_input_options");out.println(RunnerFiles.JSON.writeValueAsString(RunnerProducerObserver.observe()));return 0;}
   if(args[0].equals("runner-import")||args[0].equals("runner-export")){
    RunnerFiles.require(options.keySet().equals(Set.of("--collection-spec","--expected-spec-sha256","--source","--out")),"independent_collection_spec_required");
    var result=new BoundedEvidenceCollector().collect(java.nio.file.Path.of(options.get("--collection-spec")),options.get("--expected-spec-sha256"),java.nio.file.Path.of(options.get("--source")),java.nio.file.Path.of(options.get("--out")));
    out.println("runner_status: "+(result.complete()?"COLLECTED":"INCOMPLETE"));out.println("collection_json: "+result.receipt().toAbsolutePath());return result.complete()?0:1;
   }
   if(args[0].equals("runner-package")){
    RunnerFiles.require(options.keySet().equals(Set.of("--dependencies-lock","--expected-lock-sha256","--roots-binding","--expected-roots-sha256","--out")),"independent_lock_roots_options_required");
    var service=new RunnerBundleService();var admitted=service.inspectBindings(java.nio.file.Path.of(options.get("--dependencies-lock")),options.get("--expected-lock-sha256"),java.nio.file.Path.of(options.get("--roots-binding")),options.get("--expected-roots-sha256"));
    var result=service.exportBundle(admitted,java.nio.file.Path.of(options.get("--out")));
    out.println("runner_status: ADMITTED");out.println("source_bindings_json: "+result.sourceBindings().toAbsolutePath());out.println("source_bindings_sha256: "+result.bindingsSha256());out.println("bundle_manifest: "+result.bundleManifest().toAbsolutePath());out.println("bundle_manifest_sha256: "+result.manifestSha256());return 0;
   }
   throw new IllegalArgumentException(args[0].equals("runner-execute")?"authorized_runtime_image_sut_binding_missing_NO_NATIVE_EXECUTION":"independent_lock_roots_or_collection_binding_required");
  }catch(Exception error){out.println("runner_status: BLOCKED");err.println("runner_reason: "+RunnerDiagnostic.code(error));err.println("owner_action: Check independently pinned inputs and controller-owned output; retain incomplete evidence.");return 1;}
 }
}

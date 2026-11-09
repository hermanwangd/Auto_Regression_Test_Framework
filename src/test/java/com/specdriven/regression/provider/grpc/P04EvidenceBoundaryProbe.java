package com.specdriven.regression.provider.grpc;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.producttest.ProductTestHandoffService;
import java.nio.file.*;
import java.util.*;
/** Same retained result only. No provider, listener or client invocation. */
public final class P04EvidenceBoundaryProbe {
 static final ObjectMapper JSON=new ObjectMapper();
 public static void main(String[] args)throws Exception {
  Path result=Path.of(args[0]),run=result.getParent(),suite=Path.of(args[1]);int checks=0;
  Path proof=run.resolve(ReviewedGrpcStreamingRejectionProof.PATH);byte[] original=Files.readAllBytes(proof);
  String text=new String(original,java.nio.charset.StandardCharsets.UTF_8);
  for(String invalid:List.of("{\"schema\":\"duplicate\","+text.substring(1),text+" {}")) {
   try {Files.writeString(proof,invalid);reject(result);checks++;}finally{Files.write(proof,original);}
  }
  Path index=run.resolve(JSON.readTree(result.toFile()).path("evidence_index_ref").asText());byte[] oldIndex=Files.readAllBytes(index);
  try {
   var map=(Map<String,Object>)new org.yaml.snakeyaml.Yaml().load(new String(oldIndex,java.nio.charset.StandardCharsets.UTF_8));
   var entries=(List<Map<String,Object>>)map.get("entries");entries.removeIf(e->ReviewedGrpcStreamingRejectionProof.PATH.equals(e.get("file_path")));
   Files.writeString(index,new org.yaml.snakeyaml.Yaml().dump(map));reject(result);checks++;
  } finally {Files.write(index,oldIndex);}
  byte[] oldResult=Files.readAllBytes(result);
  try {ObjectNode changed=(ObjectNode)JSON.readTree(oldResult);removeProofRefs(changed);Files.write(result,JSON.writeValueAsBytes(changed));reject(result);checks++;}finally{Files.write(result,oldResult);}
  Path review=suite.resolve("review-input.json");byte[] oldReview=Files.readAllBytes(review);
  for(String variant:List.of("source_revision","source_closure")) {
   try {
    ObjectNode changed=(ObjectNode)JSON.readTree(oldReview);
    if(variant.equals("source_revision"))((ObjectNode)changed.path("repository_revisions")).put("python-betterproto","0".repeat(40));
    else {ArrayNode files=(ArrayNode)changed.path("reviewed_files");for(int i=files.size()-1;i>=0;i--)if("fixtures/service.proto".equals(files.get(i).path("path").asText()))files.remove(i);}
    Files.write(review,JSON.writeValueAsBytes(changed));
    try {new ProductTestHandoffService().prepare(suite.resolve("suite_manifest.yaml"),"p04_owned_negative",review,null,suite.resolve("handoff/invalid-control.json"));throw new AssertionError("invalid source review accepted");}catch(IllegalArgumentException expected){checks++;}
   } finally {Files.write(review,oldReview);}
  }
  if(!Arrays.equals(original,Files.readAllBytes(proof))||!Arrays.equals(oldIndex,Files.readAllBytes(index))||!Arrays.equals(oldResult,Files.readAllBytes(result))||!Arrays.equals(oldReview,Files.readAllBytes(review)))throw new AssertionError("control did not restore exact bytes");checks++;
  if(!new EvidenceHardeningService().validateResult(result).valid())throw new AssertionError("restored result invalid");checks++;
  System.out.println("P04 evidence boundary controls PASS: "+checks+"; same result, no new invocation");
 }
 static void removeProofRefs(JsonNode node){if(node.isArray()){ArrayNode a=(ArrayNode)node;for(int i=a.size()-1;i>=0;i--){if(a.get(i).isTextual()&&ReviewedGrpcStreamingRejectionProof.PATH.equals(a.get(i).asText()))a.remove(i);else removeProofRefs(a.get(i));}}else if(node.isObject())node.elements().forEachRemaining(P04EvidenceBoundaryProbe::removeProofRefs);}
 static void reject(Path result)throws Exception {if(new EvidenceHardeningService().validateResult(result).valid())throw new AssertionError("invalid boundary accepted");}
}

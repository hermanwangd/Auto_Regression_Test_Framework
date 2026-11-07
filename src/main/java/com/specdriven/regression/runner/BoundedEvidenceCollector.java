package com.specdriven.regression.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.specdriven.regression.contract.*;
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.report.CoverageReportService;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/** Finite collector, independent from product verdict and transport exit status. */
public final class BoundedEvidenceCollector {
    private final RunnerDurability durability;
    public BoundedEvidenceCollector(){this(RunnerDurability.SYSTEM);}
    BoundedEvidenceCollector(RunnerDurability durability){this.durability=Objects.requireNonNull(durability);}
    public record Collected(Path receipt,JsonNode document,boolean complete){}
    private static final Set<String> ROLES=Set.of("canonical","index","summary","evidence","report","coverage_json","coverage_md","code_coverage","log","runtime_status");
    private static final Pattern LOG_SECRET=Pattern.compile("(?im)(authorization\\s*:\\s*|(?:password|access_token|api_key|credential)\\s*[:=]\\s*)[^\\r\\n]+|(?i:bearer\\s+[A-Za-z0-9._~+/=-]+)");
    private static void require(boolean ok,String reason){RunnerFiles.require(ok,reason);}
    public Collected collect(Path spec,String expectedSpecSha256,Path source,Path freshOutput)throws IOException{
        JsonNode config=RunnerFiles.pinned(spec,expectedSpecSha256);
        RunnerFiles.fields(config,"schema_version","run_id","correlation_id","payload_profile","deadline_ms","canonical_result","artifact_versions","files");require(RunnerFiles.text(config,"schema_version").equals("runner-collection.v1"),"collection_version_unsupported");String run=RunnerFiles.text(config,"run_id"),correlation=RunnerFiles.text(config,"correlation_id"),profile=RunnerFiles.text(config,"payload_profile");require(Set.of("standard","reviewed_grpc").contains(profile),"collection_profile_unsupported");
        require(config.path("deadline_ms").isIntegralNumber()&&config.path("deadline_ms").asLong()>=1&&config.path("deadline_ms").asLong()<=30000,"collection_deadline_required_1_to_30000ms");long deadline=System.nanoTime()+config.path("deadline_ms").asLong()*1_000_000;
        JsonNode versions=config.path("artifact_versions");RunnerFiles.fields(versions,"runner","framework","provider","source","bundle","sut");for(String key:List.of("runner","framework","provider","source","bundle","sut"))RunnerFiles.text(versions,key);
        require(config.path("canonical_result").isNull()||config.path("canonical_result").isTextual(),"canonical_result_explicit_path_or_null_required");String canonical=config.path("canonical_result").isNull()?null:config.path("canonical_result").asText();
        List<JsonNode> requested=RunnerFiles.array(config,"files",1,4096);RunnerFiles.fresh(freshOutput,durability);
        ObjectNode receipt=RunnerFiles.object();receipt.put("schema_version","runner-collection-receipt.v1");receipt.put("run_id",run);receipt.put("correlation_id",correlation);receipt.put("spec_sha256",expectedSpecSha256);receipt.put("collected_at",Instant.now().toString());receipt.set("artifact_versions",versions.deepCopy());receipt.put("execution","NOT_OBSERVED_BY_COLLECTOR");receipt.putNull("runner_exit_code");receipt.put("framework_verdict","UNKNOWN");receipt.put("validation","NOT_RUN");receipt.put("publication","NOT_RUN");receipt.put("cleanup","NOT_ATTEMPTED");receipt.put("producer_link_revalidation","NOT_APPLICABLE");receipt.put("claim","COLLECTION_FACTS_NOT_PRODUCT_VERDICT_OR_NATIVE_ACCEPTANCE");
        ArrayNode entries=receipt.putArray("files"),gaps=receipt.putArray("gaps");ObjectNode aliases=RunnerFiles.object();aliases.put("schema_version","runner-portable-aliases.v1");ArrayNode mapping=aliases.putArray("aliases");
        var promised=new LinkedHashMap<String,String>();var admitted=new LinkedHashMap<String,byte[]>();boolean invalid=false,partial=false;long total=0,logTotal=0;Set<String> paths=new HashSet<>(),entriesIncludingDirectories=new HashSet<>();
        long metadataReserve=5L*1024*1024;long derivedReserve=canonical==null?0:16L*1024*1024;
        for(JsonNode f:requested){
            ObjectNode entry=entries.addObject();String path="";
            try{
                require(System.nanoTime()<deadline,"collection_deadline_exceeded");RunnerFiles.fields(f,"path","role","bytes","sha256","required");path=RunnerFiles.text(f,"path");RunnerFiles.relative(path);Path counted=Path.of(path);while(counted!=null){entriesIncludingDirectories.add(counted.toString());counted=counted.getParent();}require(entriesIncludingDirectories.size()+8<=4096,"output_entries_including_directories_limit");String role=RunnerFiles.text(f,"role");require(ROLES.contains(role),"unapproved_output_role");require(paths.add(path)&&!Set.of("collection.json","aliases.json","SAVE.complete").contains(path)&&!path.startsWith("controller-derived/"),"duplicate_or_reserved_output_path");
                for(Path component:Path.of(path))require(!Set.of(".ssh",".git",".aws",".env","secrets","credentials","kubeconfig").contains(component.toString().toLowerCase(Locale.ROOT)),"secret_private_configuration_path_excluded");
                entry.put("path",path);require(f.path("required").isBoolean(),"required_output_flag_missing");long limit=role.equals("canonical")&&profile.equals("reviewed_grpc")&&path.equals(canonical)?8L*1024*1024:4L*1024*1024;
                require(f.path("bytes").isIntegralNumber()&&f.path("bytes").asLong()>=0&&f.path("bytes").asLong()<=limit,"output_per_file_byte_limit");long size=f.path("bytes").asLong();String original=RunnerFiles.digest(f,"sha256");entry.put("role",role);entry.put("original_sha256",original);entry.put("original_bytes",size);
                require(total+size+metadataReserve+derivedReserve<=64L*1024*1024,"output_total_byte_limit_including_metadata_and_derived_reserve");total+=size;
                if(role.equals("log")){require(size<=1024*1024&&logTotal+size<=4L*1024*1024,"bounded_log_profile_exceeded_UNRETRIEVED");logTotal+=size;}
                byte[] bytes=RunnerFiles.read(source,path,limit);require(bytes.length==size&&RunnerFiles.sha(bytes).equals(original),"output_hash_or_length_mismatch");require(System.nanoTime()<deadline,"collection_deadline_exceeded");byte[] saved=bytes;
                if(role.equals("log")){require(!RunnerFiles.privateKeyMaterial(bytes),"private_key_log_excluded");String text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);long lines=text.lines().count();require(lines<=2000,"bounded_log_line_limit_UNRETRIEVED");saved=LOG_SECRET.matcher(text).replaceAll("***MASKED***").replaceAll("(?i)(?:https?|grpcs?|nats|mongodb(?:\\+srv)?)://[^\\s/@:]+:[^\\s/@]+@[^\\s]+|jdbc:[^\\s]+|-----BEGIN[^\\r\\n]*PRIVATE KEY-----", "***MASKED***").getBytes(java.nio.charset.StandardCharsets.UTF_8);entry.put("transformation",Arrays.equals(bytes,saved)?"none":"log_mask.v1");}
                else{RunnerFiles.noSecret(bytes);entry.put("transformation","none");}
                RunnerFiles.save(freshOutput,path,saved,durability);admitted.put(path,saved);promised.put(path,RunnerFiles.sha(saved));entry.put("state","SAVED");entry.put("saved_sha256",RunnerFiles.sha(saved));entry.put("saved_bytes",saved.length);entry.put("source_hash_verified",true);
                var alias=mapping.addObject();alias.put("original_ref",path);alias.put("portable_ref",path);alias.put("original_sha256",original);alias.put("saved_sha256",RunnerFiles.sha(saved));
                if(Set.of("report","coverage_json","coverage_md").contains(role)&&new String(bytes,java.nio.charset.StandardCharsets.UTF_8).matches("(?s).*(?:\\\"[^\\\"]*(?:ref|path)\\\"\\s*:\\s*\\\"/|file:/).*"))receipt.put("producer_link_revalidation","BLOCKED_ABSOLUTE_REFERENCE");
            }catch(NoSuchFileException e){partial=true;entry.put("state","MISSING");gaps.addObject().put("code","MISSING_REQUIRED_OR_OPTIONAL_OUTPUT").put("ref",path);}
            catch(IllegalArgumentException|FileSystemException e){invalid=true;entry.put("state","INVALID");ObjectNode gap=gaps.addObject().put("code",safe(e));if(entry.has("path"))gap.put("ref",entry.path("path").asText());else if(!path.isEmpty())gap.put("declared_ref_sha256",RunnerFiles.sha(path.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        }
        receipt.set("validator",RunnerFiles.JSON.valueToTree(RunnerProducerObserver.observe()));
        String verdict="UNKNOWN";boolean valid=false;
        if(canonical!=null&&!invalid){
            try{
                RunnerFiles.relative(canonical);require(admitted.containsKey(canonical),"canonical_output_missing");
                var result=com.specdriven.regression.contract.ResultDocumentReader.readJson(freshOutput.resolve(canonical));JsonNode d=RunnerFiles.JSON.valueToTree(result.fields());require(run.equals(d.path("run_id").asText()),"canonical_run_identity_mismatch");
                Map<String,String> indexedIds=new HashMap<>();
                for(JsonNode requestedFile:requested){String path=requestedFile.path("path").asText();if(requestedFile.path("role").asText().equals("index")&&admitted.containsKey(path)){
                    JsonNode index=RunnerFiles.document(admitted.get(path));JsonNode rows=index.path("entries");require(rows.isArray()&&rows.size()<=4096,"bounded_evidence_index_entries_required");
                    for(JsonNode row:rows){String id=RunnerFiles.text(row,"evidence_id"),ref=RunnerFiles.text(row,"file_path");Path parent=Path.of(path).getParent();String resolved=(parent==null?RunnerFiles.relative(ref):parent.resolve(RunnerFiles.relative(ref))).toString().replace('\\','/');require(indexedIds.putIfAbsent(id,resolved)==null,"duplicate_evidence_identity");}
                }}
                if(d.has("framework_version"))require(versions.path("framework").asText().equals(d.path("framework_version").asText()),"canonical_framework_version_mismatch");
                receipt.putObject("observed_artifact_versions").put("framework",d.path("framework_version").asText("UNKNOWN")).put("other_versions","UNKNOWN_UNTIL_INDEPENDENT_RUNTIME_PROOF");
                if(!closed(d,Path.of(canonical).getParent(),admitted,indexedIds)){partial=true;gaps.addObject().put("code","UNLISTED_OR_MISSING_CANONICAL_REFERENCE");}
                // Check indexed/summary reference closure before calling general validators.
                for(JsonNode requestedFile:requested){String role=requestedFile.path("role").asText(),path=requestedFile.path("path").asText();if(Set.of("index","summary").contains(role)&&admitted.containsKey(path))if(!closed(RunnerFiles.document(admitted.get(path)),Path.of(path).getParent(),admitted,indexedIds)){partial=true;gaps.addObject().put("code","UNLISTED_OR_MISSING_INDEX_REFERENCE");}}
                if(!partial){
                    var evidence=new EvidenceHardeningService().validateResult(freshOutput.resolve(canonical));var report=new ContractBaselineService().report(freshOutput.resolve(canonical));valid=evidence.valid()&&report.valid();receipt.put("validation",valid?"VALID":"INVALID");
                    if(valid){verdict=d.path("status").asText();require(Set.of("passed","failed","blocked","skipped").contains(verdict),"unknown_framework_verdict");
                        var pair=new CoverageReportService().projectCanonical(freshOutput.resolve(canonical),freshOutput.resolve("controller-derived/coverage"),null,null);
                        promised.put("controller-derived/coverage/coverage.json",pair.jsonSha256());promised.put("controller-derived/coverage/coverage.md",pair.markdownSha256());
                        var derived=receipt.putObject("controller_derived_coverage");derived.put("status",pair.status());derived.put("json_sha256",pair.jsonSha256());derived.put("md_sha256",pair.markdownSha256());derived.put("canonical_sha256",RunnerFiles.sha(admitted.get(canonical)));derived.put("identity","NEW_CONTROLLER_DERIVATION_NOT_PRODUCER_REPORT");
                    }
                }
            }catch(IllegalArgumentException|IOException e){invalid=true;receipt.put("validation","INVALID");gaps.addObject().put("code",safe(e));}
        }
        // Verify every original and transformed byte before durable publication; no canonical rewrite.
        for(var item:promised.entrySet())RunnerFiles.forceSaved(freshOutput,item.getKey(),item.getValue(),8L*1024*1024,durability);
        require(System.nanoTime()<deadline,"collection_deadline_exceeded_before_SAVE");receipt.put("framework_verdict",verdict);String state=invalid?"INVALID":partial?"PARTIAL":"COMPLETE";receipt.put("collection",state);receipt.put("publication","SAVED");receipt.put("acceptance",valid&&!partial&&!invalid?"CANONICAL_VALIDATED_REVIEW_REQUIRED":"INCOMPLETE_NO_PRODUCT_VERIFICATION_RECEIPT");
        byte[] aliasBytes=RunnerFiles.bytes(aliases),receiptBytes=RunnerFiles.bytes(receipt);require(aliasBytes.length<=RunnerFiles.TEXT_LIMIT&&receiptBytes.length<=RunnerFiles.TEXT_LIMIT,"collection_metadata_byte_limit");RunnerFiles.save(freshOutput,"aliases.json",aliasBytes,durability);RunnerFiles.save(freshOutput,"collection.json",receiptBytes,durability);durability.forceDirectory(freshOutput);
        boolean complete=!invalid&&!partial&&(canonical==null||valid);if(complete)RunnerFiles.saveCompletion(freshOutput,"BYTE_COLLECTION_SAVED_NOT_PRODUCT_VERDICT\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),durability);
        return new Collected(freshOutput.resolve("collection.json"),receipt.deepCopy(),complete);
    }
    private static String safe(Exception e){return RunnerDiagnostic.code(e);}
    private static boolean closed(JsonNode node,Path base,Map<String,byte[]> admitted,Map<String,String> indexedIds){
        if(node.isObject()){
            var fields=node.fields();while(fields.hasNext()){var field=fields.next();String key=field.getKey();JsonNode value=field.getValue();
                if(Set.of("evidence_index_ref","suite_summary_ref","result_ref","file_path").contains(key)){if(!reference(value,base,admitted,indexedIds))return false;}
                else if(Set.of("evidence_refs","provider_evidence_refs").contains(key)){if(!value.isArray())return false;for(JsonNode ref:value)if(!reference(ref,base,admitted,indexedIds))return false;}
                else if(!closed(value,base,admitted,indexedIds))return false;
            }
        }else if(node.isArray()){for(JsonNode child:node)if(!closed(child,base,admitted,indexedIds))return false;}return true;
    }
    private static boolean reference(JsonNode ref,Path base,Map<String,byte[]> admitted,Map<String,String> indexedIds){
        if(!ref.isTextual())return false;String value=ref.asText();int fragment=value.indexOf('#');if(fragment>=0)value=value.substring(0,fragment);
        if(indexedIds.containsKey(value))return admitted.containsKey(indexedIds.get(value));
        try{Path p=RunnerFiles.relative(value);String resolved=(base==null?p:base.resolve(p)).toString().replace('\\','/');return admitted.containsKey(resolved);}catch(RuntimeException invalid){return false;}
    }
}
